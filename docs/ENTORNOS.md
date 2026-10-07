# Entornos Donit

## Matriz

| Entorno | Perfil | API | Admin permitido | PostgreSQL | Imagen |
|---|---|---|---|---|---|
| LOCAL | `local` | `http://localhost:8080` | `http://localhost:4200` | volumen `tareas-postgres-local-data` | build local |
| DEV | `dev` | `https://donit-api-dev.marfern.dev` | `https://admin-dev.marfern.dev` | volumen `tareas-postgres-dev-data` | `<sha40>` |
| PROD | `prod` | `https://donit-api.marfern.dev` | `https://admin-donit.marfern.dev` | volumen `tareas-postgres-data` | `<sha40>` |

LOCAL usa `.env.local`; DEV y PROD usan archivos `.env` distintos en sus directorios de servidor. Cada entorno tiene credenciales de base de datos y secretos JWT propios. DEV y PROD tienen contenedores, redes y volúmenes separados; PostgreSQL no publica puerto al host.

Los servicios Compose son `api` y `postgres`. Sus contenedores son `tareas-api-local`/`tareas-postgres-local` en LOCAL, `tareas-api-dev`/`tareas-postgres-dev` en DEV y `tareas-api`/`tareas-postgres` en PROD.

## Local con PostgreSQL

```bash
cp .env.local.example .env.local
# Generar valores distintos para POSTGRES_PASSWORD, JWT_SECRET y JWT_ADMIN_SECRET.
docker compose -f compose.local.yaml --env-file .env.local up --build
```

Flyway crea una base vacia desde V1. El backend queda en 127.0.0.1:8080 y PostgreSQL local en 127.0.0.1:5432.

## DEV

```bash
docker compose -f compose.dev.yaml --env-file .env up -d
```

El seed solo se ejecuta con perfil `dev` y `DEV_SEED_ENABLED=true`. Es idempotente y exige contraseñas inyectadas por `.env`; nunca se activa en PROD. La cuenta de demostración, si se habilita, es exclusiva de DEV. Con fixtures antiguos sin clave de procedencia, el seeder omite todo el seed y el backend arranca. Seguir la [adopción explícita y verificación DEV](DEV-FIXTURE-PROTECTION.md) para activar la protección.

## Migraciones y rollback

Flyway se ejecuta antes de que Hibernate valide el modelo. Si una migración falla, el contenedor no queda healthy y el despliegue no se marca exitoso. El rollback automático restaura la imagen, los artefactos operativos del SHA previo y `.env`: una migración ya aplicada no se revierte. Las migraciones deben ser compatibles hacia atrás (expandir, desplegar, migrar datos y contraer en una versión posterior).

Antes de una migracion delicada se ejecuta un backup PROD validado. No se automatiza ninguna restauracion.

## Backups PostgreSQL

Los timers systemd ejecutan `scripts/backup-db.sh` en cada entorno. DEV guarda dumps en `/srv/docker/backups/tareas-app-postgres-dev/` con retención de 3 días; PROD usa `/srv/docker/backups/tareas-app-postgres/` con retención de 14 días. El script comprueba el dump con `pg_restore --list`. La [guía de recuperación PROD](RECUPERACION-BACKUPS.md) describe la verificación y los límites de una conmutación manual.

## Ramas e imagenes

`feature/*` abre PR contra `develop`; el merge publica `<sha40>` y el poller DEV despliega esa imagen usando el GitHub Environment `development`. Tras validar, un PR `develop -> master` conserva el ruleset PROD existente; el merge publica el SHA de `master` y PROD usa el Environment `production`. Los aliases `develop`/`master` son conveniencia y nunca son la fuente final del deploy.

Un mismo commit SHA se construye como máximo una vez y su imagen puede arrancar con configuración DEV o PROD. Un merge PR normal de `develop` a `master` suele crear un SHA nuevo; por ello la promoción exacta del digest solo ocurre si ambas ramas apuntan al mismo commit o si se promueve explícitamente un SHA ya publicado.

Los marcadores `.deployed-sha`, `.failed-sha`, locks, logs y rollback viven dentro del directorio de cada entorno y no se comparten.

## Cloudflare Tunnel

El túnel usa token y configuración remota. Los hostnames activos apuntan a:

1. `donit-api-dev.marfern.dev` -> HTTP `localhost:8082`.
2. `admin-dev.marfern.dev` -> HTTP `localhost:8083`.

Los hostnames PROD apuntan a `donit-api.marfern.dev` → `localhost:8080` y `admin-donit.marfern.dev` → `localhost:8081`. Se comprueba la API mediante `/actuator/health` y Admin mediante `/health`.
