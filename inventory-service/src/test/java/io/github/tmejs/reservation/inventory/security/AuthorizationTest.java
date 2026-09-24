package io.github.tmejs.reservation.inventory.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
class AuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RealmRoleConverter authoritiesConverter;

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCanReadProductsButCannotMutateStock() throws Exception {
        var customer = token("alice-subject", List.of("CUSTOMER"), "inventory-api");

        mockMvc.perform(get("/products").with(customer)).andExpect(status().isOk());
        mockMvc.perform(get("/products/00000000-0000-0000-0000-000000000001")
                        .with(token("alice-subject", List.of("CUSTOMER"), "inventory-api")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/products").with(token("alice-subject", List.of("CUSTOMER"), "inventory-api")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/products/00000000-0000-0000-0000-000000000001/stock")
                        .with(token("alice-subject", List.of("CUSTOMER"), "inventory-api")))
                .andExpect(status().isForbidden());
    }

    @Test
    void inventoryAdministratorCanReadAndMutateProducts() throws Exception {
        mockMvc.perform(get("/products")
                        .with(token("admin-subject", List.of("INVENTORY_ADMIN"), "inventory-api")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/products")
                        .with(token("admin-subject", List.of("INVENTORY_ADMIN"), "inventory-api")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/products/00000000-0000-0000-0000-000000000001/stock")
                        .with(token("admin-subject", List.of("INVENTORY_ADMIN"), "inventory-api")))
                .andExpect(status().isOk());
    }

    @Test
    void monitoringScopeCanUseMetricsButNotBusinessRoutes() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(token("monitoring", List.of(), "inventory-api metrics.read")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/products")
                        .with(token("monitoring", List.of(), "inventory-api metrics.read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotUseMetricsAndUnmatchedRoutesAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(token("alice-subject", List.of("CUSTOMER"), "inventory-api")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/unmatched")
                        .with(token("alice-subject", List.of("CUSTOMER"), "inventory-api")))
                .andExpect(status().isForbidden());
    }

    @Test
    void swaggerSpecAndHealthArePublic() throws Exception {
        mockMvc.perform(get("/openapi/inventory.yaml")).andExpect(status().isOk());
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
        FixtureEndpoints fixtureEndpoints() {
            return new FixtureEndpoints();
        }
    }

    @RestController
    static class FixtureEndpoints {

        @GetMapping({"/products", "/products/{id}"})
        String readProduct() {
            return "product";
        }

        @PostMapping({"/products", "/products/{id}/stock"})
        String mutateProduct() {
            return "mutated";
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
