package com.fluxpay.gateway.idempotency;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

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
            case NEW -> runAndStore(request, response, chain, ctx.merchantId(), key, outcome.leaseEpoch());
        }
    }

    private void runAndStore(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            UUID merchantId, String key, long leaseEpoch) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean settled = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (STORABLE_STATUSES.contains(status)) {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                // From here the key is not abandoned any more, even if storing fails: the
                // request has succeeded, and releasing the key would invite a second run. If
                // complete() throws, the key stays leased until the lease expires.
                settled = true;
                if (!idempotency.complete(merchantId, key, leaseEpoch, status, body)) {
                    leaseLost(merchantId, key, leaseEpoch);
                }
            }
        } finally {
            // A non-storable status (500/503) or a thrown exception leaves the key reclaimable.
            try {
                if (!settled && !idempotency.abandon(merchantId, key, leaseEpoch)) {
                    leaseLost(merchantId, key, leaseEpoch);
                }
            } catch (RuntimeException ex) {
                // Releasing the key failed (e.g. the database is down). Do not let that replace
                // the real outcome: the key simply stays leased until the lease expires.
                log.warn("Could not release idempotency key {} for merchant {}: {}", key, merchantId,
                        ex.getMessage());
            } finally {
                wrapper.copyBodyToResponse();
            }
        }
    }

    /**
     * This request ran past its lease and no longer owns the key (another request took it
     * over, or the row is gone). Its fenced write changed nothing, which is the point: the stored outcome belongs to the current
     * owner. This request's own client still gets the response computed for it.
     */
    private static void leaseLost(UUID merchantId, String key, long leaseEpoch) {
        log.warn("Idempotency lease lost: merchant={} key={} epoch={} finished after it stopped owning the key; "
                + "its outcome was not stored", merchantId, key, leaseEpoch);
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
