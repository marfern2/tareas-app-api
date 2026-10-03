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

ARTIFACTS=("${COMPOSE_FILE}" scripts/cd-deploy.sh scripts/cd-poll.sh scripts/backup-db.sh)
if [[ "${DEPLOY_ENV}" == prod ]]; then
  ARTIFACTS+=(scripts/monitor-health.sh)
else
  ARTIFACTS+=(scripts/monitor-health-dev.sh)
fi

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
if [[ "$(git cat-file -t "${target}" 2>>"${LOG_FILE}" || true)" != commit ]]; then
  git fetch origin "${target}" --quiet 2>>"${LOG_FILE}" || true
fi
if [[ "$(git cat-file -t "${target}" 2>>"${LOG_FILE}" || true)" != commit ]]; then
  log "ERROR: SHA objetivo no es un commit local"
  exit 2
fi
if ! docker manifest inspect "${IMAGE}:${target_tag}" >/dev/null 2>&1; then
  log "ESTADO=1 imagen inexistente ${IMAGE}:${target_tag}"
  exit 1
fi

sync_artifacts() {
  local sha="$1" stage path
  stage="$(mktemp -d "${PROJECT_DIR}/.cd-artifacts.XXXXXX")" || return 1
  if ! git archive --format=tar "${sha}" -- "${ARTIFACTS[@]}" 2>>"${LOG_FILE}" \
       | tar -x -C "${stage}" 2>>"${LOG_FILE}"; then
    rm -rf -- "${stage}"
    return 1
  fi
  for path in "${ARTIFACTS[@]:1}"; do
    if ! chmod +x "${stage}/${path}" 2>>"${LOG_FILE}"; then
      rm -rf -- "${stage}"
      return 1
    fi
  done
  if ! mkdir -p "${PROJECT_DIR}/scripts"; then
    rm -rf -- "${stage}"
    return 1
  fi
  for path in "${ARTIFACTS[@]}"; do
    if ! mv -fT -- "${stage}/${path}" "${PROJECT_DIR}/${path}"; then
      rm -rf -- "${stage}"
      return 1
    fi
  done
  rmdir "${stage}/scripts" "${stage}"
}

prev_tag="$(sed -n 's/^API_IMAGE_TAG=//p' "${ENV_FILE}" | tail -n1 | tr -d '\r' || true)"
prev_sha="$(cat "${MARKER}" 2>/dev/null || true)"
if ! sync_artifacts "${target}"; then
  if [[ "${prev_sha}" =~ ^[0-9a-f]{40}$ && "${prev_tag}" == "${prev_sha}" ]] \
     && [[ "$(git cat-file -t "${prev_sha}" 2>>"${LOG_FILE}" || true)" == commit ]] \
     && sync_artifacts "${prev_sha}"; then
    log "ESTADO=1 sincronizacion de ${target} fallo; artefactos anteriores restaurados"
    exit 1
  fi
  rm -f "${MARKER}"
  log "ESTADO=4 sincronizacion de ${target} fallo; version anterior no verificable"
  exit 4
fi

mkdir -p "${BACKUP_DIR}"
backup_env="${BACKUP_DIR}/.env-$(date +%Y%m%d-%H%M%S).bak"
if ! cp -a "${ENV_FILE}" "${backup_env}"; then
  log "ESTADO=1 no se pudo respaldar .env"
  exit 1
fi
chmod 600 "${backup_env}"

tmp_env="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
cleanup() { rm -f "${tmp_env}"; }
trap cleanup EXIT

compose=(docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}")
restore_env() {
  local restored
  restored="$(mktemp "${ENV_FILE}.restore.XXXXXX")" || return 1
  if ! cp -a "${backup_env}" "${restored}" || ! chmod 600 "${restored}" || ! mv -f "${restored}" "${ENV_FILE}"; then
    rm -f "${restored}"
    return 1
  fi
}

