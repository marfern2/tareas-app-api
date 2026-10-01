#!/usr/bin/env bash
set -Eeuo pipefail

# Reset explicito del sandbox publico DEV.
# Conserva Flyway, el admin privado y sus sesiones. Elimina y reconstruye solo:
# - cuenta admin demo + sus refresh tokens
# - usuarios Android ficticios + sus refresh tokens, tipos y tareas

EXPECTED_DIR="/srv/docker/tareas-app-api-dev"
DB_CONTAINER="tareas-postgres-dev"
LOCK_FILE="/tmp/donit-reset-demo-dev.lock"

usage() {
  echo "Uso: $0 --confirm-reset-dev" >&2
  exit 2
}

[[ $# -eq 1 && "$1" == "--confirm-reset-dev" ]] || usage

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd -- "${script_dir}/.." && pwd -P)"
if [[ "${project_dir}" != "${EXPECTED_DIR}" ]]; then
  echo "ERROR: este script solo puede ejecutarse desde ${EXPECTED_DIR}" >&2
  exit 1
fi

cd "${project_dir}"
[[ -f .env ]] || {
  echo "ERROR: falta ${EXPECTED_DIR}/.env" >&2
  exit 1
}
[[ "$(stat -c '%a' .env)" == "600" ]] || {
  echo "ERROR: .env debe tener permisos 600" >&2
  exit 1
}

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ "${POSTGRES_DB:-}" != "donit_dev" || "${DEV_SEED_ENABLED:-}" != "true" ]]; then
  echo "ERROR: las salvaguardas de base de datos/perfil DEV no coinciden" >&2
  exit 1
fi
if [[ "${DEV_DEMO_EMAIL:-}" != "demo@marfern.dev" ]]; then
  echo "ERROR: DEV_DEMO_EMAIL no coincide con la cuenta demo esperada" >&2
  exit 1
fi

for command_name in curl docker flock; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "ERROR: falta el comando ${command_name}" >&2
    exit 1
  }
done

exec 9>"${LOCK_FILE}"
flock -n 9 || {
  echo "ERROR: ya hay otro reset DEV en curso" >&2
  exit 1
}

actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${DB_CONTAINER}" 2>/dev/null || true)"
actual_service="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "${DB_CONTAINER}" 2>/dev/null || true)"
if [[ "${actual_project}" != "tareas-app-api-dev" || "${actual_service}" != "postgres" ]]; then
  echo "ERROR: el contenedor PostgreSQL no pertenece al proyecto DEV esperado" >&2
  exit 1
fi

actual_database="$(docker exec "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT current_database()"')"
if [[ "${actual_database}" != "donit_dev" ]]; then
  echo "ERROR: PostgreSQL no confirma que la base activa sea donit_dev" >&2
  exit 1
fi

echo "[reset-demo-dev] Creando backup previo de DEV"
backup_log="$(mktemp)"
chmod 600 "${backup_log}"
if ! BACKUP_CONTAINER="${DB_CONTAINER}" \
    BACKUP_DIR="/srv/docker/backups/tareas-app-postgres-dev" \
    BACKUP_PREFIX="tareas-app-dev" \
    BACKUP_RETENTION_DAYS=3 \
      ./scripts/backup-db.sh >"${backup_log}" 2>&1; then
  echo "ERROR: fallo el backup previo de DEV" >&2
  tail -n 20 "${backup_log}" >&2
  rm -f "${backup_log}"
  exit 1
fi
rm -f "${backup_log}"
echo "[reset-demo-dev] Backup DEV validado"

api_stopped=false
restart_api_on_error() {
  if [[ "${api_stopped}" == "true" ]]; then
    docker compose --env-file .env -f compose.dev.yaml up -d api >/dev/null 2>&1 || true
  fi
}
trap restart_api_on_error ERR

echo "[reset-demo-dev] Deteniendo solo la API DEV durante el reset"
docker compose --env-file .env -f compose.dev.yaml stop api
api_stopped=true

docker exec -i "${DB_CONTAINER}" sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null <<'SQL'
BEGIN;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM admin_users
        WHERE (lower(email) = lower('demo@marfern.dev') OR username = 'demo')
          AND NOT (lower(email) = lower('demo@marfern.dev') AND username = 'demo')
    ) THEN
        RAISE EXCEPTION 'Conflicto de identidad de la demo DEV';
    END IF;
    IF EXISTS (
        SELECT 1 FROM usuarios
        WHERE (username IN ('alex-demo', 'sam-demo', 'disabled-demo')
               OR email IN ('alex@example.invalid', 'sam@example.invalid', 'disabled@example.invalid'))
          AND (username, email) NOT IN (
              ('alex-demo', 'alex@example.invalid'),
              ('sam-demo', 'sam@example.invalid'),
              ('disabled-demo', 'disabled@example.invalid')
          )
    ) THEN
        RAISE EXCEPTION 'Conflicto de identidad de usuarios ficticios DEV';
    END IF;
END $$;
DELETE FROM admin_refresh_tokens
WHERE admin_user_id IN (
    SELECT id FROM admin_users WHERE lower(email) = lower('demo@marfern.dev') AND username = 'demo'
);
DELETE FROM admin_users WHERE lower(email) = lower('demo@marfern.dev') AND username = 'demo';
DELETE FROM refresh_tokens WHERE usuario_id IN (
    SELECT id FROM usuarios WHERE (username, email) IN (
        ('alex-demo', 'alex@example.invalid'),
        ('sam-demo', 'sam@example.invalid'),
        ('disabled-demo', 'disabled@example.invalid'))
);
DELETE FROM tareas WHERE usuario_id IN (
    SELECT id FROM usuarios WHERE (username, email) IN (
        ('alex-demo', 'alex@example.invalid'),
        ('sam-demo', 'sam@example.invalid'),
        ('disabled-demo', 'disabled@example.invalid'))
);
DELETE FROM tipos_tarea WHERE usuario_id IN (
    SELECT id FROM usuarios WHERE (username, email) IN (
        ('alex-demo', 'alex@example.invalid'),
        ('sam-demo', 'sam@example.invalid'),
        ('disabled-demo', 'disabled@example.invalid'))
);
DELETE FROM usuarios WHERE (username, email) IN (
    ('alex-demo', 'alex@example.invalid'),
    ('sam-demo', 'sam@example.invalid'),
    ('disabled-demo', 'disabled@example.invalid')
);
COMMIT;
SQL

echo "[reset-demo-dev] Arrancando API DEV para ejecutar el seed protegido"
docker compose --env-file .env -f compose.dev.yaml up -d api
api_stopped=false
trap - ERR

healthy=false
for _ in {1..60}; do
  if curl --fail --silent --show-error --max-time 3 \
      http://127.0.0.1:8082/actuator/health | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done
if [[ "${healthy}" != "true" ]]; then
  echo "ERROR: la API DEV no recupero health tras el reset" >&2
  exit 1
fi

counts="$(docker exec -i "${DB_CONTAINER}" sh -c \
  'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT (SELECT count(*) FROM usuarios) || ' users, '
    || (SELECT count(*) FROM tipos_tarea) || ' types, '
    || (SELECT count(*) FROM tareas) || ' tasks, '
    || (SELECT count(*) FROM admin_users WHERE lower(email) = lower('demo@marfern.dev')) || ' demo admins';
SQL
)"

echo "[reset-demo-dev] OK: ${counts}; admin privado y migraciones conservados"
