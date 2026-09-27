package io.github.tmejs.reservation.orders.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern ORDER_PATH = Pattern.compile("^/orders/([0-9a-fA-F-]{36})$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationId(request.getHeader(HEADER));
        response.setHeader(HEADER, correlationId);
        try (var correlation = MDC.putCloseable("correlationId", correlationId)) {
            Matcher matcher = ORDER_PATH.matcher(request.getRequestURI());
            if (matcher.matches()) {
                try (var order = MDC.putCloseable("orderId", matcher.group(1))) {
                    chain.doFilter(request, response);
                }
            } else {
                chain.doFilter(request, response);
            }
        }
    }

    private static String correlationId(String candidate) {
        return candidate != null && SAFE_CORRELATION.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
