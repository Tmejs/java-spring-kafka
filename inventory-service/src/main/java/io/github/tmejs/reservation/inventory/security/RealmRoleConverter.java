package io.github.tmejs.reservation.inventory.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

@Component
public final class RealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Set<String> ALLOWED_REALM_ROLES = Set.of("CUSTOMER", "INVENTORY_ADMIN");

    private final JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var authorities = new LinkedHashSet<GrantedAuthority>(scopeAuthorities.convert(jwt));
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return authorities;
        }
        roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(ALLOWED_REALM_ROLES::contains)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .forEach(authorities::add);
        return authorities;
    }
}
