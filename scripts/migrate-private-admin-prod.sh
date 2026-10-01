#!/usr/bin/env bash
set -Eeuo pipefail

PROD_DIR="/srv/docker/tareas-app-api"
DB_CONTAINER="tareas-postgres"
LOCK_FILE="/tmp/donit-migrate-private-admin-prod.lock"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ "$(cd -- "${script_dir}/.." && pwd -P)" == "${PROD_DIR}" ]] || {
  echo "ERROR: herramienta fuera del directorio auditado" >&2
  exit 1
}
[[ -t 0 ]] || {
  echo "ERROR: se requiere una terminal interactiva" >&2
  exit 1
}

for command_name in curl docker flock jq; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "ERROR: falta el comando ${command_name}" >&2
    exit 1
  }
done

exec 9>"${LOCK_FILE}"
flock -n 9 || {
  echo "ERROR: ya hay otra migracion PROD en curso" >&2
  exit 1
}

project_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${DB_CONTAINER}")"
service_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "${DB_CONTAINER}")"
[[ "${project_label}" == "tareas-app-api" && "${service_label}" == "postgres" ]] || {
  echo "ERROR: PostgreSQL no pertenece al entorno PROD" >&2
  exit 1
}

curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/actuator/health >/dev/null
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8081/health >/dev/null

target_count="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM admin_users WHERE lower(email) = lower('\''admin@gmail.com'\'')"')"
[[ "${target_count}" == "0" ]] || {
  echo "ERROR: admin@gmail.com ya existe en PROD; no se modifica nada" >&2
  exit 1
}

admin_identity="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT id || '\''|'\'' || username FROM admin_users WHERE enabled = true"')"
[[ "${admin_identity}" == "1|mar" ]] || {
  echo "ERROR: el admin PROD no coincide con ID 1 y username mar; no se modifica nada" >&2
  exit 1
}

admin_count="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM admin_users"')"
[[ "${admin_count}" == "1" ]] || {
  echo "ERROR: PROD debe contener exactamente un admin; no se modifica nada" >&2
  exit 1
}

backup_log="$(mktemp)"
chmod 600 "${backup_log}"
if ! (cd "${PROD_DIR}" && ./scripts/backup-db.sh >"${backup_log}" 2>&1); then
  echo "ERROR: fallo el backup PROD previo; no se modifica nada" >&2
  rm -f "${backup_log}"
  exit 1
fi
rm -f "${backup_log}"

echo "Validacion previa de la password PROD actual:"
"${script_dir}/validate-private-admin.sh" prod

before_hash="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT password_hash FROM admin_users WHERE id = 1 AND username = '\''mar'\''"')"

docker exec -i "${DB_CONTAINER}" sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null <<'SQL'
BEGIN;
DO $$
BEGIN
    IF (SELECT count(*) FROM admin_users WHERE id = 1 AND username = 'mar' AND enabled = true) <> 1
       OR (SELECT count(*) FROM admin_users) <> 1 THEN
        RAISE EXCEPTION 'PROD debe contener exactamente un admin antes de la migracion';
    END IF;
    IF EXISTS (SELECT 1 FROM admin_users WHERE lower(email) = lower('demo@marfern.dev')) THEN
        RAISE EXCEPTION 'La cuenta demo no debe existir en PROD';
    END IF;
    IF EXISTS (SELECT 1 FROM admin_users WHERE lower(email) = lower('admin@gmail.com')) THEN
        RAISE EXCEPTION 'El email objetivo ya existe';
    END IF;
    IF (SELECT count(*) FROM admin_users WHERE enabled = true) <> 1 THEN
        RAISE EXCEPTION 'PROD debe contener exactamente un admin habilitado';
    END IF;
END $$;
SELECT id FROM admin_users WHERE id = 1 AND username = 'mar' FOR UPDATE;
DELETE FROM admin_refresh_tokens
WHERE admin_user_id = 1;
UPDATE admin_users
SET email = 'admin@gmail.com'
WHERE id = 1 AND username = 'mar';
COMMIT;
SQL

after_hash="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT password_hash FROM admin_users WHERE email = '\''admin@gmail.com'\''"')"
[[ -n "${after_hash}" && "${before_hash}" == "${after_hash}" ]] || {
  echo "ERROR: la verificacion del hash PROD no coincide" >&2
  exit 1
}

active_sessions="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM admin_refresh_tokens WHERE revoked_at IS NULL AND expires_at > now()"')"
[[ "${active_sessions}" == "0" ]] || {
  echo "ERROR: quedaron sesiones administrativas activas tras la migracion" >&2
  exit 1
}

curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/actuator/health >/dev/null
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8081/health >/dev/null

echo "Email PROD migrado sin cambiar el hash. Repite la password para validar login/refresh/logout:"
"${script_dir}/validate-private-admin.sh" prod
echo "OK: email privado PROD migrado, hash preservado, sesiones antiguas revocadas y PROD no reiniciado"
