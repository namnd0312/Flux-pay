package com.fluxpay.gateway.merchant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.fluxpay.gateway.AbstractIntegrationTest;
import com.fluxpay.gateway.CountingFakeProvider;
import com.fluxpay.gateway.MutableClock;
import com.fluxpay.gateway.common.CanonicalRequest;
import com.fluxpay.gateway.common.HmacSigner;

/**
 * Exercises {@link MerchantAuthFilter} through the booted app and MockMvc: a correctly
 * signed request passes and the controller still reads the body; every rejection path
 * returns its distinct 401 code (or 413 for an oversize body) and never a 500; and the
 * cross-endpoint replay a bare body signature would miss (a GET signature reused on cancel)
 * is rejected because method and path are inside the signed canonical string.
 */
class MerchantAuthFilterIT extends AbstractIntegrationTest {

    private static final String DEMO = "demo-merchant";
    private static final HmacSigner SIGNER = new HmacSigner();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SecretCipher cipher;

    @Autowired
    MutableClock clock;

    @Autowired
    CountingFakeProvider fake;

    @AfterEach
    void reset() {
        clock.reset();
        fake.reset();
    }

    private String createBody() {
        return "{\"orderId\":\"ORD-" + UUID.randomUUID() + "\",\"amount\":1000000}";
    }

    @Test
    void validSignaturePassesAndControllerReadsBody() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac(DEMO, DEMO_SECRET, clock.millis(), "idem-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void missingSignatureHeaderIsMissingHeaders() throws Exception {
        // Only the api key present: the filter needs all three auth headers.
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .header("X-Merchant-Id", DEMO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING_HEADERS"));
    }

    @Test
    void unknownMerchantIsRejected() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac("nobody", "whatever", clock.millis(), null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNKNOWN_MERCHANT"));
    }

    @Test
    void inactiveMerchantIsRejected() throws Exception {
        seedMerchant("inactive-merchant", cipher.encrypt("inactive-merchant", "s"), false);
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac("inactive-merchant", "s", clock.millis(), null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MERCHANT_INACTIVE"));
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        long tenMinutesAgo = clock.millis() - (10 * 60 * 1000L); // tolerance is 5m
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac(DEMO, DEMO_SECRET, tenMinutesAgo, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_STALE_TIMESTAMP"));
    }

    @Test
    void wrongSecretIsBadSignature() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac(DEMO, "wrong-secret", clock.millis(), null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_BAD_SIGNATURE"));
    }

    @Test
    void undecryptableStoredSecretIsBadSignatureNot500() throws Exception {
        // A v1: envelope whose bytes are random: the GCM tag check fails on decrypt.
        String bogus = "v1:" + Base64.getEncoder().encodeToString(new byte[40]);
        seedMerchant("broken-merchant", bogus, true);
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .with(hmac("broken-merchant", "any", clock.millis(), null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_BAD_SIGNATURE"));
    }

    @Test
    void emptyDecryptedSecretIsBadSignatureNot500() throws Exception {
        // Misprovisioned merchant: ciphertext decrypts to an empty secret. An empty HMAC
        // key would make the signer throw a 500; the filter must stop at a 401 instead.
        seedMerchant("empty-secret-merchant", cipher.encrypt("empty-secret-merchant", ""), true);
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(createBody())
                        .header("X-Merchant-Id", "empty-secret-merchant")
                        .header("X-Timestamp", Long.toString(clock.millis()))
                        .header("X-Signature", "00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_BAD_SIGNATURE"));
    }

    @Test
    void getSignatureReplayedOnCancelIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        String ts = Long.toString(clock.millis());
        // Sign a GET on the payment, then reuse those exact headers on POST .../cancel.
        byte[] getCanonical = CanonicalRequest.build("GET", "/api/v1/payments/" + id, ts, null, new byte[0]);
        String getSignature = SIGNER.hex(DEMO_SECRET.getBytes(StandardCharsets.UTF_8), getCanonical);
        mvc.perform(post("/api/v1/payments/" + id + "/cancel")
                        .header("X-Merchant-Id", DEMO)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", getSignature))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_BAD_SIGNATURE"));
    }

    @Test
    void oversizeBodyIsRejectedBeforeAuth() throws Exception {
        byte[] huge = new byte[70 * 1024]; // > 64 KB cap
        java.util.Arrays.fill(huge, (byte) 'a');
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"));
    }

    private void seedMerchant(String apiKey, String storedSecret, boolean active) {
        Integer n = jdbc.sql("select count(*) from merchants where api_key = :k").param("k", apiKey)
                .query(Integer.class).single();
        if (n == 0) {
            jdbc.sql("insert into merchants (id, name, api_key, secret, active) "
                            + "values (:id, :name, :k, :s, :active)")
                    .param("id", UUID.randomUUID())
                    .param("name", apiKey)
                    .param("k", apiKey)
                    .param("s", storedSecret)
                    .param("active", active)
                    .update();
        }
    }
}
