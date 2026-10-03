#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_DIR="/srv/docker/tareas-app-api-dev"
DB_CONTAINER="tareas-postgres-dev"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd -- "${script_dir}/.." && pwd -P)"
[[ "${project_dir}" == "${EXPECTED_DIR}" ]] || {
  echo "ERROR: este comando solo puede ejecutarse desde ${EXPECTED_DIR}" >&2
  exit 1
}
[[ -t 0 ]] || {
  echo "ERROR: se requiere una terminal interactiva" >&2
  exit 1
}

cd "${project_dir}"
[[ -f .env && "$(stat -c '%a' .env)" == "600" ]] || {
  echo "ERROR: .env debe existir con permisos 600" >&2
  exit 1
}

set -a
# shellcheck disable=SC1091
source .env
set +a
[[ "${POSTGRES_DB:-}" == "donit_dev" && "${DEV_SEED_ENABLED:-}" == "true" ]] || {
  echo "ERROR: las salvaguardas DEV no coinciden" >&2
  exit 1
}

project_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${DB_CONTAINER}")"
[[ "${project_label}" == "tareas-app-api-dev" ]] || {
  echo "ERROR: PostgreSQL no pertenece al entorno DEV" >&2
  exit 1
}

new_password=""
confirmation=""
rotation_log=""
env_tmp=""
cleanup() {
  new_password=""
  confirmation=""
  [[ -z "${rotation_log}" || ! -e "${rotation_log}" ]] || rm -f "${rotation_log}"
  [[ -z "${env_tmp}" || ! -e "${env_tmp}" ]] || rm -f "${env_tmp}"
}
trap cleanup EXIT

read -r -s -p "Nueva password privada DEV (minimo 12 caracteres): " new_password
printf '\n'
read -r -s -p "Repite la nueva password privada DEV: " confirmation
printf '\n'
[[ ${#new_password} -ge 12 ]] || {
  echo "ERROR: la password debe tener al menos 12 caracteres" >&2
  exit 1
}
[[ "${new_password}" == "${confirmation}" ]] || {
  echo "ERROR: las passwords no coinciden" >&2
  exit 1
}

before_accounts="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM admin_users"')"

rotation_log="$(mktemp)"
chmod 600 "${rotation_log}"
if ! printf '%s\n%s\n' "${new_password}" "${confirmation}" \
    | docker compose --env-file .env -f compose.dev.yaml -f compose.build.yaml run --rm -T --no-deps \
        -e SPRING_PROFILES_ACTIVE=runtime,dev-admin-rotation \
        -e APP_ADMIN_ROTATION_ENABLED=true \
        -e APP_ADMIN_ROTATION_ENVIRONMENT=development \
        -e APP_ADMIN_ROTATION_USERNAME=admin-dev \
        -e APP_ADMIN_ROTATION_TARGET_EMAIL=admin@gmail.com \
        api >"${rotation_log}" 2>&1; then
  echo "ERROR: la rotacion DEV fallo; no se muestran detalles potencialmente sensibles" >&2
  rm -f "${rotation_log}"
  exit 1
fi
grep -q '^ROTATION_OK:' "${rotation_log}" || {
  echo "ERROR: el proceso no confirmo la rotacion DEV" >&2
  rm -f "${rotation_log}"
  exit 1
}
rm -f "${rotation_log}"
rotation_log=""

env_tmp="$(mktemp "${project_dir}/.env.rotation.XXXXXX")"
chmod 600 "${env_tmp}"
email_written=false
password_written=false
while IFS= read -r line || [[ -n "${line}" ]]; do
  case "${line}" in
    DEV_ADMIN_EMAIL=*)
      printf 'DEV_ADMIN_EMAIL=%s\n' 'admin@gmail.com' >>"${env_tmp}"
      email_written=true
      ;;
    DEV_ADMIN_PASSWORD=*)
      printf 'DEV_ADMIN_PASSWORD=%s\n' 'NO_GUARDAR_PASSWORD_REAL_EN_GIT' >>"${env_tmp}"
      password_written=true
      ;;
    *) printf '%s\n' "${line}" >>"${env_tmp}" ;;
  esac
done < .env
[[ "${email_written}" == true ]] || printf 'DEV_ADMIN_EMAIL=%s\n' 'admin@gmail.com' >>"${env_tmp}"
[[ "${password_written}" == true ]] || printf 'DEV_ADMIN_PASSWORD=%s\n' 'NO_GUARDAR_PASSWORD_REAL_EN_GIT' >>"${env_tmp}"
mv "${env_tmp}" .env
env_tmp=""
chmod 600 .env

new_password=""
confirmation=""

rotated_hash="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT password_hash FROM admin_users WHERE email = '\''admin@gmail.com'\''"')"
[[ -n "${rotated_hash}" ]] || {
  echo "ERROR: no se encontro el admin privado DEV rotado" >&2
  exit 1
}

docker compose --env-file .env -f compose.dev.yaml up -d --no-deps --force-recreate api >/dev/null
healthy=false
for _ in {1..60}; do
  if curl --fail --silent --show-error --max-time 3 http://127.0.0.1:8082/actuator/health \
      | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done
[[ "${healthy}" == true ]] || {
  echo "ERROR: API DEV no recupero health tras el reinicio" >&2
  exit 1
}

after_hash="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT password_hash FROM admin_users WHERE email = '\''admin@gmail.com'\''"')"
after_accounts="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM admin_users"')"
[[ "${rotated_hash}" == "${after_hash}" && "${before_accounts}" == "${after_accounts}" ]] || {
  echo "ERROR: el reinicio altero el hash o duplico cuentas" >&2
  exit 1
}

echo "Rotacion DEV aplicada. Introduce una vez mas la nueva password para validar login/refresh/logout:"
"${script_dir}/validate-private-admin.sh" dev
"${script_dir}/validate-demo-dev.sh"
echo "OK: admin privado DEV rotado, sesiones revocadas y password persistente tras recrear la API"
