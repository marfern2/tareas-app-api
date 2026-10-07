#!/usr/bin/env bash
set -Eeuo pipefail

# Retired: the existing schema has no immutable marker proving which accounts,
# tasks and types belong to the seed. Names and email addresses are insufficient.
# This command is intentionally read-only until a provenance-aware maintenance
# procedure is designed and reviewed.

if [[ $# -ne 1 || "$1" != "--check" ]]; then
  echo "ERROR: el reset demo DEV esta retirado; solo se admite --check (sin mutaciones)" >&2
  exit 2
fi

EXPECTED_DIR="/srv/docker/tareas-app-api-dev"
DB_CONTAINER="tareas-postgres-dev"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd -- "${script_dir}/.." && pwd -P)"
if [[ "${project_dir}" != "${EXPECTED_DIR}" ]]; then
  echo "ERROR: solo se permite comprobar la instalacion DEV esperada" >&2
  exit 1
fi

cd "${project_dir}"
if [[ ! -f .env || "$(stat -c '%a' .env)" != "600" ]]; then
  echo "ERROR: falta .env o sus permisos no son 600" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ "${POSTGRES_DB:-}" != "donit_dev" || "${DEV_SEED_ENABLED:-}" != "true" ]]; then
  echo "ERROR: las salvaguardas DEV no coinciden" >&2
  exit 1
fi
command -v docker >/dev/null 2>&1 || { echo "ERROR: falta docker" >&2; exit 1; }

actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${DB_CONTAINER}")"
actual_service="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "${DB_CONTAINER}")"
if [[ "${actual_project}" != "tareas-app-api-dev" || "${actual_service}" != "postgres" ]]; then
  echo "ERROR: el contenedor no pertenece al proyecto DEV esperado" >&2
  exit 1
fi

docker exec -i "${DB_CONTAINER}" sh -c \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
BEGIN TRANSACTION READ ONLY;
DO $$
BEGIN
    IF current_database() <> 'donit_dev' THEN
        RAISE EXCEPTION 'Base de datos inesperada';
    END IF;
    IF to_regclass('public.admin_permissions') IS NULL
       OR to_regclass('public.admin_audit_events') IS NULL
       OR to_regclass('public.usuarios') IS NULL THEN
        RAISE EXCEPTION 'Esquema DEV inesperado';
    END IF;
END $$;
SELECT 'Usuarios de nombre/email demo: ' || count(*)
  FROM usuarios
 WHERE (username, email) IN (('alex-demo', 'alex@example.invalid'),
                             ('sam-demo', 'sam@example.invalid'),
                             ('disabled-demo', 'disabled@example.invalid'));
SELECT 'Usuarios demo protegidos: ' || count(*)
  FROM usuarios
 WHERE protected_from_admin_mutation
   AND (username, email) IN (('alex-demo', 'alex@example.invalid'),
                              ('sam-demo', 'sam@example.invalid'),
                              ('disabled-demo', 'disabled@example.invalid'));
SELECT 'Administradores demo por nombre/email: ' || count(*)
  FROM admin_users WHERE username = 'demo' AND lower(email) = 'demo@marfern.dev';
SELECT 'Permisos administrativos: ' || count(*) FROM admin_permissions;
SELECT 'Eventos de auditoria (solo lectura): ' || count(*) FROM admin_audit_events;
COMMIT;
SQL

echo "[reset-demo-dev] Comprobacion terminada. No se ha modificado nada; el reset permanece retirado."
