#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

curl() { command curl --max-time 15 "$@"; }

# E2E de la cuenta demo contra DEV. Lee la credencial publica desde .env,
# no muestra tokens y elimina los objetos temporales creados durante el CRUD.

EXPECTED_DIR="/srv/docker/tareas-app-api-dev"
API_URL="http://127.0.0.1:8082"
ADMIN_URL="http://127.0.0.1:8083"
DB_CONTAINER="tareas-postgres-dev"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd -- "${script_dir}/.." && pwd -P)"
[[ "${project_dir}" == "${EXPECTED_DIR}" ]] || {
  echo "ERROR: este script solo puede ejecutarse desde ${EXPECTED_DIR}" >&2
  exit 1
}

cd "${project_dir}"
[[ -f .env && "$(stat -c '%a' .env)" == "600" ]] || {
  echo "ERROR: falta .env o no tiene permisos 600" >&2
  exit 1
}

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ "${POSTGRES_DB:-}" != "donit_dev" || "${DEV_SEED_ENABLED:-}" != "true" ]]; then
  echo "ERROR: las salvaguardas DEV no coinciden" >&2
  exit 1
fi
for value_name in DEV_DEMO_EMAIL DEV_DEMO_PASSWORD; do
  [[ -n "${!value_name:-}" ]] || {
    echo "ERROR: falta ${value_name}" >&2
    exit 1
  }
done

actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${DB_CONTAINER}" 2>/dev/null || true)"
[[ "${actual_project}" == "tareas-app-api-dev" ]] || {
  echo "ERROR: PostgreSQL no pertenece al proyecto DEV" >&2
  exit 1
}

tmp_dir="$(mktemp -d)"
chmod 700 "${tmp_dir}"
access_token=""
refresh_token=""
user_id=""
type_id=""
task_id=""
stage="initialization"

cleanup() {
  if [[ -n "${user_id}" && -f "${tmp_dir}/auth-header" ]]; then
    if [[ -n "${task_id}" ]]; then
      curl --silent --output /dev/null --request DELETE \
        --header @"${tmp_dir}/auth-header" \
        "${API_URL}/api/admin/users/${user_id}/tasks/${task_id}" || true
    fi
    if [[ -n "${type_id}" ]]; then
      curl --silent --output /dev/null --request DELETE \
        --header @"${tmp_dir}/auth-header" \
        "${API_URL}/api/admin/users/${user_id}/task-types/${type_id}" || true
    fi
  fi
  if [[ -n "${refresh_token}" ]]; then
    {
      printf '{"refreshToken":'
      printf '%s' "${refresh_token}" | jq -Rs .
      printf '}'
    } | curl --silent --output /dev/null \
        --header 'Content-Type: application/json' --data-binary @- \
        "${API_URL}/api/admin/auth/logout" || true
  fi
  access_token=""
  refresh_token=""
  DEV_DEMO_PASSWORD=""
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT
trap 'echo "ERROR: fallo de validacion en etapa ${stage} (linea ${LINENO})" >&2' ERR

expect_status() {
  local expected="$1"
  local actual="$2"
  local operation="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "ERROR: ${operation} devolvio HTTP ${actual}, se esperaba ${expected}" >&2
    exit 1
  fi
}

curl --fail --silent --show-error --max-time 5 "${API_URL}/actuator/health" \
  | jq -e '.status == "UP"' >/dev/null
curl --fail --silent --show-error --max-time 5 "${ADMIN_URL}/health" >/dev/null

stage="login"
login_status="$({
  printf '{"email":'
  printf '%s' "${DEV_DEMO_EMAIL}" | jq -Rs .
  printf ',"password":'
  printf '%s' "${DEV_DEMO_PASSWORD}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output "${tmp_dir}/login.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${API_URL}/api/admin/auth/login")"
DEV_DEMO_PASSWORD=""
expect_status 200 "${login_status}" "login demo DEV"

access_token="$(jq -er '.token | select(type == "string" and length > 0)' "${tmp_dir}/login.json")"
refresh_token="$(jq -er '.refreshToken | select(type == "string" and length > 0)' "${tmp_dir}/login.json")"
rm -f "${tmp_dir}/login.json"

stage="refresh"
refresh_status="$({
  printf '{"refreshToken":'
  printf '%s' "${refresh_token}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output "${tmp_dir}/refresh.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${API_URL}/api/admin/auth/refresh")"
access_token=""
expect_status 200 "${refresh_status}" "refresh demo DEV"

access_token="$(jq -er '.token | select(type == "string" and length > 0)' "${tmp_dir}/refresh.json")"
refresh_token="$(jq -er '.refreshToken | select(type == "string" and length > 0)' "${tmp_dir}/refresh.json")"
rm -f "${tmp_dir}/refresh.json"
printf 'Authorization: Bearer %s\n' "${access_token}" >"${tmp_dir}/auth-header"

stage="listados"
for resource in users tasks task-types; do
  status="$(curl --silent --show-error --output "${tmp_dir}/${resource}.json" --write-out '%{http_code}' \
    --header @"${tmp_dir}/auth-header" "${API_URL}/api/admin/${resource}?size=50")"
  expect_status 200 "${status}" "listar ${resource}"
  jq -e '.totalElements > 0 and (.content | type == "array")' "${tmp_dir}/${resource}.json" >/dev/null
done

jq -e 'all(.content[]; .email | endswith("@example.invalid"))' "${tmp_dir}/users.json" >/dev/null
user_id="$(jq -er '.content[0].id' "${tmp_dir}/users.json")"

stage="crear tipo"
type_status="$(curl --silent --show-error --output "${tmp_dir}/created-type.json" --write-out '%{http_code}' \
  --request POST --header @"${tmp_dir}/auth-header" --header 'Content-Type: application/json' \
  --data '{"nombre":"Validacion temporal","descripcion":"Objeto ficticio de prueba E2E","color":"#A855F7"}' \
  "${API_URL}/api/admin/users/${user_id}/task-types")"
