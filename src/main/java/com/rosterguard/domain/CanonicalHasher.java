package com.rosterguard.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic SHA-256 hashing of canonical records (docs/DESIGN_DECISIONS.md D9).
 * Each field is written as a 4-byte length (-1 for null) followed by UTF-8 bytes, so that
 * ("ab","c") and ("a","bc") cannot collide. A version tag is hashed first.
 */
public final class CanonicalHasher {

    private CanonicalHasher() {
    }

    /** Hash of the HR profile only. Used by nightly reconciliation to detect "changed". */
    public static String rosterHash(HrProfile p) {
        return hash("ROSTER_V1", p.name(), p.email(), p.phone(), p.managerId(),
                p.officeCode(), p.shiftCode());
    }

    /** Hash of event type + HR profile. Used only to tell duplicate from conflict at one version. */
    public static String payloadHash(HrEventType type, HrProfile profile) {
        HrProfile p = type == HrEventType.DEACTIVATE ? HrProfile.EMPTY : profile;
        return hash("EVENT_V1", type.name(), p.name(), p.email(), p.phone(), p.managerId(),
                p.officeCode(), p.shiftCode());
    }

    private static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) {
                    sha.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                    sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    sha.update(bytes);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }
}
