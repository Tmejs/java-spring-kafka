package io.github.tmejs.reservation.inventory.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RealmRoleConverter realmRoleConverter) throws Exception {
        var jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(realmRoleConverter);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/openapi/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus")
                        .hasAuthority("SCOPE_metrics.read")
                        .requestMatchers(HttpMethod.POST, "/products", "/products/", "/products/*/stock")
                        .hasRole("INVENTORY_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/products", "/products/", "/products/**")
                        .hasAnyRole("CUSTOMER", "INVENTORY_ADMIN")
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
