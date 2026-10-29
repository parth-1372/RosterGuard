package com.rosterguard.domain;

/** A validation failure. Carries the field name and a code only, never the submitted value. */
public record FieldError(String field, String code) {
}