write_marker() {
  local marker="$1" sha="$2" tmp
  tmp="$(mktemp "${marker}.tmp.XXXXXX")" || return 1
  if ! printf '%s' "${sha}" > "${tmp}" || ! chmod 600 "${tmp}" || ! mv -f "${tmp}" "${marker}"; then
    rm -f "${tmp}"
    return 1
  fi
}

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
  if ! restore_env; then
    log "ROLLBACK fallo: no se pudo restaurar .env"
    return 1
  fi
  if [[ ! "${prev_sha}" =~ ^[0-9a-f]{40}$ || "${prev_tag}" != "${prev_sha}" ]]; then
    log "ROLLBACK fallo: no hay SHA anterior valido y coincidente con .env (bootstrap o estado inconsistente)"
    return 1
  fi
  if ! docker image inspect "${IMAGE}:${prev_sha}" >/dev/null 2>&1; then
    log "ROLLBACK fallo: imagen anterior ${IMAGE}:${prev_sha} no disponible localmente"
    return 1
  fi
  if [[ "$(git cat-file -t "${prev_sha}" 2>>"${LOG_FILE}" || true)" != commit ]] \
     || ! sync_artifacts "${prev_sha}"; then
    log "ROLLBACK fallo: artefactos del commit anterior ${prev_sha} no disponibles"
    return 1
  fi
  if ! "${compose[@]}" up -d --no-deps api >>"${LOG_FILE}" 2>&1; then
    log "ROLLBACK fallo: compose up de la imagen anterior fallo"
    return 1
  fi
  if ! sleep 15; then
    log "ROLLBACK fallo: espera de arranque interrumpida"
    return 1
  fi
  if ! check_health "${LOCAL_HEALTH}" 24 5 || ! check_health "${PUBLIC_HEALTH}" 12 10; then
    log "ROLLBACK fallo: health de la imagen anterior fallo"
    return 1
  fi
  write_marker "${MARKER}" "${prev_sha}"
}

fail_with_rollback() {
  local reason="$1" code=4
  trap '' HUP INT TERM
  trap - ERR
  if rollback; then
    code=3
  else
    # The previous SHA is no longer verified as the running healthy version.
    if ! rm -f "${MARKER}"; then
      log "ERROR: no se pudo invalidar .deployed-sha"
    fi
  fi
  if ! write_marker "${FAILED_MARKER}" "${target}"; then
    log "ERROR: no se pudo registrar .failed-sha"
    code=4
  fi
  log "ESTADO=${code} ${reason}; rollback aplicacion $([[ ${code} -eq 3 ]] && printf OK || printf FALLO)"
  exit "${code}"
}

trap 'fail_with_rollback "interrupcion"' HUP INT TERM
trap 'fail_with_rollback "error inesperado"' ERR
awk -v t="${target_tag}" '
  /^API_IMAGE_TAG=/ { seen=1; print "API_IMAGE_TAG=" t; next }
  { print }
  END { if (!seen) print "API_IMAGE_TAG=" t }
' "${ENV_FILE}" > "${tmp_env}"
chmod 600 "${tmp_env}"
mv "${tmp_env}" "${ENV_FILE}"
log "imagen objetivo=${target_tag} anterior=${prev_tag:-desconocida}"
if ! "${compose[@]}" pull api; then
  if ! restore_env || [[ ! "${prev_sha}" =~ ^[0-9a-f]{40}$ ]] || [[ "${prev_tag}" != "${prev_sha}" ]] \
     || [[ "$(git cat-file -t "${prev_sha}" 2>>"${LOG_FILE}" || true)" != commit ]] \
     || ! sync_artifacts "${prev_sha}"; then
    rm -f "${MARKER}"
    log "ESTADO=4 pull fallo y no se pudo restaurar la version anterior"
    exit 4
  fi
  log "ESTADO=1 pull fallo"
  exit 1
fi
if ! "${compose[@]}" up -d --no-deps api >>"${LOG_FILE}" 2>&1; then
  fail_with_rollback "compose up fallo"
fi

if ! check_health "${LOCAL_HEALTH}" 30 5; then
  fail_with_rollback "health local fallo"
fi
if ! check_health "${PUBLIC_HEALTH}" 12 10; then
  fail_with_rollback "health publico fallo"
fi

write_marker "${MARKER}" "${target}"
trap - HUP INT TERM ERR
rm -f "${FAILED_MARKER}"
log "ESTADO=0 DEPLOY OK sha=${target} tag=${target_tag}"
