package io.github.tmejs.reservation.orders.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentOwner {

    public String subject() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication && authentication.isAuthenticated()) {
            return jwtAuthentication.getToken().getSubject();
        }
        throw new IllegalStateException("An authenticated JWT principal is required");
    }
}
