#!/usr/bin/env bash
# 端到端冒烟编排：停后端 → 重建空库 → 起后端 → 跑 smoke_e2e.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PGBIN="$ROOT/.tools/debs/extracted/usr/lib/postgresql/15/bin"
export LD_LIBRARY_PATH="$ROOT/.tools/debs/extracted/usr/lib/aarch64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JAVA_HOME="$ROOT/.tools/jdk-17.0.20.1+1"

[ -f "$ROOT/.tools/backend.pid" ] && kill "$(cat "$ROOT/.tools/backend.pid")" 2>/dev/null || true
sleep 1
"$PGBIN/dropdb" -h localhost -p 55432 -U postgres park_energy
"$PGBIN/createdb" -h localhost -p 55432 -U postgres park_energy

DB_URL="jdbc:postgresql://localhost:55432/park_energy" DB_USER=postgres DB_PASSWORD=postgres \
  "$JAVA_HOME/bin/java" -jar "$ROOT/backend/target/park-energy-billing-1.0.0.jar" \
  > "$ROOT/.tools/backend.log" 2>&1 &
echo $! > "$ROOT/.tools/backend.pid"

for _ in $(seq 1 30); do
  curl -sf http://localhost:8080/api/meters >/dev/null 2>&1 && break
  sleep 1
done

python3 "$ROOT/scripts/smoke_e2e.py"
