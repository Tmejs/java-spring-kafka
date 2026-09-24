package io.github.tmejs.reservation.orders.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@Import(AuthorizationTest.FixtureConfiguration.class)
class AuthorizationTest extends OrderPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RealmRoleConverter authoritiesConverter;

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCanUseOrdersAndSubjectComesFromJwt() throws Exception {
        mockMvc.perform(post("/orders").with(token("alice-subject", List.of("CUSTOMER"), "orders-api")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/orders/00000000-0000-0000-0000-000000000001")
                        .with(token("alice-subject", List.of("CUSTOMER"), "orders-api")))
                .andExpect(status().isOk())
                .andExpect(content().string("alice-subject"));
    }

    @Test
    void inventoryAdministratorCannotUseOrders() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(token("admin-subject", List.of("INVENTORY_ADMIN"), "orders-api")))
                .andExpect(status().isForbidden());
    }

    @Test
    void monitoringScopeCanUseMetricsButNotOrders() throws Exception {
        var monitoring = token("monitoring", List.of(), "orders-api metrics.read");

        mockMvc.perform(get("/actuator/prometheus").with(monitoring))
                .andExpect(status().isOk());
        mockMvc.perform(post("/orders").with(token("monitoring", List.of(), "orders-api metrics.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotUseMetricsAndUnmatchedRoutesAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(token("alice-subject", List.of("CUSTOMER"), "orders-api")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/unmatched")
                        .with(token("alice-subject", List.of("CUSTOMER"), "orders-api")))
                .andExpect(status().isForbidden());
    }

    @Test
    void swaggerSpecAndHealthArePublic() throws Exception {
        mockMvc.perform(get("/openapi/orders.yaml")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private RequestPostProcessor token(String subject, List<String> roles, String scope) {
        return jwt()
                .jwt(jwt -> jwt.subject(subject)
                        .claim("scope", scope)
                        .claim("realm_access", Map.of("roles", roles)))
                .authorities(authoritiesConverter);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new IllegalArgumentException("Encoded JWT decoding is not used by this policy test");
            };
        }

        @Bean
        FixtureEndpoints fixtureEndpoints(CurrentOwner currentOwner) {
            return new FixtureEndpoints(currentOwner);
        }
    }

    @RestController
    static class FixtureEndpoints {

        private final CurrentOwner currentOwner;

        FixtureEndpoints(CurrentOwner currentOwner) {
            this.currentOwner = currentOwner;
        }

        @PostMapping("/orders")
        String createOrder() {
            return "created";
        }

        @GetMapping("/orders/{id}")
        String getOrder() {
            return currentOwner.subject();
        }

        @GetMapping("/actuator/prometheus")
        String metrics() {
            return "metrics";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "up";
        }

        @GetMapping("/unmatched")
        String unmatched() {
            return "denied";
        }
    }
}
