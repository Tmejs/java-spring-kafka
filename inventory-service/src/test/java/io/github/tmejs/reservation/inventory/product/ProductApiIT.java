package io.github.tmejs.reservation.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import io.github.tmejs.reservation.client.inventory.ApiClient;
import io.github.tmejs.reservation.client.inventory.ApiException;
import io.github.tmejs.reservation.client.inventory.api.ProductsApi;
import io.github.tmejs.reservation.client.inventory.model.AddStockRequest;
import io.github.tmejs.reservation.client.inventory.model.CreateProductRequest;
import io.github.tmejs.reservation.client.inventory.model.Product;
import io.github.tmejs.reservation.inventory.InventoryApplication;
import io.github.tmejs.reservation.inventory.support.InventoryPostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiIT extends InventoryPostgresIntegrationTest {

    private static final String ISSUER = "https://issuer.example/realms/reservation";
    private static final String KEY_ID = "product-api-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final HttpServer JWKS_SERVER = startJwksServer();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () ->
                "http://" + JWKS_SERVER.getAddress().getHostString() + ":"
                        + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @BeforeEach
    void clearProducts() {
        jdbc.execute("truncate table products cascade");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void migratesInventorySchemaAndValidatesMappings() {
        assertThat(jdbc.queryForList(
                        "select table_name from information_schema.tables where table_schema = 'public'",
                        String.class))
                .containsExactlyInAnyOrder(
                        "flyway_schema_history", "products", "reservations", "outbox", "processed_events");
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getName())
                .contains("ProductEntity", "ReservationEntity", "OutboxEntity", "ProcessedEventEntity");
        assertThat(jdbc.queryForObject(
                        "select indexdef from pg_indexes where schemaname = 'public' "
                                + "and tablename = 'outbox' and indexname = 'idx_outbox_pending'",
                        String.class))
                .contains("WHERE (published_at IS NULL)");
    }

    @Test
    void createsDuplicateNamesAndListsStableUuidOrderedPages() throws Exception {
        ProductsApi admin = api("INVENTORY_ADMIN");
        var created = new ArrayList<Product>();
        created.add(admin.createProduct(createRequest("same-name", 3)));
        created.add(admin.createProduct(createRequest("same-name", 5)));
        created.add(admin.createProduct(createRequest("different", 7)));

        assertThat(created).extracting(Product::getId).doesNotHaveDuplicates();
        var expectedIds = created.stream().map(Product::getId).sorted().toList();
        var firstPage = api("CUSTOMER").listProducts(0, 2);
        var secondPage = api("CUSTOMER").listProducts(1, 2);
        var actualIds = new ArrayList<UUID>();
        firstPage.getContent().forEach(product -> actualIds.add(product.getId()));
        secondPage.getContent().forEach(product -> actualIds.add(product.getId()));

        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        assertThat(firstPage.getPage()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(api("CUSTOMER").getProduct(created.getFirst().getId())).isEqualTo(created.getFirst());
        assertThat(admin.createProductWithHttpInfo(createRequest("location", 0))
                        .getHeaders().get("location"))
                .singleElement()
                .asString()
                .startsWith("/products/");
    }

    @Test
    void enforcesAuthenticationRolesAndPaginationBounds() throws Exception {
        Product created = api("INVENTORY_ADMIN").createProduct(createRequest("secured", 1));

        assertThat(api("CUSTOMER").getProduct(created.getId()).getName()).isEqualTo("secured");
        assertApiProblem(() -> api("CUSTOMER").createProduct(createRequest("denied", 0)), 403);
        assertApiProblem(() -> api("CUSTOMER").addStock(created.getId(), stockRequest(1)), 403);
        assertApiProblem(() -> anonymousApi().getProduct(created.getId()), 401);
        assertApiProblem(() -> api("CUSTOMER").listProducts(-1, 20), 400);
        assertApiProblem(() -> api("CUSTOMER").listProducts(0, 101), 400);
    }

    @Test
    void returnsProblemDetailsForInvalidMissingAndOverflowRequests() throws Exception {
        ProductsApi admin = api("INVENTORY_ADMIN");
        assertApiProblem(() -> admin.createProduct(createRequest(" ", -1)), 400);

        Product maximum = admin.createProduct(createRequest("maximum", Integer.MAX_VALUE));
        assertApiProblem(() -> admin.addStock(maximum.getId(), stockRequest(0)), 400);
        assertApiProblem(() -> admin.addStock(maximum.getId(), stockRequest(1)), 409);
        assertApiProblem(() -> api("CUSTOMER").getProduct(UUID.randomUUID()), 404);
    }

    @Test
    void concurrentStockAdditionsDoNotLoseUpdates() throws Exception {
        int before = 10;
        int firstAdd = 7;
        int secondAdd = 11;
        Product created = api("INVENTORY_ADMIN").createProduct(createRequest("concurrent", before));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> addAfterBarrier(created.getId(), firstAdd, ready, start));
            var second = executor.submit(() -> addAfterBarrier(created.getId(), secondAdd, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        Product after = api("CUSTOMER").getProduct(created.getId());
        assertThat(after.getAvailableQuantity()).isEqualTo(before + firstAdd + secondAdd);
    }

    private Product addAfterBarrier(UUID productId, int quantity, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return api("INVENTORY_ADMIN").addStock(productId, stockRequest(quantity));
    }

    private static CreateProductRequest createRequest(String name, int initialQuantity) {
        return new CreateProductRequest().name(name).initialQuantity(initialQuantity);
    }

    private static AddStockRequest stockRequest(int quantity) {
        return new AddStockRequest().quantity(quantity);
    }

    private ProductsApi api(String role) {
        String token = token(role);
        return new ProductsApi(new ApiClient()
                .setScheme("http")
                .setHost("localhost")
                .setPort(port)
                .setRequestInterceptor(request -> request.header("Authorization", "Bearer " + token)));
    }

    private ProductsApi anonymousApi() {
        return new ProductsApi(new ApiClient().setScheme("http").setHost("localhost").setPort(port));
    }

    private static String token(String role) {
        var rsaKey = new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic())
                .privateKey((RSAPrivateKey) SIGNING_KEY.getPrivate())
                .keyID(KEY_ID)
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(role.toLowerCase())
                .audience(List.of("inventory-api"))
                .issuedAt(now.minusSeconds(5))
                .notBefore(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("scope", "inventory-api")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
                .getTokenValue();
    }

    private static void assertApiProblem(ThrowingApiCall call, int expectedStatus) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(expectedStatus);
                    if (expectedStatus >= 400 && expectedStatus != 401 && expectedStatus != 403) {
                        assertThat(exception.getResponseHeaders().firstValue("content-type"))
                                .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
                        var body = JSON.readTree(exception.getResponseBody());
                        assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
                        assertThat(body.get("title").asString()).isNotBlank();
                    }
                });
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
}
