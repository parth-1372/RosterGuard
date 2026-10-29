package com.rosterguard.service;

import com.rosterguard.domain.FieldError;
import java.util.List;

public class InvalidRequestException extends RuntimeException {

    private final transient List<FieldError> errors;

    public InvalidRequestException(List<FieldError> errors) {
        super("Invalid request");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
