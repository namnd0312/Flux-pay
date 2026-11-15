#!/usr/bin/env bash
# Runs one JMeter scenario headless against the local compose stack, then checks the
# database invariants.
#
#   load-tests/run.sh <scenario> [-Jname=value ...]
#
# scenario = baseline-create-pay-poll | same-key-burst | webhook-vs-cancel
#
# Environment:
#   LABEL            suffix for the results directory (e.g. LABEL=R1-race)
#   METRICS=1        also sample actuator metrics (needs docker-compose.loadtest.yml)
#   QUIESCE_SECONDS  wait between the end of the run and the invariant check (default 20:
#                    longer than the mock provider's 15.5 s webhook retry window)
#   COMPOSE_PROJECT  compose project name if the stack was started with -p
#
# The invariants are checked over the WHOLE database, so reset it between runs
# (docker compose down -v && up) or a previous run's rows count against this one.
#
# Exit code 1 unless ALL of these hold: every JMeter sample passed its assertions, the run
# created at least one payment, every HARD invariant is 0 (and all of them were checked),
# and with METRICS=1 the mock provider dropped no webhook.
set -euo pipefail

SCENARIO="${1:?usage: run.sh <scenario> [-Jname=value ...]}"
shift
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
[ -f "$DIR/$SCENARIO.jmx" ] || { echo "unknown scenario: $SCENARIO" >&2; exit 2; }

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/results/$STAMP-$SCENARIO${LABEL:+-$LABEL}"
mkdir -p "$OUT"

EXTRA=()
if [ "$SCENARIO" = "same-key-burst" ]; then
    EXTRA+=(-Jburst=true "-JrunId=$STAMP")
fi

COMPOSE=(docker compose)
[ -n "${COMPOSE_PROJECT:-}" ] && COMPOSE+=(-p "$COMPOSE_PROJECT")
psql_db() {
    (cd "$ROOT" && "${COMPOSE[@]}" exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -')
}

PAYMENTS_BEFORE="$(echo 'select count(*) from payments;' | psql_db)"
[ "$PAYMENTS_BEFORE" = "0" ] || echo "WARNING: database already holds $PAYMENTS_BEFORE payments;" \
    "invariants are whole-database, reset it for a clean run" >&2

METRICS_PID=""
if [ -n "${METRICS:-}" ]; then
    "$DIR/collect-metrics.sh" "$OUT/metrics.csv" &
    METRICS_PID=$!
    sleep 2
    kill -0 "$METRICS_PID" 2>/dev/null || { echo "metrics collector exited; see above" >&2; exit 2; }
fi
stop_metrics() { [ -n "$METRICS_PID" ] && kill "$METRICS_PID" 2>/dev/null || true; }
trap stop_metrics EXIT

jmeter -n -t "$DIR/$SCENARIO.jmx" -l "$OUT/results.jtl" -e -o "$OUT/report" -j "$OUT/jmeter.log" \
    "-JscriptDir=$DIR" ${EXTRA[@]+"${EXTRA[@]}"} "$@"

# JMeter exits 0 even when every sample failed, so read the verdict from the results file.
python3 -I - "$OUT/results.jtl" > "$OUT/samples.txt" <<'PY'
import collections, csv, sys
codes, failed, total = collections.Counter(), 0, 0
for r in csv.DictReader(open(sys.argv[1])):
    total += 1
    codes[(r["label"], r["responseCode"])] += 1
    failed += r["success"] != "true"
print(f"samples|total|{total}")
print(f"samples|failed|{failed}")
for (label, code), n in sorted(codes.items()):
    print(f"code|{label} {code}|{n}")
PY

echo "Waiting ${QUIESCE_SECONDS:-20}s for in-flight webhooks to settle..."
sleep "${QUIESCE_SECONDS:-20}"
stop_metrics

psql_db < "$DIR/verify-invariants.sql" > "$OUT/invariants.txt"
PAYMENTS_AFTER="$(echo 'select count(*) from payments;' | psql_db)"

echo "--- samples ($OUT/samples.txt)"
cat "$OUT/samples.txt"
echo "--- invariants ($OUT/invariants.txt)"
cat "$OUT/invariants.txt"
echo "--- report: $OUT/report/index.html"

FAILURES=()
FAILED_SAMPLES="$(awk -F'|' '$1 == "samples" && $2 == "failed" { print $3 }' "$OUT/samples.txt")"
[ "$FAILED_SAMPLES" = "0" ] || FAILURES+=("$FAILED_SAMPLES JMeter samples failed their assertions")
[ "$PAYMENTS_AFTER" -gt "$PAYMENTS_BEFORE" ] || FAILURES+=("the run created no payment (gateway down, clock skew, wrong port?)")
EXPECTED_HARD="$(grep -c "^select 'HARD'" "$DIR/verify-invariants.sql")"
CHECKED_HARD="$(awk -F'|' '$1 == "HARD"' "$OUT/invariants.txt" | wc -l | tr -d ' ')"
[ "$CHECKED_HARD" = "$EXPECTED_HARD" ] || FAILURES+=("only $CHECKED_HARD of $EXPECTED_HARD HARD invariants were checked")
BROKEN="$(awk -F'|' '$1 == "HARD" && $3 != 0 { print $2 "=" $3 }' "$OUT/invariants.txt" | tr '\n' ' ')"
[ -z "$BROKEN" ] || FAILURES+=("HARD invariants broken: $BROKEN")
if [ -n "$METRICS_PID" ]; then
    # The mock's own drop counters, this run only (last sample minus first: the counters
    # live as long as the mock process). A dropped webhook means the test double, not the
    # gateway, shaped the run. Unreadable is not a pass either.
    MOCK_DROPS="$(python3 -I - "$OUT/metrics.csv" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
for col in ("mock_rejected", "mock_exhausted"):
    first = rows[0].get(col, "") if rows else ""
    last = rows[-1].get(col, "") if rows else ""
    if first == "" or last == "":
        print(f"{col}=unreadable", end=" ")
    elif float(last) - float(first) > 0:
        print(f"{col}=+{float(last) - float(first):g}", end=" ")
PY
)"
    [ -z "$MOCK_DROPS" ] || FAILURES+=("mock provider dropped webhooks: $MOCK_DROPS")
fi

if [ ${#FAILURES[@]} -gt 0 ]; then
    printf 'FAILED: %s\n' "${FAILURES[@]}" >&2
    exit 1
fi
echo "PASSED: $((PAYMENTS_AFTER - PAYMENTS_BEFORE)) payments created, all samples passed, all $EXPECTED_HARD HARD invariants hold."
