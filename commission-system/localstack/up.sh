#!/usr/bin/env bash
# 로컬 스택 한 명령(E2 ⑤) — Oracle Free(compose) + 시드 + A-1 두 번째 판 시나리오 + 엔진 앱. README "로컬 실행".
#   localstack/up.sh          새 컨테이너 → Flyway·시드·시나리오(commission-localstack) → 엔진 앱 기동(백그라운드, :8080)
#   localstack/up.sh verify   피드를 afterSeq=0부터 끝까지 받아 a1_portal_150000_e2.jsonl과 바이트 비교
#   localstack/up.sh down     앱 종료 + 컨테이너·볼륨 제거
# 서비스 토큰은 .state/engine-token(권한 600)에만 쓴다 — 이 스크립트는 토큰·암호를 출력하지 않는다(앱에는 SHA-256만 넘긴다).
# 포털 e2e(e2e/engine-feed/)는 그 파일을 읽는다.
set -euo pipefail

cd "$(dirname "$0")/.."                      # commission-system
STATE=localstack/.state
COMPOSE=(docker compose -f localstack/compose.yaml)
APP_PORT="${APP_PORT:-8080}"

image() {
  sed -n 's/^oracle-free-image *= *"\(.*\)"$/\1/p' gradle/libs.versions.toml
}

sha256() {
  openssl dgst -sha256 | awk '{print $NF}'
}

secrets() {
  export ORACLE_IMAGE
  ORACLE_IMAGE="$(image)"
  test -n "$ORACLE_IMAGE" || { echo 'gradle/libs.versions.toml에 oracle-free-image가 없다' >&2; exit 1; }
  export ORACLE_PASSWORD
  ORACLE_PASSWORD="$(cat "$STATE/db-password")"
}

up() {
  mkdir -p "$STATE"
  chmod 700 "$STATE"
  ( umask 077
    openssl rand -hex 16 > "$STATE/db-password"
    openssl rand -hex 32 > "$STATE/engine-token" )
  secrets
  echo "[up] Oracle 새로 기동: $ORACLE_IMAGE"
  "${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
  "${COMPOSE[@]}" up -d --wait

  export DB_URL="jdbc:oracle:thin:@//localhost:${ORACLE_PORT:-1521}/FREEPDB1"
  export DB_USERNAME=engine
  export DB_PASSWORD="$ORACLE_PASSWORD"
  export ENGINE_TENANT_ID=GA01
  export ENGINE_SERVICE_TOKEN_SHA256
  ENGINE_SERVICE_TOKEN_SHA256="$(tr -d '\n' < "$STATE/engine-token" | sha256)"

  echo '[up] Flyway + 시드 + A-1 두 번째 판(commission-localstack)'
  ./gradlew -q --no-daemon :commission-localstack:run

  echo "[up] 엔진 앱 기동(:$APP_PORT, 테넌트 GA01, 기본 프로파일)"
  ./gradlew -q --no-daemon :commission-app:bootJar
  jar="$(ls commission-app/build/libs/commission-app-*.jar | grep -v -- '-plain' | head -1)"
  nohup java -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8 -jar "$jar" --server.port="$APP_PORT" > "$STATE/app.log" 2>&1 &
  echo $! > "$STATE/app.pid"
  for _ in $(seq 1 120); do
    if curl -sf "http://localhost:$APP_PORT/actuator/health" > /dev/null; then
      echo "[up] 준비됨 — 피드 http://localhost:$APP_PORT/internal/v1/events, 토큰 파일 localstack/.state/engine-token"
      return 0
    fi
    if ! kill -0 "$(cat "$STATE/app.pid")" 2> /dev/null; then
      echo '[up] 앱이 종료됐다 — localstack/.state/app.log' >&2
      tail -n 40 "$STATE/app.log" >&2
      exit 1
    fi
    sleep 2
  done
  echo '[up] 앱 준비 시간 초과 — localstack/.state/app.log' >&2
  exit 1
}

verify() {
  ./gradlew -q --no-daemon :commission-localstack:verifyFeed -PengineUrl="http://localhost:$APP_PORT"
}

down() {
  if [ -f "$STATE/app.pid" ]; then
    kill "$(cat "$STATE/app.pid")" 2> /dev/null || true
    rm -f "$STATE/app.pid"
  fi
  if [ -f "$STATE/db-password" ]; then
    secrets
    "${COMPOSE[@]}" down -v --remove-orphans
  fi
}

case "${1:-up}" in
  up) up ;;
  verify) verify ;;
  down) down ;;
  *) echo "usage: $0 [up|verify|down]" >&2; exit 2 ;;
esac
