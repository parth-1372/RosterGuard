package com.rosterguard.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Boundary validation for HR events. Reports field + code only, never the offending value. */
public final class HrEventValidator {

    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_FORMAT = "INVALID_FORMAT";
    public static final String INVALID_PHONE = "INVALID_PHONE";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String UNKNOWN_OFFICE = "UNKNOWN_OFFICE";
    /** Missing or unrecognised event type. */
    public static final String INVALID_TYPE = "INVALID_TYPE";

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private HrEventValidator() {
    }

    public static List<FieldError> validate(HrEvent event, Set<String> knownOfficeCodes) {
        List<FieldError> errors = new ArrayList<>();
        if (event.type() == null) {
            errors.add(new FieldError("type", INVALID_TYPE));
        }
        if (event.employeeId() == null || event.employeeId().isEmpty()) {
            errors.add(new FieldError("employeeId", REQUIRED));
        } else if (!ID.matcher(event.employeeId()).matches()) {
            errors.add(new FieldError("employeeId", INVALID_FORMAT));
        }
        if (event.sourceVersion() == null) {
            errors.add(new FieldError("sourceVersion", REQUIRED));
        } else if (event.sourceVersion() < 0) {
            errors.add(new FieldError("sourceVersion", INVALID_FORMAT));
        }
        if (event.type() == HrEventType.CREATE || event.type() == HrEventType.UPDATE) {
            validateProfile(event.profile(), knownOfficeCodes, errors);
        }
        return errors;
    }

    private static void validateProfile(HrProfile p, Set<String> offices, List<FieldError> errors) {
        required("name", p.name(), 200, errors);
        if (required("email", p.email(), 254, errors) && !EMAIL.matcher(p.email()).matches()) {
            errors.add(new FieldError("email", INVALID_FORMAT));
        }
        if (p.phone() == null) {
            errors.add(new FieldError("phone", REQUIRED));
        } else if (!PHONE.matcher(p.phone()).matches()) {
            errors.add(new FieldError("phone", INVALID_PHONE));
        }
        if (p.officeCode() == null) {
            errors.add(new FieldError("officeCode", REQUIRED));
        } else if (!offices.contains(p.officeCode())) {
            errors.add(new FieldError("officeCode", UNKNOWN_OFFICE));
        }
        if (p.managerId() != null && !ID.matcher(p.managerId()).matches()) {
            errors.add(new FieldError("managerId", INVALID_FORMAT));
        }
        if (p.shiftCode() != null && !CODE.matcher(p.shiftCode()).matches()) {
            errors.add(new FieldError("shiftCode", INVALID_FORMAT));
        }
    }

    /** @return true if the value is present and within the limit. */
    private static boolean required(String field, String value, int max, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(field, REQUIRED));
            return false;
        }
        if (value.length() > max) {
            errors.add(new FieldError(field, TOO_LONG));
            return false;
        }
        return true;
    }
}

