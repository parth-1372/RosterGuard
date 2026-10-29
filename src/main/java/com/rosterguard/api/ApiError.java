package com.rosterguard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rosterguard.domain.FieldError;
import java.util.List;

/**
 * The one error shape for every failure. Messages are fixed strings: they never echo input,
 * exception text, SQL or personal data. The traceId links a response to server-side logs.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, String traceId, List<FieldError> errors) {

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, List.of());
    }
}

