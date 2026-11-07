package com.fluxpay.gateway;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fluxpay.gateway.common.CanonicalRequest;
import com.fluxpay.gateway.common.HmacSigner;

/**
 * Base for gateway integration tests: the full application boots against a real
 * PostgreSQL 16 started by Testcontainers and is driven through MockMvc.
 *
 * <p>{@code @ServiceConnection} hands the container's JDBC url, user, and
 * password to Spring Boot automatically, so no datasource config is needed in
 * the test profile. Flyway then applies the real migrations, which is what makes
 * {@code ddl-auto=validate} meaningful in tests. Subclasses add behaviour;
 * Docker must be running or the container fails fast with a clear message.
 *
 * <p>From phase 5 every {@code /api/**} request must carry a valid HMAC signature, so
 * tests build requests with {@link #hmac}: a {@link RequestPostProcessor} that reads the
 * fully built method, path and body and attaches {@code X-Merchant-Id}, {@code X-Timestamp},
 * {@code X-Signature} (and an optional {@code Idempotency-Key}) exactly as a merchant
 * would. The timestamp is passed in so a test can drive the clock and still sign a fresh
 * request. The signing secret is the merchant's plaintext; the gateway decrypts the stored
 * ciphertext to the same bytes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MutableClock.Config.class, CountingFakeProvider.Config.class})
public abstract class AbstractIntegrationTest {

    /** Plaintext secret of the DEMO merchant (ciphertext seeded by V5 decrypts to this). */
    protected static final String DEMO_SECRET = "demo-secret-change-me";

    private static final HmacSigner SIGNER = new HmacSigner();

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    /**
     * Signs the request the way a merchant client does. Applied via
     * {@code mvc.perform(post(...).content(body).with(hmac(apiKey, secret, ts, key)))}.
     */
    protected static RequestPostProcessor hmac(String apiKey, String secret, long timestampMillis, String idempotencyKey) {
        return request -> {
            String ts = Long.toString(timestampMillis);
            request.addHeader("X-Merchant-Id", apiKey);
            request.addHeader("X-Timestamp", ts);
            if (idempotencyKey != null) {
                request.addHeader("Idempotency-Key", idempotencyKey);
            }
            byte[] body = request.getContentAsByteArray();
            byte[] canonical = CanonicalRequest.build(request.getMethod(), request.getRequestURI(), ts,
                    idempotencyKey, body == null ? new byte[0] : body);
            request.addHeader("X-Signature", SIGNER.hex(secret.getBytes(StandardCharsets.UTF_8), canonical));
            return request;
        };
    }
}
