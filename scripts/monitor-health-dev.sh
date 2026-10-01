#!/usr/bin/env bash
set -Eeuo pipefail

fail=0
check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "[monitor-dev] OK ${name}"
  else
    echo "[monitor-dev] CRIT ${name}" >&2
    fail=1
  fi
}

container_healthy() {
  [[ "$(docker inspect --format '{{if and .State.Running (eq .State.Health.Status "healthy")}}ok{{end}}' "$1" 2>/dev/null)" == ok ]]
}

check api-container container_healthy tareas-api-dev
check postgres-container container_healthy tareas-postgres-dev
check api-local curl -fsS --max-time 5 http://127.0.0.1:8082/actuator/health
check api-public curl -fsS --max-time 10 https://donit-api-dev.marfern.dev/actuator/health
exit "${fail}"
