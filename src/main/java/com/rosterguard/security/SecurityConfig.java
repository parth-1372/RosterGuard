package com.rosterguard.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT security. Authorisation is deny-by-default: any path without an explicit rule
 * is refused, so a forgotten endpoint is closed, not open.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a 256-bit key

    @Bean
    SecretKey jwtSigningKey(SecurityProperties props) {
        String secret = props.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Fail fast: never run with a missing or weak signing key.
            throw new IllegalStateException(
                    "rosterguard.security.jwt-secret (JWT_SECRET) must be set to at least 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, SecurityProperties props) {
        // Pinning HS256 means tokens with alg=none or any other algorithm are rejected.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(5)),
                new JwtIssuerValidator(props.issuer()),
                requiredClaims()));
        return decoder;
    }

    /** A token without subject, tenant or roles is not an identity we can authorise. */
    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            boolean ok = notBlank(jwt.getSubject())
                    && notBlank(jwt.getClaimAsString("tenant"))
                    && jwt.getClaimAsStringList("roles") != null
                    && !jwt.getClaimAsStringList("roles").isEmpty();
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    /** Unknown role names are ignored: they grant nothing. */
    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? List.of() : roles.stream()
                .filter(Role::isKnown)
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder,
                                    JwtAuthenticationConverter converter,
                                    SecurityErrorHandlers errors, Environment env) throws Exception {
        boolean dev = env.acceptsProfiles(Profiles.of("dev"));
        http
            // Stateless bearer tokens in a header, no cookies: CSRF does not apply.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(o -> o
                    .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(errors)
                    .accessDeniedHandler(errors))
            .authorizeHttpRequests(a -> {
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
                a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                if (dev) {
                    // Only exists in the dev profile. Elsewhere the path has no rule: denied.
                    a.requestMatchers(HttpMethod.POST, "/api/dev/token").permitAll();
                }
                a.requestMatchers(HttpMethod.POST, "/api/v1/employees/events")
                        .hasRole(Role.HR_INTEGRATION.name());
                a.requestMatchers(HttpMethod.PUT, "/api/v1/employees/*/transport")
                        .hasRole(Role.TRANSPORT_ADMIN.name());
                a.requestMatchers(HttpMethod.GET, "/api/v1/employees")
                        .hasAnyRole(Role.TRANSPORT_ADMIN.name(), Role.OPERATOR.name());
                a.requestMatchers(HttpMethod.GET, "/api/v1/employees/*")
                        .hasAnyRole(Role.TRANSPORT_ADMIN.name(), Role.OPERATOR.name(),
                                Role.EMPLOYEE.name());
                a.requestMatchers("/actuator/**").hasRole(Role.OPERATOR.name());
                a.anyRequest().denyAll();
            });
        return http.build();
    }
}

