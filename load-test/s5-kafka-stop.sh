#!/usr/bin/env bash
# S5 Kafka 정지. S1 부하(RATE)를 6분 돌리면서 부하가 붙은 뒤 kafka를 STOP_SECONDS 동안 멈췄다 다시 띄운다.
# 저장소 루트에서 실행한다: RATE=200 load-test/s5-kafka-stop.sh
set -euo pipefail

RATE="${RATE:?S1 포화 RPS의 70%를 RATE로 준다}"
STOP_SECONDS="${STOP_SECONDS:-60}"
# k6 setup(단말 1,000대 등록)과 부하 안정화에 걸리는 시간보다 길게 둔다. 실제 시각은 Grafana에서 확인해 결과에 적는다.
STOP_AFTER_SECONDS="${STOP_AFTER_SECONDS:-150}"
COMPOSE=(docker compose -f docker/docker-compose.yaml)

mkdir -p load-test/results
k6 run -e RATE="$RATE" -e DURATION=6m --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
  --summary-export "load-test/results/s5-rate${RATE}.json" load-test/s1-baseline.js &
k6_pid=$!

sleep "$STOP_AFTER_SECONDS"
echo "kafka 정지 $(date -u +%FT%TZ)"
"${COMPOSE[@]}" stop kafka
sleep "$STOP_SECONDS"
echo "kafka 시작 $(date -u +%FT%TZ)"
"${COMPOSE[@]}" start kafka

wait "$k6_pid"
