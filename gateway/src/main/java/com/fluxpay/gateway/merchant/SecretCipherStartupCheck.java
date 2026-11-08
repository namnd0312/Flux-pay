package com.fluxpay.gateway.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails startup if the configured {@code FLUXPAY_SECRET_KEY} cannot decrypt the seeded
 * DEMO merchant's secret. Without this check a wrong or rotated key would boot cleanly and
 * then turn every authenticated request into {@code AUTH_BAD_SIGNATURE}, which looks like a
 * client bug. Decrypting one known row at startup turns a silent misconfiguration into a
 * loud, immediate boot failure with a clear message.
 *
 * <p>The {@link SecretCipher} constructor already rejects a missing or wrong-length key, so
 * this covers the remaining case: a well-formed key that is simply the wrong key for the
 * data in the database.
 */
@Component
public class SecretCipherStartupCheck implements ApplicationRunner {

    /** Seeded by V2; its secret is re-encrypted by V5 with the dev key. */
    static final String DEMO_API_KEY = "demo-merchant";

    private static final Logger log = LoggerFactory.getLogger(SecretCipherStartupCheck.class);

    private final MerchantRepository merchants;
    private final SecretCipher cipher;

    public SecretCipherStartupCheck(MerchantRepository merchants, SecretCipher cipher) {
        this.merchants = merchants;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        Merchant demo = merchants.findByApiKey(DEMO_API_KEY).orElse(null);
        if (demo == null) {
            log.warn("DEMO merchant '{}' not found; skipping secret self-check", DEMO_API_KEY);
            return;
        }
        try {
            cipher.decrypt(demo.getApiKey(), demo.getSecret());
            log.info("Secret key self-check passed: DEMO merchant secret decrypts");
        } catch (SecretDecryptException ex) {
            throw new IllegalStateException(
                    "FLUXPAY_SECRET_KEY cannot decrypt the DEMO merchant secret. "
                            + "The key does not match the data in the database (wrong key, or secrets "
                            + "were encrypted with a different key). Re-encrypt with EncryptSecretTool "
                            + "and apply a new migration, or set the correct key.",
                    ex);
        }
    }
}
