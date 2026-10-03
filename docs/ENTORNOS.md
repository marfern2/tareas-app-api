# Entornos Donit

## Matriz

| Entorno | Perfil | API | Admin permitido | PostgreSQL | Imagen |
|---|---|---|---|---|---|
| LOCAL | `local` | `http://localhost:8080` | `http://localhost:4200` | volumen `tareas-postgres-local-data` | build local |
| DEV | `dev` | `https://donit-api-dev.marfern.dev` | `https://admin-dev.marfern.dev` | volumen `tareas-postgres-dev-data` | `<sha40>` |
| PROD | `prod` | `https://donit-api.marfern.dev` | `https://admin-donit.marfern.dev` | volumen `tareas-postgres-data` | `<sha40>` |

Cada entorno usa `.env`, credenciales DB y secretos JWT propios. PostgreSQL DEV/PROD no publica puerto al host.

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

El seed solo se ejecuta con perfil `dev` y `DEV_SEED_ENABLED=true`. Es idempotente y exige passwords inyectados por `.env`; nunca se activa en PROD. La única credencial pública de ejemplo es `demo@marfern.dev` / `admin123`, exclusiva de DEV.

## Migraciones y rollback

Flyway se ejecuta antes de que Hibernate valide el modelo. Si una migracion falla, el contenedor no queda healthy y el despliegue no se marca exitoso. El rollback automatico solo restaura la imagen y `.env`: una migracion ya aplicada no se revierte. Las migraciones deben ser compatibles hacia atras (expandir, desplegar, migrar datos y contraer en una version posterior).

Antes de una migracion delicada se ejecuta un backup PROD validado. No se automatiza ninguna restauracion.

## Ramas e imagenes

`feature/*` abre PR contra `develop`; el merge publica `<sha40>` y el poller DEV despliega esa imagen usando el GitHub Environment `development`. Tras validar, un PR `develop -> master` conserva el ruleset PROD existente; el merge publica el SHA de `master` y PROD usa el Environment `production`. Los aliases `develop`/`master` son conveniencia y nunca son la fuente final del deploy.

Un mismo commit SHA se construye como máximo una vez y su imagen puede arrancar con configuración DEV o PROD. Un merge PR normal de `develop` a `master` suele crear un SHA nuevo; por ello la promoción exacta del digest solo ocurre si ambas ramas apuntan al mismo commit o si se promueve explícitamente un SHA ya publicado.

Los marcadores `.deployed-sha`, `.failed-sha`, locks, logs y rollback viven dentro del directorio de cada entorno y no se comparten.

## Cloudflare (manual)

El túnel usa token y configuración remota. En Zero Trust > Networks > Tunnels > tunnel de `marserver` > Public Hostnames añadir:

1. `donit-api-dev.marfern.dev` -> HTTP `localhost:8082`.
2. `admin-dev.marfern.dev` -> HTTP `localhost:8083`.

No editar ni eliminar los hostnames PROD. Verificar después con `/actuator/health` y `/health`.
