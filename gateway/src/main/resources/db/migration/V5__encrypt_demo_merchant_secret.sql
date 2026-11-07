-- Phase 5: replace the DEMO merchant's plaintext secret (seeded in V2) with ciphertext.
-- The value is the v1: AES-256-GCM envelope produced by EncryptSecretTool for
--   api key  = 'demo-merchant'   (bound as AAD)
--   plaintext= 'demo-secret-change-me'
--   key      = the committed dev key (FLUXPAY_SECRET_KEY in .env.example / application-test.yml)
-- Precomputed once and committed because Flyway runs plain SQL here (a Java migration
-- cannot inject the Spring-managed SecretCipher). Dev key only: see README for rotation.
UPDATE merchants
SET secret = 'v1:90lkIB1rChHKWHk3QW/PCNENV6gxxSnOX2gbLQaALD4l1X6i5Rq45Y+hhhjcZFOiiQ=='
WHERE api_key = 'demo-merchant';
