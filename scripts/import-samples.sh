#!/usr/bin/env bash
# 可重复执行：初始化样本费率（幂等）+ 导入四份样本读数（幂等 upsert）。
# 用法：API=http://localhost:8080 ./scripts/import-samples.sh
set -euo pipefail
API="${API:-http://localhost:8080}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> 初始化样本费率/表计（幂等）"
curl -sS -X POST "$API/api/admin/seed" && echo

for f in readings-tou.csv readings-midmonth.csv readings-tier.csv readings-gap.csv; do
  echo ">> 导入 $f（重复执行结果不变）"
  curl -sS -X POST "$API/api/meters/import" -F "file=@$DIR/samples/$f" && echo
done
