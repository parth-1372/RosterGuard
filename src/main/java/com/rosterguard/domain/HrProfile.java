package com.rosterguard.domain;

import java.util.Locale;

/**
 * The HR-owned fields of an employee (name, email, phone, manager, office, shift).
 * Values are canonicalised on construction so that what we store and what we hash are identical.
 * Transport-owned fields deliberately do not exist here: an HR event cannot carry them.
 */
public record HrProfile(String name, String email, String phone, String managerId,
                        String officeCode, String shiftCode) {

    public static final HrProfile EMPTY = new HrProfile(null, null, null, null, null, null);

    public HrProfile {
        name = clean(name);
        email = clean(email);
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
        }
        phone = cleanPhone(phone);
        managerId = clean(managerId);
        officeCode = clean(officeCode);
        shiftCode = clean(shiftCode);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String cleanPhone(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.replace(" ", "").replace("-", "");
    }
}
