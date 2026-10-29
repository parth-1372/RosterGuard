package com.rosterguard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes ApiError JSON for failures that happen inside the security filter chain. */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper mapper;

    public ErrorResponseWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, MDC.get(TraceIdFilter.MDC_KEY)));
    }
}

