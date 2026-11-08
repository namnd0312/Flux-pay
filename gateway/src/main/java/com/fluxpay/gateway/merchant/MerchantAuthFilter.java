package com.fluxpay.gateway.merchant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fluxpay.gateway.common.CachedBodyHttpServletRequest;
import com.fluxpay.gateway.common.CanonicalRequest;
import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.common.ProblemDetailWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates every {@code /api/**} request VNPAY/MoMo style: the {@code X-Merchant-Id}
 * api key names the merchant, and an {@code X-Signature} = HMAC-SHA256 of the
 * {@link CanonicalRequest} (method, path, timestamp, idempotency key, body hash) proves the
 * caller holds that merchant's secret. A bare api key identifies but never authenticates,
 * which is the failure this phase fixes.
 *
 * <p>Order of checks (fail closed): read and cap the body, require all three auth headers,
 * look the merchant up, require it active, bound the timestamp to a window, decrypt the
 * stored secret, then verify the signature in constant time. Every rejection is a 401 (413
 * for an oversize body) written as an RFC 7807 body by the shared {@link ProblemDetailWriter};
 * no path reaches a 500. A decrypt failure is a 401 {@code AUTH_BAD_SIGNATURE} logged at
 * ERROR (an operator problem), not leaked to the merchant. Distinct 401 codes are kept for
 * learning; note 04 explains why production collapses them.
 *
 * <p>Registered for {@code /api/*} only (see {@code MerchantAuthFilterConfig}); webhooks use
 * provider signatures from phase 4 and {@code /actuator/health} is public.
 */
public class MerchantAuthFilter extends OncePerRequestFilter {

    static final String HEADER_API_KEY = "X-Merchant-Id";
    static final String HEADER_TIMESTAMP = "X-Timestamp";
    static final String HEADER_SIGNATURE = "X-Signature";
    static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(MerchantAuthFilter.class);

    private final MerchantRepository merchants;
    private final SecretCipher cipher;
    private final HmacSigner signer;
    private final ProblemDetailWriter problems;
    private final Clock clock;
    private final long toleranceMillis;
    private final int maxBodyBytes;

    public MerchantAuthFilter(MerchantRepository merchants, SecretCipher cipher, HmacSigner signer,
            ProblemDetailWriter problems, Clock clock, Duration timestampTolerance, int maxBodyBytes) {
        this.merchants = merchants;
        this.cipher = cipher;
        this.signer = signer;
        this.problems = problems;
        this.clock = clock;
        this.toleranceMillis = timestampTolerance.toMillis();
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body;
        try {
            body = readCapped(request.getInputStream());
        } catch (BodyTooLargeException ex) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_BODY_TOO_LARGE",
                    "Request body exceeds " + maxBodyBytes + " bytes.");
            return;
        }
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);

        String failureCode = authenticate(wrapped, body);
        if (failureCode != null) {
            reject(response, HttpStatus.UNAUTHORIZED, failureCode, "Request authentication failed.");
            return;
        }
        chain.doFilter(wrapped, response);
    }

    /** Returns null on success (after setting {@link MerchantContext}); otherwise a 401 code. */
    private String authenticate(HttpServletRequest request, byte[] body) {
        String apiKey = request.getHeader(HEADER_API_KEY);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(apiKey) || isBlank(timestamp) || isBlank(signature)) {
            log.warn("Auth rejected: missing headers on {} {}", request.getMethod(), request.getRequestURI());
            return "AUTH_MISSING_HEADERS";
        }

        Merchant merchant = merchants.findByApiKey(apiKey).orElse(null);
        if (merchant == null) {
            log.warn("Auth rejected: unknown merchant api key");
            return "AUTH_UNKNOWN_MERCHANT";
        }
        if (!merchant.isActive()) {
            log.warn("Auth rejected: inactive merchant {}", apiKey);
            return "AUTH_MERCHANT_INACTIVE";
        }

        long sentMillis;
        try {
            sentMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            // The header is present but not a usable timestamp: treat it as a timestamp
            // failure, not a missing header.
            log.warn("Auth rejected: unparseable X-Timestamp for merchant {}", apiKey);
            return "AUTH_STALE_TIMESTAMP";
        }
        if (Math.abs(clock.millis() - sentMillis) > toleranceMillis) {
            log.warn("Auth rejected: stale timestamp for merchant {}", apiKey);
            return "AUTH_STALE_TIMESTAMP";
        }

        byte[] secret;
        try {
            secret = cipher.decrypt(apiKey, merchant.getSecret());
        } catch (SecretDecryptException ex) {
            // Operator-visible: a key-management fault, not a client error. Never a 500.
            log.error("Auth rejected: stored secret for merchant {} could not be decrypted", apiKey, ex);
            return "AUTH_BAD_SIGNATURE";
        }
        if (secret.length == 0) {
            // A misprovisioned merchant whose ciphertext decrypts to an empty secret. An
            // empty HMAC key would make the signer throw, so stop here with a 401, not a 500.
            log.error("Auth rejected: decrypted secret for merchant {} is empty (misprovisioned)", apiKey);
            return "AUTH_BAD_SIGNATURE";
        }

        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY);
        byte[] canonical = CanonicalRequest.build(
                request.getMethod(), request.getRequestURI(), timestamp, idempotencyKey, body);
        if (!signer.verify(secret, canonical, signature)) {
            log.warn("Auth rejected: bad signature for merchant {} on {} {}",
                    apiKey, request.getMethod(), request.getRequestURI());
            return "AUTH_BAD_SIGNATURE";
        }

        new MerchantContext(merchant.getId(), apiKey).storeIn(request);
        return null;
    }

    private byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        problems.write(response, problems.build(status, code, detail));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Internal signal that the body exceeded the cap before buffering completed. */
    private static final class BodyTooLargeException extends IOException {
    }
}
