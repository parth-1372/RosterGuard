package com.rosterguard.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rosterguard.domain.FieldError;
import com.rosterguard.service.InvalidRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY. Mints unauthenticated demo tokens so the API can be exercised without an
 * identity provider. This bean exists only under the "dev" profile; in any other profile the
 * route has no handler and no security rule, so anonymous callers get 401.
 */
@RestController
@RequestMapping("/api/dev/token")
@Profile("dev")
public class DevTokenController {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    static final Duration MAX_TTL = Duration.ofHours(1);

    public record TokenRequest(String userId, String tenant, List<String> roles,
                               String employeeId, Long ttlSeconds) {
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    public DevTokenController(SecretKey key, SecurityProperties props) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.props = props;
    }

    @PostMapping
    public TokenResponse issue(@RequestBody TokenRequest request) {
        validate(request);
        Duration ttl = request.ttlSeconds() == null ? DEFAULT_TTL
                : Duration.ofSeconds(Math.max(1, request.ttlSeconds()));
        if (ttl.compareTo(MAX_TTL) > 0) {
            ttl = MAX_TTL;
        }
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer()).subject(request.userId())
                .issuedAt(now).expiresAt(now.plus(ttl))
                .claim("tenant", request.tenant())
                .claim("roles", request.roles());
        if (request.employeeId() != null) {
            claims.claim("employeeId", request.employeeId());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new TokenResponse(token, now.plus(ttl));
    }

    private static void validate(TokenRequest r) {
        List<FieldError> errors = new ArrayList<>();
        if (r.userId() == null || r.userId().isBlank()) {
            errors.add(new FieldError("userId", "REQUIRED"));
        }
        if (r.tenant() == null || r.tenant().isBlank()) {
            errors.add(new FieldError("tenant", "REQUIRED"));
        }
        if (r.roles() == null || r.roles().isEmpty()) {
            errors.add(new FieldError("roles", "REQUIRED"));
        } else if (!r.roles().stream().allMatch(Role::isKnown)) {
            errors.add(new FieldError("roles", "INVALID_ROLE"));
        } else if (r.roles().contains(Role.EMPLOYEE.name())
                && (r.employeeId() == null || r.employeeId().isBlank())) {
            errors.add(new FieldError("employeeId", "REQUIRED"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
    }
}

