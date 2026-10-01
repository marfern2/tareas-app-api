#!/usr/bin/env bash
set -Eeuo pipefail

DEPLOY_ENV="${DEPLOY_ENV:-prod}"
IMAGE="ghcr.io/marfern2/tareas-app-api"

case "${DEPLOY_ENV}" in
  prod)
    PROJECT_DIR="/srv/docker/tareas-app-api"
    BRANCH="master"
    ;;
  dev)
    PROJECT_DIR="/srv/docker/tareas-app-api-dev"
    BRANCH="develop"
    ;;
  *)
    echo "DEPLOY_ENV debe ser dev o prod" >&2
    exit 2
    ;;
esac

DEPLOYED_SHA_FILE="${PROJECT_DIR}/.deployed-sha"
FAILED_SHA_FILE="${PROJECT_DIR}/.failed-sha"
cd "${PROJECT_DIR}"

deployed="$(cat "${DEPLOYED_SHA_FILE}" 2>/dev/null || true)"
failed="$(cat "${FAILED_SHA_FILE}" 2>/dev/null || true)"
target_sha="$(git ls-remote origin "refs/heads/${BRANCH}" 2>/dev/null | awk '{print $1}')"

if [[ ! "${target_sha}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "[cd-poll $(date -Is)] ERROR: no se pudo obtener origin/${BRANCH}" >&2
  exit 1
fi
[[ "${target_sha}" == "${deployed}" ]] && exit 0
[[ "${target_sha}" == "${failed}" ]] && exit 0

tag="${target_sha}"
if ! err="$(docker manifest inspect "${IMAGE}:${tag}" 2>&1)"; then
  echo "[cd-poll $(date -Is)] imagen ${IMAGE}:${tag} aun no publicada (${err}); se espera"
  exit 0
fi

exec env DEPLOY_ENV="${DEPLOY_ENV}" ./scripts/cd-deploy.sh "${target_sha}"
