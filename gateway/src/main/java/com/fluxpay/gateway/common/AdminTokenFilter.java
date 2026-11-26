package com.fluxpay.gateway.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the operator endpoints under {@code /internal/*} with one shared secret sent in
 * {@code X-Admin-Token}. These endpoints act on any merchant's payment, so they must not be
 * reachable with merchant credentials: this filter looks at nothing but its own header, and a
 * perfectly signed merchant request without the token is refused like any other.
 *
 * <p>The comparison is {@link MessageDigest#isEqual}, which takes the same time wherever the
 * first differing byte is, so response timing does not reveal how much of a guess was right.
 * Every refusal is the same 401: the caller is not told whether the header was missing or wrong.
 *
 * <p>A blank configured token is refused at construction, which stops the application from
 * starting. Otherwise an unset variable would quietly mean "the empty token is valid".
 *
 * <p>This is a shared token, not operator accounts: it proves "someone who knows the token",
 * never who.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final byte[] expected;
    private final ProblemDetailWriter problems;

    public AdminTokenFilter(String token, ProblemDetailWriter problems) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "FLUXPAY_ADMIN_TOKEN is not set. The operator endpoints under /internal would be "
                            + "unprotected, so the gateway refuses to start. Set it in .env (see .env.example).");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Admin auth rejected on {} {}", request.getMethod(), request.getRequestURI());
            problems.write(response, problems.build(HttpStatus.UNAUTHORIZED, "ADMIN_AUTH_FAILED",
                    "Operator authentication failed."));
            return;
        }
        chain.doFilter(request, response);
    }
}
