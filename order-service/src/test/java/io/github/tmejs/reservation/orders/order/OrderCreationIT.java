package io.github.tmejs.reservation.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import io.github.tmejs.reservation.client.orders.ApiClient;
import io.github.tmejs.reservation.client.orders.ApiException;
import io.github.tmejs.reservation.client.orders.ApiResponse;
import io.github.tmejs.reservation.client.orders.api.OrdersApi;
import io.github.tmejs.reservation.client.orders.model.CreateOrderRequest;
import io.github.tmejs.reservation.client.orders.model.Order;
import io.github.tmejs.reservation.client.orders.model.OrderItem;
import io.github.tmejs.reservation.client.orders.model.OrderStatus;
import io.github.tmejs.reservation.client.orders.model.RejectionReason;
import io.github.tmejs.reservation.events.EventCodec;
import io.github.tmejs.reservation.orders.OrderApplication;
import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OrderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrderCreationIT.FixedClockConfiguration.class)
class OrderCreationIT extends OrderPostgresIntegrationTest {

    private static final String ISSUER = "https://issuer.example/realms/reservation";
    private static final String KEY_ID = "order-api-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final HttpServer JWKS_SERVER = startJwksServer();
    private static final Instant FIXED_TIME = Instant.parse("2026-09-26T07:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearOrders() {
        jdbc.execute("truncate table order_items, idempotency_keys, outbox, processed_events, orders cascade");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () ->
                "http://" + JWKS_SERVER.getAddress().getHostString() + ":"
                        + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @Test
    void createsAtomicallyAndReplaysOriginalResponseForCanonicalRequest() throws Exception {
        UUID firstProduct = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID secondProduct = UUID.fromString("00000000-0000-4000-8000-000000000001");
        var firstRequest = request(item(firstProduct, 2), item(secondProduct, 1));
        var reorderedRequest = request(item(secondProduct, 1), item(firstProduct, 2));
        OrdersApi alice = api("alice-subject");

        ApiResponse<Order> first = alice.createOrderWithHttpInfo("replay-key", firstRequest);
        jdbc.update(
                "update orders set status = 'REJECTED', rejection_reason = 'INSUFFICIENT_STOCK' where id = ?",
                first.getData().getId());
        ApiResponse<Order> replay = alice.createOrderWithHttpInfo("replay-key", reorderedRequest);

        assertThat(first.getStatusCode()).isEqualTo(202);
        assertThat(replay.getStatusCode()).isEqualTo(202);
        assertThat(replay.getData()).isEqualTo(first.getData());
        assertThat(replay.getData().getId()).isEqualTo(first.getData().getId());
        assertThat(replay.getData().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(replay.getHeaders().get("location")).isEqualTo(first.getHeaders().get("location"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class))
                .isEqualTo(1);

        String payload = jdbc.queryForObject("select payload from outbox", String.class);
        var event = new EventCodec().decodeOrderCreated(payload);
        assertThat(event.metadata().orderId()).isEqualTo(first.getData().getId());
        assertThat(event.metadata().occurredAt()).isEqualTo(FIXED_TIME);
        assertThat(event.items()).extracting(line -> line.productId().toString())
                .containsExactly(secondProduct.toString(), firstProduct.toString());

        Order current = alice.getOrder(first.getData().getId());
        assertThat(current.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(current.getRejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_STOCK);
        assertApiStatus(() -> api("bob-subject").getOrder(first.getData().getId()), 404);
    }

    @Test
    void rejectsChangedPayloadForSameOwnerAndKey() throws Exception {
        OrdersApi alice = api("alice-subject");
        UUID productId = UUID.randomUUID();
        alice.createOrder("conflict-key", request(item(productId, 1)));

        assertApiStatus(() -> alice.createOrder("conflict-key", request(item(productId, 2))), 409);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void serializesConcurrentSameKeyRequestsIntoOneOrderAndOneOutboxEntry() throws Exception {
        UUID productId = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterBarrier("alice-subject", productId, ready, start));
            var second = executor.submit(() -> createAfterBarrier("alice-subject", productId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).getId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).getId());
        }

        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Integer.class)).isEqualTo(1);
    }

    @Test
    void scopesTheSameIdempotencyKeyToEachOwner() throws Exception {
        UUID productId = UUID.randomUUID();

        Order aliceOrder = api("alice-subject").createOrder("shared-key", request(item(productId, 1)));
        Order bobOrder = api("bob-subject").createOrder("shared-key", request(item(productId, 1)));

        assertThat(aliceOrder.getId()).isNotEqualTo(bobOrder.getId());
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Integer.class)).isEqualTo(2);
        assertThat(api("alice-subject").getOrder(aliceOrder.getId()).getId()).isEqualTo(aliceOrder.getId());
        assertThat(api("bob-subject").getOrder(bobOrder.getId()).getId()).isEqualTo(bobOrder.getId());
    }

    @Test
    void rejectsEmptyAndDuplicateProductItemsBeforePersistence() {
        OrdersApi alice = api("alice-subject");
        UUID duplicateProduct = UUID.randomUUID();

        assertApiStatus(() -> alice.createOrder("empty", request()), 400);
        assertApiStatus(() -> alice.createOrder(
                        "duplicate", request(item(duplicateProduct, 1), item(duplicateProduct, 2))),
                400);

        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
    }

    @Test
    void rejectsTwoExactlyIdenticalJsonItemsBeforePersistence() throws Exception {
        UUID productId = UUID.randomUUID();
        String body = """
                {"items":[
                  {"productId":"%s","quantity":2},
                  {"productId":"%s","quantity":2}
                ]}
                """.formatted(productId, productId);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .header("Authorization", "Bearer " + token("alice-subject"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "exact-duplicate")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
    }

    private Order createAfterBarrier(
            String subject, UUID productId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return api(subject).createOrder("concurrent-key", request(item(productId, 3)));
    }

    private OrdersApi api(String subject) {
        String token = token(subject);
        return new OrdersApi(new ApiClient()
                .setScheme("http")
                .setHost("localhost")
                .setPort(port)
                .setRequestInterceptor(request -> request.header("Authorization", "Bearer " + token)));
    }

    private static CreateOrderRequest request(OrderItem... items) {
        return new CreateOrderRequest().items(List.of(items));
    }

    private static OrderItem item(UUID productId, int quantity) {
        return new OrderItem().productId(productId).quantity(quantity);
    }

    private static void assertApiStatus(ThrowingApiCall call, int expectedStatus) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(
                        ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo(expectedStatus));
    }

    private static String token(String subject) {
        var rsaKey = new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic())
                .privateKey((RSAPrivateKey) SIGNING_KEY.getPrivate())
                .keyID(KEY_ID)
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(List.of("orders-api"))
                .issuedAt(now.minusSeconds(5))
                .notBefore(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("scope", "orders-api")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
                .getTokenValue();
    }

    private static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot generate JWT test key", exception);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var publicKey = new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic()).keyID(KEY_ID).build();
            byte[] body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start JWKS test server", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingApiCall {
        void execute() throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
        }
    }
}
