#!/usr/bin/env bash
# 一键启动：PostgreSQL(5439) + Spring Boot(8080) + Vite(5173)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash scripts/start-pg.sh

export JAVA_HOME="$ROOT/.downloads/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$ROOT/.downloads/apache-maven-3.9.9/bin:$PATH"
export DB_PORT="${DB_PORT:-5439}"

echo "[app] 启动 Spring Boot（后台日志 backend/run.log）"
if [ ! -f backend/target/energy-billing-1.0.0.jar ]; then
  (cd backend && mvn -q -DskipTests package)
fi
SAMPLES_DIR="$ROOT/data/samples" \
  nohup java -jar backend/target/energy-billing-1.0.0.jar > backend/run.log 2>&1 &
echo $! > "$ROOT/data/backend.pid"

echo "[web] 启动前端开发服务器（后台日志 frontend/run.log）"
cd frontend
nohup npm run dev -- --host 127.0.0.1 > run.log 2>&1 &
echo $! > "$ROOT/data/frontend.pid"
cd ..

echo ""
echo "全部启动："
echo "  前端 http://127.0.0.1:5173"
echo "  后端 http://127.0.0.1:8080/api/meta"
echo "  停止: bash scripts/stop.sh"
