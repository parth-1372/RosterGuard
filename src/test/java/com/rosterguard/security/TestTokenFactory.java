package com.rosterguard.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints JWT tokens for test use only.
 *
 * <p>Uses the Nimbus library (already a transitive dependency via
 * spring-boot-starter-oauth2-resource-server) directly, so no Spring context is needed.
 * The secret must match the value in src/test/resources/application.yml.
 */
public final class TestTokenFactory {

    /** Must be >= 32 bytes and identical to the value in test application.yml. */
    public static final String TEST_SECRET = "test-secret-used-only-in-automated-tests-32plus";
    public static final String ISSUER = "rosterguard";

    private TestTokenFactory() {}

    /** Mint a standard valid token for the given tenant and roles. */
    public static String validToken(String userId, String tenant, String... roles) {
        return mint(userId, tenant, null, List.of(roles),
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    /** Valid token with an employeeId claim (for the EMPLOYEE role). */
    public static String employeeToken(String userId, String tenant, String employeeId) {
        return mint(userId, tenant, employeeId, List.of(Role.EMPLOYEE.name()),
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    /** Token that has already expired. */
    public static String expiredToken(String tenant) {
        return mint("user", tenant, null, List.of(Role.OPERATOR.name()),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
    }

    /** Token signed with a different key (wrong signature). */
    public static String wrongKeyToken(String tenant) {
        try {
            byte[] wrongKey = ("wrong-key-completely-different-secret-value-here")
                    .getBytes(StandardCharsets.UTF_8);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject("bad-user")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                    .claim("tenant", tenant)
                    .claim("roles", List.of(Role.OPERATOR.name()))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(wrongKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint wrong-key token", e);
        }
    }

    /** Token missing the required 'tenant' claim. */
    public static String missingTenantToken() {
        try {
            byte[] key = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject("user")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                    .claim("roles", List.of(Role.OPERATOR.name()))
                    // no "tenant" claim
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint missing-tenant token", e);
        }
    }

    /** Token with an unrecognised role that maps to no authority. */
    public static String unknownRoleToken(String tenant) {
        return mint("user", tenant, null, List.of("SUPER_ADMIN"),
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    private static String mint(String userId, String tenant, String employeeId,
                               List<String> roles, Instant iat, Instant exp) {
        try {
            byte[] key = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject(userId)
                    .issueTime(Date.from(iat))
                    .expirationTime(Date.from(exp))
                    .claim("tenant", tenant)
                    .claim("roles", roles);
            if (employeeId != null) {
                builder.claim("employeeId", employeeId);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint test token", e);
        }
    }
}
