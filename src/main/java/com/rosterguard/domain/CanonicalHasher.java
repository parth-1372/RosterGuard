package com.rosterguard.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic SHA-256 canonical hasher. Hex output is 64 lowercase chars.
 *
 * <p>Every field is hashed as {@code [4-byte big-endian length][UTF-8 bytes]}.
 * Null is represented by length -1. Blank is treated as null. This length-prefixing
 * prevents collision attacks across adjacent field boundaries (e.g. ("ab","c") vs ("a","bc")).
 *
 * <p>Email is case-insensitive; phone is normalised (spaces and hyphens stripped).
 */
public final class CanonicalHasher {

    private CanonicalHasher() {}

    /** Hash of HR profile alone (tag ROSTER_V1). Used for changed-detection during CSV reconciliation. */
    public static String rosterHash(HrProfile profile) {
        HrProfile p = profile == null ? HrProfile.EMPTY : profile;
        return hash("ROSTER_V1", p.name(), p.email(), p.phone(), p.managerId(),
                p.officeCode(), p.shiftCode());
    }

    /** Hash of event type + HR profile (tag EVENT_V1). Used to tell duplicate from conflict at one version. */
    public static String payloadHash(HrEventType type, HrProfile profile) {
        HrProfile p = type == HrEventType.DEACTIVATE ? HrProfile.EMPTY : (profile == null ? HrProfile.EMPTY : profile);
        return hash("EVENT_V1", type.name(), p.name(), p.email(), p.phone(), p.managerId(),
                p.officeCode(), p.shiftCode());
    }

    /** Hash of CSV row profile (tag EVENT_V1 with UPDATE). Used for stored payload_hash from CSV import. */
    public static String csvPayloadHash(HrProfile profile) {
        return payloadHash(HrEventType.UPDATE, profile);
    }

    private static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                appendField(sha, part);
            }
            return toHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void appendField(MessageDigest sha, String value) {
        String clean = clean(value);
        if (clean == null) {
            sha.update(new byte[] {-1, -1, -1, -1});
            return;
        }
        byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        sha.update(new byte[] {
                (byte) (len >>> 24),
                (byte) (len >>> 16),
                (byte) (len >>> 8),
                (byte) len
        });
        sha.update(bytes);
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
