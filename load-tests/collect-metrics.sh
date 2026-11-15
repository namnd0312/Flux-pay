#!/usr/bin/env bash
# Samples gateway and mock-provider metrics every 2 seconds into a CSV until killed.
# Needs the load-test compose override (metrics on 127.0.0.1:9090 and :9091).
# Usage: collect-metrics.sh <output.csv>
set -euo pipefail
OUT="${1:?usage: collect-metrics.sh <output.csv>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$OUT" "$ROOT" "${COMPOSE_PROJECT:-}" <<'PY'
import json, subprocess, sys, time, urllib.error, urllib.request

out, root, project = sys.argv[1], sys.argv[2], sys.argv[3]
GATEWAY, MOCK = "http://127.0.0.1:9090", "http://127.0.0.1:9091"
# column -> (base url, metric name, statistic). A metric that does not exist yet (a
# counter appears only after its first increment, the actuator answers 404) is recorded
# as 0. Any other failure (connection refused, timeout) is an EMPTY cell, never 0: a
# column of zeros must mean "measured and nothing happened", not "could not measure".
COLUMNS = {
    "hikari_active":      (GATEWAY, "hikaricp.connections.active", "VALUE"),
    "hikari_pending":     (GATEWAY, "hikaricp.connections.pending", "VALUE"),
    "hikari_max":         (GATEWAY, "hikaricp.connections.max", "VALUE"),
    "tomcat_busy":        (GATEWAY, "tomcat.threads.busy", "VALUE"),
    "tomcat_max":         (GATEWAY, "tomcat.threads.config.max", "VALUE"),
    "optimistic_retries": (GATEWAY, "fluxpay.payment.optimistic.retries", "COUNT"),
    "retries_webhook":    (GATEWAY, "fluxpay.payment.optimistic.retries?tag=path:webhook", "COUNT"),
    "retries_cancel":     (GATEWAY, "fluxpay.payment.optimistic.retries?tag=path:cancel", "COUNT"),
    "mock_tomcat_busy":   (MOCK, "tomcat.threads.busy", "VALUE"),
    "mock_sent":          (MOCK, "mock.webhook.sent", "COUNT"),
    "mock_retried":       (MOCK, "mock.webhook.retried", "COUNT"),
    "mock_exhausted":     (MOCK, "mock.webhook.exhausted", "COUNT"),
    "mock_rejected":      (MOCK, "mock.webhook.rejected", "COUNT"),
}

def metric(base, name, statistic):
    try:
        with urllib.request.urlopen(f"{base}/actuator/metrics/{name}", timeout=1) as r:
            for m in json.load(r)["measurements"]:
                if m["statistic"] == statistic:
                    return m["value"]
        return ""
    except urllib.error.HTTPError as e:
        return 0 if e.code == 404 else ""
    except Exception:
        return ""

# Fail fast when the metrics endpoints are not there (override not loaded, wrong port):
# run.sh notices that this process has exited and refuses to report a verdict.
for base in (GATEWAY, MOCK):
    try:
        urllib.request.urlopen(f"{base}/actuator/metrics", timeout=2).close()
    except Exception as e:
        sys.exit(f"collect-metrics: {base}/actuator/metrics unreachable ({e}); "
                 "start the stack with load-tests/docker-compose.loadtest.yml")

def lock_waiters():
    cmd = ["docker", "compose"] + (["-p", project] if project else []) + [
        "exec", "-T", "db", "sh", "-c",
        "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -Atc "
        "\"select count(*) from pg_stat_activity where wait_event_type = 'Lock'\""]
    try:
        out = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=3).stdout.strip()
        return int(out) if out else ""
    except Exception:
        return ""

with open(out, "w") as f:
    f.write("epoch_s," + ",".join(COLUMNS) + ",pg_lock_waiters\n")
    while True:
        row = [str(int(time.time()))] + [str(metric(*spec)) for spec in COLUMNS.values()] + [str(lock_waiters())]
        f.write(",".join(row) + "\n")
        f.flush()
        time.sleep(2)
PY
