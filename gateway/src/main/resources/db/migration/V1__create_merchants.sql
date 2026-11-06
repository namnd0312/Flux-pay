-- Merchants are the gateway's API clients. The api_key is the public identifier a
-- merchant sends in the X-Merchant-Id header (from phase 2 onward); secret is the
-- shared key used for HMAC request signing (phase 5). In phase 5 the plaintext
-- secret seeded in V2 is replaced by ciphertext, but the column stays `secret`.
CREATE TABLE merchants (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    api_key    VARCHAR(64)  NOT NULL UNIQUE,
    secret     TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
