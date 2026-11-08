package com.fluxpay.gateway.idempotency;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.fluxpay.gateway.common.CanonicalRequest;
import com.fluxpay.gateway.common.ProblemDetailWriter;
import com.fluxpay.gateway.merchant.MerchantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Makes {@code POST /api/v1/payments} safe to retry. Registered after the auth filter and
 * only on the create path, so it always sees an authenticated {@link MerchantContext}.
 *
 * <p>Why a filter and not controller code: the responses worth replaying include ones the
 * controller never returns itself — a 502 from a provider failure or a 409 for a duplicate
 * order are rendered by the controller advice <em>after</em> the controller has thrown, so
 * only a response wrapper sees the final status and body. The filter wraps the response,
 * lets the request run once, and stores the outcome for replay.
 *
 * <p>Storable (replayable) statuses are the deterministic ones: a retry would get the same
 * answer. Transient failures (500, 503) are not stored — the key is dropped so the merchant
 * can retry with it.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER_KEY = "Idempotency-Key";
    static final String HEADER_REPLAYED = "Idempotency-Replayed";
    static final int MAX_KEY_LENGTH = 64;

    /** Deterministic outcomes a retry should get identically. */
    private static final Set<Integer> STORABLE_STATUSES = Set.of(201, 400, 404, 409, 422, 502);

    private final IdempotencyService idempotency;
    private final ProblemDetailWriter problems;

    public IdempotencyFilter(IdempotencyService idempotency, ProblemDetailWriter problems) {
        this.idempotency = idempotency;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        MerchantContext ctx = MerchantContext.from(request);
        if (ctx == null) {
            // Defensive: auth runs first and would have rejected an unauthenticated request.
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(HEADER_KEY);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            reject(response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
                    "A non-empty Idempotency-Key header (max 64 chars) is required to create a payment.");
            return;
        }

        String requestHash = CanonicalRequest.sha256Hex(readBody(request));
        IdempotencyOutcome outcome = idempotency.begin(ctx.merchantId(), key, requestHash);
        switch (outcome.kind()) {
            case IN_PROGRESS -> reject(response, HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this Idempotency-Key is already being processed.");
            case MISMATCH -> reject(response, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request body.");
            case REPLAY -> replay(response, outcome.responseStatus(), outcome.responseBody());
            case NEW -> runAndStore(request, response, chain, ctx.merchantId(), key);
        }
    }

    private void runAndStore(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            UUID merchantId, String key) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (STORABLE_STATUSES.contains(status)) {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                idempotency.complete(merchantId, key, status, body);
                stored = true;
            }
        } finally {
            // A non-storable status (500/503) or a thrown exception leaves the key reclaimable.
            if (!stored) {
                idempotency.abandon(merchantId, key);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(status >= 400
                ? MediaType.APPLICATION_PROBLEM_JSON_VALUE : MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HEADER_REPLAYED, "true");
        if (body != null) {
            response.getWriter().write(body);
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        problems.write(response, problems.build(status, code, detail));
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        // The auth filter already buffered the body; this re-reads from that buffer.
        try (InputStream in = request.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
