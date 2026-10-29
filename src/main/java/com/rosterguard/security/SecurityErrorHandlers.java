package com.rosterguard.security;

import com.rosterguard.api.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 and 403 responses in the standard error shape. The default bearer entry point puts the
 * validation failure reason (e.g. "Jwt expired at ...") into a header; we deliberately do not.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ErrorResponseWriter writer;

    public SecurityErrorHandlers(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        writer.write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this operation");
    }
}

