package com.rosterguard.security;

/** The four roles. Stored in the JWT "roles" claim; mapped to ROLE_ authorities. */
public enum Role {
    HR_INTEGRATION,
    TRANSPORT_ADMIN,
    OPERATOR,
    EMPLOYEE;

    public static boolean isKnown(String name) {
        for (Role role : values()) {
            if (role.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

