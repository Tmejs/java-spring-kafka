package io.github.tmejs.reservation.inventory.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Import(JwtValidationIT.FixtureConfiguration.class)
class JwtValidationIT {

    private static final String KEY_ID = "inventory-test-key";
    private static final String ISSUER = "https://issuer.example/realms/reservation";
    private static final String AUDIENCE = "inventory-api";
    private static final KeyPair SIGNING_KEY = generateKeyPair();
    private static final KeyPair WRONG_KEY = generateKeyPair();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () ->
                "http://" + JWKS_SERVER.getAddress().getHostString() + ":" + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Test
    void acceptsValidToken() throws Exception {
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer " + token(TokenClaims.valid(), SIGNING_KEY)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsTokenSignedByDifferentKey() throws Exception {
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer " + token(TokenClaims.valid(), WRONG_KEY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer "
                        + token(TokenClaims.valid().withIssuer("https://wrong.example/realms/reservation"), SIGNING_KEY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer "
                        + token(TokenClaims.valid().withAudience(List.of("orders-api")), SIGNING_KEY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingAudience() throws Exception {
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer "
                        + token(TokenClaims.valid().withAudience(List.of()), SIGNING_KEY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        var now = Instant.now();
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer "
                        + token(TokenClaims.valid().withTimes(now.minusSeconds(600), now.minusSeconds(300)), SIGNING_KEY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsFutureNotBefore() throws Exception {
        var now = Instant.now();
        mockMvc.perform(get("/products/validation").header("Authorization", "Bearer "
                        + token(TokenClaims.valid().withTimes(now.plusSeconds(600), now.plusSeconds(900)), SIGNING_KEY)))
                .andExpect(status().isUnauthorized());
    }

    private static String token(TokenClaims tokenClaims, KeyPair keyPair) {
        var rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(KEY_ID)
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
        var claims = JwtClaimsSet.builder()
                .issuer(tokenClaims.issuer())
                .subject("alice-subject")
                .audience(tokenClaims.audience())
                .issuedAt(tokenClaims.expiresAt().isBefore(Instant.now())
                        ? tokenClaims.expiresAt().minusSeconds(300)
                        : Instant.now().minusSeconds(5))
                .notBefore(tokenClaims.notBefore())
                .expiresAt(tokenClaims.expiresAt())
                .claim("scope", AUDIENCE)
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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
            var body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
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

    private record TokenClaims(String issuer, List<String> audience, Instant notBefore, Instant expiresAt) {

        static TokenClaims valid() {
            var now = Instant.now();
            return new TokenClaims(ISSUER, List.of(AUDIENCE), now.minusSeconds(5), now.plusSeconds(300));
        }

        TokenClaims withIssuer(String newIssuer) {
            return new TokenClaims(newIssuer, audience, notBefore, expiresAt);
        }

        TokenClaims withAudience(List<String> newAudience) {
            return new TokenClaims(issuer, newAudience, notBefore, expiresAt);
        }

        TokenClaims withTimes(Instant newNotBefore, Instant newExpiresAt) {
            return new TokenClaims(issuer, audience, newNotBefore, newExpiresAt);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean
        ValidationEndpoint validationEndpoint() {
            return new ValidationEndpoint();
        }
    }

    @RestController
    static class ValidationEndpoint {

        @GetMapping("/products/validation")
        String validate() {
            return "validated";
        }
    }
}
