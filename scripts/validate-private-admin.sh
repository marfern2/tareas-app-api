#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

curl() { command curl --max-time 15 "$@"; }

# Valida login -> refresh -> logout sin mostrar ni persistir la password o los tokens.
# La password se lee desde un TTY con eco desactivado y nunca se pasa como argumento
# de proceso. Este script no cambia credenciales ni datos.

usage() {
  echo "Uso: $0 dev|prod" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage

case "$1" in
  dev)
    api_url="http://127.0.0.1:8082"
    db_container="tareas-postgres-dev"
    expected_project="tareas-app-api-dev"
    ;;
  prod)
    api_url="http://127.0.0.1:8080"
    db_container="tareas-postgres"
    expected_project="tareas-app-api"
    ;;
  *) usage ;;
esac

for command_name in curl docker jq; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "ERROR: falta el comando ${command_name}" >&2
    exit 1
  }
done

[[ -t 0 ]] || {
  echo "ERROR: se requiere una terminal interactiva para leer la password sin eco" >&2
  exit 1
}

actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${db_container}" 2>/dev/null || true)"
actual_service="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "${db_container}" 2>/dev/null || true)"
if [[ "${actual_project}" != "${expected_project}" || "${actual_service}" != "postgres" ]]; then
  echo "ERROR: el contenedor no pertenece al entorno esperado" >&2
  exit 1
fi

private_email="$(docker exec "${db_container}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT email FROM admin_users WHERE lower(email) <> lower('\''demo@marfern.dev'\'') AND enabled = true ORDER BY id"')"

if [[ -z "${private_email}" || "${private_email}" == *$'\n'* ]]; then
  echo "ERROR: debe existir exactamente un admin privado habilitado en el entorno" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
chmod 700 "${tmp_dir}"
private_password=""
access_token=""
refresh_token=""

cleanup() {
  if [[ -n "${refresh_token}" ]]; then
    {
      printf '{"refreshToken":'
      printf '%s' "${refresh_token}" | jq -Rs .
      printf '}'
    } | curl --silent --output /dev/null \
        --header 'Content-Type: application/json' --data-binary @- \
        "${api_url}/api/admin/auth/logout" || true
  fi
  private_password=""
  access_token=""
  refresh_token=""
  rm -f "${tmp_dir}/login.json" "${tmp_dir}/refresh.json"
  rmdir "${tmp_dir}" 2>/dev/null || true
}
trap cleanup EXIT

read -r -s -p "Password privada ($1): " private_password
printf '\n'
[[ -n "${private_password}" ]] || {
  echo "ERROR: la password no puede estar vacia" >&2
  exit 1
}

login_status="$({
  printf '{"email":'
  printf '%s' "${private_email}" | jq -Rs .
  printf ',"password":'
  printf '%s' "${private_password}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output "${tmp_dir}/login.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${api_url}/api/admin/auth/login")"

private_password=""

if [[ "${login_status}" != "200" ]]; then
  echo "ERROR: login privado fallo (HTTP ${login_status}); no se muestran detalles" >&2
  exit 1
fi

access_token="$(jq -er '.token | select(type == "string" and length > 0)' "${tmp_dir}/login.json" 2>/dev/null)" || {
  echo "ERROR: la respuesta de login no contiene un access token valido" >&2
  exit 1
}
refresh_token="$(jq -er '.refreshToken | select(type == "string" and length > 0)' "${tmp_dir}/login.json" 2>/dev/null)" || {
  echo "ERROR: la respuesta de login no contiene un refresh token valido" >&2
  exit 1
}
rm -f "${tmp_dir}/login.json"

refresh_status="$({
  printf '{"refreshToken":'
  printf '%s' "${refresh_token}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output "${tmp_dir}/refresh.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${api_url}/api/admin/auth/refresh")"

access_token=""

if [[ "${refresh_status}" != "200" ]]; then
  echo "ERROR: refresh privado fallo (HTTP ${refresh_status}); no se muestran detalles" >&2
  exit 1
fi

access_token="$(jq -er '.token | select(type == "string" and length > 0)' "${tmp_dir}/refresh.json" 2>/dev/null)" || {
  echo "ERROR: la respuesta de refresh no contiene un access token valido" >&2
  exit 1
}
refresh_token="$(jq -er '.refreshToken | select(type == "string" and length > 0)' "${tmp_dir}/refresh.json" 2>/dev/null)" || {
  echo "ERROR: la respuesta de refresh no contiene un refresh token valido" >&2
  exit 1
}
rm -f "${tmp_dir}/refresh.json"

logout_status="$({
  printf '{"refreshToken":'
  printf '%s' "${refresh_token}" | jq -Rs .
  printf '}'
} | curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- "${api_url}/api/admin/auth/logout")"

if [[ "${logout_status}" != "204" ]]; then
  echo "ERROR: logout privado fallo (HTTP ${logout_status})" >&2
  exit 1
fi

access_token=""
refresh_token=""

echo "OK: login, refresh y logout del admin privado validados en $1 sin mostrar credenciales ni tokens"
