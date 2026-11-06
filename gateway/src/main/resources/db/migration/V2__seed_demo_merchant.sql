-- One demo merchant so the Postman happy path works out of the box.
-- api_key "demo-merchant" is the X-Merchant-Id value; the secret is plaintext in
-- increment 1 and becomes ciphertext in phase 5 (dev key, documented, test-only).
INSERT INTO merchants (id, name, api_key, secret, active)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'DEMO',
    'demo-merchant',
    'demo-secret-change-me',
    TRUE
);
