package io.github.tmejs.reservation.orders.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

class KeycloakRealmIT {

    private static final DockerImageName KEYCLOAK_IMAGE =
            DockerImageName.parse("quay.io/keycloak/keycloak:26.7.4");
    private static final String ORDERS_SECRET = "test-orders-secret";
    private static final String INVENTORY_SECRET = "test-inventory-secret";
    private static final String MONITORING_SECRET = "test-monitoring-secret";
    private static final Set<String> BUSINESS_ROLES = Set.of("CUSTOMER", "INVENTORY_ADMIN");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void importedRealmIssuesLeastPrivilegeClientCredentialTokens() throws Exception {
        Path realmJson = Path.of(System.getProperty("projectRoot"), "infra/keycloak/reservation-realm.json");

        try (var keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
                .withExposedPorts(8080)
                .withEnv("ORDERS_DEMO_CLIENT_SECRET", ORDERS_SECRET)
                .withEnv("INVENTORY_DEMO_CLIENT_SECRET", INVENTORY_SECRET)
                .withEnv("MONITORING_CLIENT_SECRET", MONITORING_SECRET)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(realmJson),
                        "/opt/keycloak/data/import/reservation-realm.json")
                .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
                .waitingFor(Wait.forHttp("/realms/reservation/.well-known/openid-configuration")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            keycloak.start();

            String issuer = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
                    + "/realms/reservation";
            Jwt orders = token(issuer, "orders-demo", ORDERS_SECRET);
            Jwt inventory = token(issuer, "inventory-demo", INVENTORY_SECRET);
            Jwt monitoring = token(issuer, "monitoring", MONITORING_SECRET);

            assertThat(orders.getAudience()).containsExactly("orders-api");
            assertThat(scopes(orders)).containsExactly("orders-api");
            assertThat(allowedRealmRoles(orders)).containsExactly("CUSTOMER");

            assertThat(inventory.getAudience()).containsExactly("inventory-api");
            assertThat(scopes(inventory)).containsExactly("inventory-api");
            assertThat(allowedRealmRoles(inventory)).containsExactly("INVENTORY_ADMIN");

            assertThat(monitoring.getAudience()).containsExactlyInAnyOrder("orders-api", "inventory-api");
            assertThat(scopes(monitoring)).containsExactlyInAnyOrder("orders-api", "inventory-api", "metrics.read");
            assertThat(allowedRealmRoles(monitoring)).isEmpty();

            var inventoryDecoder = decoder(issuer, "inventory-api");
            assertThatThrownBy(() -> inventoryDecoder.decode(orders.getTokenValue()))
                    .isInstanceOf(JwtValidationException.class);
            var ordersDecoder = decoder(issuer, "orders-api");
            assertThatThrownBy(() -> ordersDecoder.decode(inventory.getTokenValue()))
                    .isInstanceOf(JwtValidationException.class);
        }
    }

    private static Jwt token(String issuer, String clientId, String secret) throws Exception {
        String body = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(secret);
        var request = HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String tokenValue = JSON.readTree(response.body()).get("access_token").asString();
        return decoder(issuer, expectedAudience(clientId)).decode(tokenValue);
    }

    private static NimbusJwtDecoder decoder(String issuer, String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
        var issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        var audienceValidator = new JwtClaimValidator<List<String>>("aud", audiences ->
                audiences != null && audiences.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    private static String expectedAudience(String clientId) {
        return switch (clientId) {
            case "orders-demo" -> "orders-api";
            case "inventory-demo" -> "inventory-api";
            case "monitoring" -> "orders-api";
            default -> throw new IllegalArgumentException("Unknown client");
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Set<String> scopes(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        return scope == null || scope.isBlank()
                ? Set.of()
                : Arrays.stream(scope.split(" ")).collect(Collectors.toSet());
    }

    private static Set<String> allowedRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(BUSINESS_ROLES::contains)
                .collect(Collectors.toSet());
    }
}
