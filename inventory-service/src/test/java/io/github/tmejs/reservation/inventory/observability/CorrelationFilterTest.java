package io.github.tmejs.reservation.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;

class CorrelationFilterTest {
    @Test
    void clearsCorrelationContextWhenRequestFails() {
        var filter = new CorrelationFilter();
        var request = new MockHttpServletRequest("GET", "/products");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            assertThat(MDC.get("correlationId")).isNotBlank();
            throw new ServletException("fixture");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void messageContextClearsIdsAndPreservesExistingContextWhenProcessingFails() {
        MDC.put("traceId", "existing");
        assertThatThrownBy(() -> CorrelationContext.withMessageIds(UUID.randomUUID(), UUID.randomUUID(), () -> {
            assertThat(MDC.get("orderId")).isNotBlank();
            assertThat(MDC.get("eventId")).isNotBlank();
            throw new IllegalStateException("fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("eventId")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("existing");
        MDC.remove("traceId");
    }
}
