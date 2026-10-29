package com.rosterguard.security;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The verified identity of the caller, built only from a validated JWT.
 * The tenant used for every data access comes from here and nowhere else:
 * never from a request body, path or query parameter.
 */
public record Caller(String userId, String tenantId, String employeeId, Set<Role> roles) {

    public static Caller from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("Request reached a controller without a JWT identity");
        }
        Jwt jwt = token.getToken();
        Set<Role> roles = token.getAuthorities().stream()
                .map(a -> a.getAuthority().substring("ROLE_".length()))
                .filter(Role::isKnown)
                .map(Role::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("tenant"),
                jwt.getClaimAsString("employeeId"), roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /** Admins and operators may read any employee in their tenant. */
    public boolean canReadAnyEmployee() {
        return hasRole(Role.TRANSPORT_ADMIN) || hasRole(Role.OPERATOR);
    }
}

