#!/usr/bin/env bash
# Replays ONE pre-signed create request N times in parallel to demonstrate idempotency.
#
# Before (phase 5 build, no idempotency key enforcement off): 50 identical requests ->
#   50 payment rows, 50 provider sessions = the double-charge bug.
# After (phase 6): the same burst -> 1 payment row, 1 provider call; the rest are replays
#   (201 + Idempotency-Replayed: true) or 409 IDEMPOTENCY_IN_PROGRESS.
#
# This does NO signing in the shell. Sign one request in Postman (Phase 5/6 folder, the
# collection pre-request script signs it), open the Postman Console, copy the exact
# X-Merchant-Id / X-Timestamp / X-Signature / Idempotency-Key headers and the request body
# into the variables below. The signature is valid for 5 minutes (timestamp window), so run
# the burst promptly; if you see 50x 401, re-sign in Postman and copy fresh headers.
#
# Usage: edit the headers/body below, then:  ./scripts/burst-replay.sh
set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
N="${N:-50}"
PARALLEL="${PARALLEL:-20}"

# --- paste from Postman Console (one signed request) ---
MERCHANT_ID="demo-merchant"
TIMESTAMP="PASTE_X_TIMESTAMP"
SIGNATURE="PASTE_X_SIGNATURE"
IDEMPOTENCY_KEY="PASTE_IDEMPOTENCY_KEY"
BODY='{"orderId":"ORD-BURST-1","amount":1000000}'
# -------------------------------------------------------

if [[ "$TIMESTAMP" == PASTE_* || "$SIGNATURE" == PASTE_* ]]; then
  echo "Edit the signed headers at the top of this script first (see the comment)." >&2
  exit 2
fi

echo "Firing $N requests ($PARALLEL in parallel) at $GATEWAY/api/v1/payments ..."
seq "$N" | xargs -P "$PARALLEL" -I{} \
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST "$GATEWAY/api/v1/payments" \
    -H "Content-Type: application/json" \
    -H "X-Merchant-Id: $MERCHANT_ID" \
    -H "X-Timestamp: $TIMESTAMP" \
    -H "X-Signature: $SIGNATURE" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    --data "$BODY" \
  | sort | uniq -c

echo
echo "Now count payment rows for the order (expect 1 after phase 6):"
echo "  psql \"\$DB_URL\" -c \"select count(*) from payments where order_id='ORD-BURST-1'\""
