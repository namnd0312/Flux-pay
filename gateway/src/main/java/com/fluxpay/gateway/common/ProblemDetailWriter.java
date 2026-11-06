package com.fluxpay.gateway.common;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Builds an RFC 7807 problem detail and writes it straight to a raw
 * {@link HttpServletResponse}.
 *
 * <p>Two callers need this: the {@link ProblemDetailsHandler} controller advice
 * (which also could return a ProblemDetail object), and the servlet filters
 * added in phases 5-6 (HMAC auth, idempotency). A filter runs before Spring MVC
 * renders anything, so it cannot return a ProblemDetail object the way a
 * controller does; it must serialize the error to the response itself. Sharing
 * one writer keeps every error body identical, including the stable
 * {@code code} field clients branch on.
 */
@Component
public class ProblemDetailWriter {

    private final ObjectMapper objectMapper;

    public ProblemDetailWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Build a problem detail carrying a machine-readable {@code code} alongside the human title. */
    public ProblemDetail build(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    /** Serialize a problem detail to the response (used by filters that run outside Spring MVC). */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
