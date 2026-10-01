#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

DEPLOY_ENV="${DEPLOY_ENV:-prod}"
IMAGE="ghcr.io/marfern2/tareas-app-api"

case "${DEPLOY_ENV}" in
  prod)
    PROJECT_DIR="/srv/docker/tareas-app-api"
    BRANCH="master"
    COMPOSE_FILE="compose.yaml"
    LOCAL_HEALTH="http://127.0.0.1:8080/actuator/health"
    PUBLIC_HEALTH="https://donit-api.marfern.dev/actuator/health"
    ;;
  dev)
    PROJECT_DIR="/srv/docker/tareas-app-api-dev"
    BRANCH="develop"
    COMPOSE_FILE="compose.dev.yaml"
    LOCAL_HEALTH="http://127.0.0.1:8082/actuator/health"
    PUBLIC_HEALTH="https://donit-api-dev.marfern.dev/actuator/health"
    ;;
  *)
    echo "DEPLOY_ENV debe ser dev o prod" >&2
    exit 2
    ;;
esac

ENV_FILE="${PROJECT_DIR}/.env"
BACKUP_DIR="${PROJECT_DIR}/backups-antes-deploy"
LOCKFILE="${PROJECT_DIR}/.cd-deploy.lock"
MARKER="${PROJECT_DIR}/.deployed-sha"
FAILED_MARKER="${PROJECT_DIR}/.failed-sha"
LOG_DIR="${PROJECT_DIR}/logs"
LOG_FILE="${LOG_DIR}/cd-deploy.log"
mkdir -p "${LOG_DIR}"

log() { printf '%s [%s] %s\n' "$(date -Is)" "${DEPLOY_ENV}" "$*" | tee -a "${LOG_FILE}"; }

target="${1:-}"
if [[ ! "${target}" =~ ^[0-9a-f]{40}$ ]]; then
  log "ERROR: SHA invalido"
  exit 2
fi
target_tag="${target}"

cd "${PROJECT_DIR}"
exec 9>"${LOCKFILE}"
if ! flock -n 9; then
  log "ESTADO=1 otro deploy en curso"
  exit 1
fi

if ! git fetch origin "${BRANCH}" --quiet 2>>"${LOG_FILE}"; then
  log "ESTADO=1 git fetch fallo"
  exit 1
fi
if ! git archive --format=tar "origin/${BRANCH}" "${COMPOSE_FILE}" scripts/ 2>>"${LOG_FILE}" \
     | tar -x -C "${PROJECT_DIR}" 2>>"${LOG_FILE}"; then
  log "ESTADO=1 sincronizacion de ${COMPOSE_FILE}/scripts fallo"
  exit 1
fi
chmod +x "${PROJECT_DIR}"/scripts/*.sh 2>/dev/null || true

if ! docker manifest inspect "${IMAGE}:${target_tag}" >/dev/null 2>&1; then
  log "ESTADO=1 imagen inexistente ${IMAGE}:${target_tag}"
  exit 1
fi

prev_tag="$(sed -n 's/^API_IMAGE_TAG=//p' "${ENV_FILE}" | tail -n1 | tr -d '\r' || true)"
mkdir -p "${BACKUP_DIR}"
backup_env="${BACKUP_DIR}/.env-$(date +%Y%m%d-%H%M%S).bak"
if ! cp -a "${ENV_FILE}" "${backup_env}"; then
  log "ESTADO=1 no se pudo respaldar .env"
  exit 1
fi
chmod 600 "${backup_env}"

tmp_env="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
trap 'rm -f "${tmp_env}"' EXIT
awk -v t="${target_tag}" '
  /^API_IMAGE_TAG=/ { seen=1; print "API_IMAGE_TAG=" t; next }
  { print }
  END { if (!seen) print "API_IMAGE_TAG=" t }
' "${ENV_FILE}" > "${tmp_env}" && mv "${tmp_env}" "${ENV_FILE}"
chmod 600 "${ENV_FILE}"
log "imagen objetivo=${target_tag} anterior=${prev_tag:-desconocida}"

compose=(docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}")
if ! "${compose[@]}" pull api; then
  cp -a "${backup_env}" "${ENV_FILE}" && chmod 600 "${ENV_FILE}"
  log "ESTADO=1 pull fallo"
  exit 1
fi
if ! "${compose[@]}" up -d --no-deps api >>"${LOG_FILE}" 2>&1; then
  cp -a "${backup_env}" "${ENV_FILE}" && chmod 600 "${ENV_FILE}"
  log "ESTADO=1 compose up fallo"
  exit 1
fi

check_health() {
  local url="$1" attempts="${2:-24}" delay="${3:-5}" resp
  for ((i = 1; i <= attempts; i++)); do
    if resp="$(curl -fsS --max-time 5 "${url}" 2>/dev/null)" \
       && printf '%s' "${resp}" | jq -e '.status == "UP"' >/dev/null 2>&1; then
      return 0
    fi
    sleep "${delay}"
  done
  return 1
}

rollback() {
  log "ROLLBACK aplicacion: restaurando .env anterior; no se revierte la DB"
  cp -a "${backup_env}" "${ENV_FILE}" && chmod 600 "${ENV_FILE}"
  "${compose[@]}" up -d --no-deps api >/dev/null 2>&1 || true
  sleep 15
}

fail_state() {
  local code="$1"; shift
  printf '%s' "${target}" > "${FAILED_MARKER}"
  chmod 600 "${FAILED_MARKER}"
  log "ESTADO=${code} $*"
  exit "${code}"
}

if ! check_health "${LOCAL_HEALTH}" 30 5; then
  rollback
  if check_health "${LOCAL_HEALTH}" 24 5; then
    fail_state 3 "health local fallo; rollback aplicacion OK"
  fi
  fail_state 4 "health local y rollback fallaron"
fi
if ! check_health "${PUBLIC_HEALTH}" 12 10; then
  rollback
  if check_health "${PUBLIC_HEALTH}" 12 10 && check_health "${LOCAL_HEALTH}" 12 5; then
    fail_state 3 "health publico fallo; rollback aplicacion OK"
  fi
  fail_state 4 "health publico y rollback fallaron"
fi

printf '%s' "${target}" > "${MARKER}"
chmod 600 "${MARKER}"
rm -f "${FAILED_MARKER}"
log "ESTADO=0 DEPLOY OK sha=${target} tag=${target_tag}"
