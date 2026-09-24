package io.github.tmejs.reservation.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import io.github.tmejs.reservation.orders.OrderApplication;
import io.github.tmejs.reservation.orders.support.OrderPostgresIntegrationTest;

@SpringBootTest(classes = OrderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiExposureIT extends OrderPostgresIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void servesOriginalOrdersContract() throws Exception {
        var expected = new ClassPathResource("openapi/orders.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        var response = get("/openapi/orders.yaml");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
    }

    @Test
    void swaggerUiUsesOrdersContractAndPkce() throws Exception {
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);

        var initializer = get("/swagger-ui/swagger-initializer.js");
        var uiConfig = get("/v3/api-docs/swagger-config");

        assertThat(initializer.statusCode()).isEqualTo(200);
        assertThat(initializer.body())
                .contains("\"configUrl\" : \"/v3/api-docs/swagger-config\"")
                .contains("\"clientId\":\"reservation-swagger\"")
                .contains("\"scopes\":\"orders-api\"")
                .contains("\"usePkceWithAuthorizationCodeGrant\":true");
        assertThat(uiConfig.statusCode()).isEqualTo(200);
        assertThat(uiConfig.body()).contains("\"url\":\"/openapi/orders.yaml\"");
    }

    @Test
    void doesNotExposeGeneratedOpenApi() throws Exception {
        assertThat(get("/v3/api-docs").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
