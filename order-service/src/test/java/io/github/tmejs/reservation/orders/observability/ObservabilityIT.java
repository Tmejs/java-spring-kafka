package io.github.tmejs.reservation.orders.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = OrderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "reservation.health.kafka-timeout=100ms", "spring.kafka.bootstrap-servers=127.0.0.1:1" })
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends OrderPostgresIntegrationTest {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ObservabilityIT.class);
    private static final String ISSUER = "https://issuer.example/realms/reservation";
    private static final String KEY_ID = "observability-test-key";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @LocalServerPort private int apiPort;
    @LocalManagementPort private int managementPort;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://"
                + JWKS_SERVER.getAddress().getHostString() + ":" + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void exposesMinimalHealthWhileReadinessFailsFastWhenKafkaIsUnavailable() throws Exception {
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(503);
        assertThat(get(managementPort, "/actuator/health/liveness", null).body()).contains("\"status\":\"UP\"");
        long started = System.nanoTime();
        var readiness = get(managementPort, "/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void protectsPrometheusByAudienceAndScopeOnTheManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", token("another-api", "metrics.read")).statusCode())
                .isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", token("orders-api", "orders-api")).statusCode())
                .isEqualTo(403);
        var scrape = get(managementPort, "/actuator/prometheus", token("orders-api", "metrics.read"));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "reservation_outbox_pending");
        assertThat(get(managementPort, "/actuator/env", token("orders-api", "metrics.read")).statusCode())
                .isIn(403, 404);
        assertThat(get(apiPort, "/actuator/health", token("orders-api", "metrics.read")).statusCode())
                .isIn(403, 404);
    }

    @Test
    void emitsParseableStructuredLogWithCorrelationFields(CapturedOutput output) throws Exception {
        try (var correlation = MDC.putCloseable("correlationId", "correlation-fixture");
                var order = MDC.putCloseable("orderId", "00000000-0000-0000-0000-000000000001")) {
            LOG.info("structured-log-fixture");
        }
        String line = output.getOut().lines().filter(candidate -> candidate.contains("structured-log-fixture"))
                .findFirst().orElseThrow();
        var json = new tools.jackson.databind.ObjectMapper().readTree(line);
        assertThat(json.get("message").asText()).isEqualTo("structured-log-fixture");
        assertThat(json.get("correlationId").asText()).isEqualTo("correlation-fixture");
        assertThat(json.get("orderId").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    private static HttpResponse<String> get(int port, String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String audience, String scope) {
        var rsaKey = new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic())
                .privateKey((RSAPrivateKey) SIGNING_KEY.getPrivate()).keyID(KEY_ID).build();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject("monitoring").audience(List.of(audience))
                .issuedAt(now.minusSeconds(5)).notBefore(now.minusSeconds(5)).expiresAt(now.plusSeconds(300))
                .claim("scope", scope).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims))
                .getTokenValue();
    }

    private static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic())
                    .keyID(KEY_ID).build()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
