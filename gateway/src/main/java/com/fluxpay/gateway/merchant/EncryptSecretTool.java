package com.fluxpay.gateway.merchant;

/**
 * Command-line helper that prints the {@code v1:} ciphertext for a (key, apiKey, plaintext)
 * triple, so a human can paste it into a Flyway migration. Flyway runs plain SQL here (no
 * Java migration can inject Spring beans), so the ciphertext for a seeded merchant must be
 * precomputed once and committed in the migration.
 *
 * <p>Run it with the dev key on the classpath via the environment:
 * <pre>
 * FLUXPAY_SECRET_KEY=... ./mvnw -q -pl gateway exec:java \
 *   -Dexec.mainClass=com.fluxpay.gateway.merchant.EncryptSecretTool \
 *   -Dexec.args="demo-merchant demo-secret-change-me"
 * </pre>
 * It builds a {@link SecretCipher} from the same {@code FLUXPAY_SECRET_KEY} the app uses,
 * so the output decrypts under that key. Rotating the key means re-running this and adding
 * a new migration (see README).
 */
public final class EncryptSecretTool {

    private EncryptSecretTool() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: EncryptSecretTool <apiKey> <plaintextSecret>  (reads FLUXPAY_SECRET_KEY from env)");
            System.exit(2);
            return;
        }
        String key = System.getenv("FLUXPAY_SECRET_KEY");
        SecretCipher cipher = new SecretCipher(key);
        System.out.println(cipher.encrypt(args[0], args[1]));
    }
}