expect_status 201 "${type_status}" "crear tipo ficticio"
type_id="$(jq -er '.id' "${tmp_dir}/created-type.json")"

stage="crear tarea"
future_date="$(date -d '+10 days' +%F)"
jq -n --arg title "Validacion temporal" --arg date "${future_date}" --argjson typeId "${type_id}" \
  '{titulo:$title, descripcion:"Objeto ficticio de prueba E2E", fecha:$date, completada:false, urgencia:1, tipoTareaId:$typeId}' \
  >"${tmp_dir}/create-task-request.json"
task_status="$(curl --silent --show-error --output "${tmp_dir}/created-task.json" --write-out '%{http_code}' \
  --request POST --header @"${tmp_dir}/auth-header" --header 'Content-Type: application/json' \
  --data-binary @"${tmp_dir}/create-task-request.json" "${API_URL}/api/admin/users/${user_id}/tasks")"
expect_status 201 "${task_status}" "crear tarea ficticia"
task_id="$(jq -er '.id' "${tmp_dir}/created-task.json")"

stage="leer tarea"
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header @"${tmp_dir}/auth-header" "${API_URL}/api/admin/tasks/${task_id}")"
expect_status 200 "${status}" "leer tarea ficticia"

stage="actualizar tarea"
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request PATCH --header @"${tmp_dir}/auth-header" --header 'Content-Type: application/json' \
  --data '{"completada":true,"urgencia":2}' "${API_URL}/api/admin/users/${user_id}/tasks/${task_id}")"
expect_status 200 "${status}" "actualizar tarea ficticia"

stage="eliminar tarea"
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request DELETE --header @"${tmp_dir}/auth-header" \
  "${API_URL}/api/admin/users/${user_id}/tasks/${task_id}")"
expect_status 204 "${status}" "eliminar tarea ficticia"
task_id=""

stage="actualizar tipo"
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request PATCH --header @"${tmp_dir}/auth-header" --header 'Content-Type: application/json' \
  --data '{"color":"#7C3AED"}' "${API_URL}/api/admin/users/${user_id}/task-types/${type_id}")"
expect_status 200 "${status}" "actualizar tipo ficticio"

stage="eliminar tipo"
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request DELETE --header @"${tmp_dir}/auth-header" \
  "${API_URL}/api/admin/users/${user_id}/task-types/${type_id}")"
expect_status 204 "${status}" "eliminar tipo ficticio"
type_id=""

stage="logout"
logout_status="$({
  printf '{"refreshToken":'
  printf '%s' "${refresh_token}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${API_URL}/api/admin/auth/logout")"
expect_status 204 "${logout_status}" "logout demo DEV"

access_token=""
refresh_token=""
echo "OK: health, login, refresh, listados, CRUD ficticio y logout demo validados solo en DEV"
