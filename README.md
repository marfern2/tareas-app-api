# Donit — backend

Donit es una aplicación de gestión de tareas con cliente Android, panel web de administración y API Spring Boot. **Donit v1.0.0 está desplegado en producción.** Este repositorio contiene el backend: identidad, reglas de negocio, persistencia, API de usuarios, API administrativa y catálogo de demostración.

## Arquitectura y tecnologías

- **Android:** cliente Kotlin/Jetpack Compose para la cuenta y las tareas de cada usuario.
- **Admin:** panel Angular para administrar usuarios, tareas, tipos y el catálogo demo, según los permisos del administrador.
- **Backend:** Java 21, Spring Boot 4.0.7, Gradle 9.3.1, Spring Security, Spring Data JPA, PostgreSQL y Flyway. Docker Compose ejecuta la API y PostgreSQL; las imágenes se publican en GHCR.

El backend mantiene separadas la autenticación de usuarios y administradores. Flyway migra el esquema antes de la validación JPA. La versión desplegada en PROD incluye **Flyway V9**, el **manifest demo v1** y las API demo pública y privada operativas.

## Entornos y acceso público

| Entorno | API | Panel Admin |
| --- | --- | --- |
| DEV (`develop`) | https://donit-api-dev.marfern.dev | https://admin-dev.marfern.dev |
| PROD (`master`) | https://donit-api.marfern.dev | https://admin-donit.marfern.dev |

La [demo web pública en PROD](https://admin-donit.marfern.dev/demo) consume la API pública del catálogo. DEV y PROD usan bases de datos, contenedores y configuración separados. Más información en [entornos](docs/ENTORNOS.md).

## API

| Grupo | Función |
| --- | --- |
| `/auth/**` | Registro, inicio de sesión, renovación y cierre de sesión de usuarios. |
| `/usuarios/**` | Perfil del usuario autenticado. |
| `/tareas/**` | Consulta y gestión de tareas propias. |
| `/tipos-tarea/**` | Consulta y gestión de tipos propios. |
| `/api/admin/**` | Autenticación y perfil administrativos; gestión de usuarios, tareas y tipos reales con permisos. |
| `/api/admin/demo/**` | Gestión privada del catálogo demo, publicación y restauración de fixtures. |
| `/api/public/demo/**` | Consultas públicas de usuarios, tipos, tareas y estadísticas demo; solo `GET`. |

Las rutas administrativas de autenticación están bajo `/api/admin/auth/**`. Los detalles de filtros, paginación, permisos y contratos demo están en [Catálogo demo](docs/DEMO-CATALOG.md); los controles de administración real se describen en [Seguridad administrativa](docs/ADMIN-SECURITY-V2.md).

## Demo pública y datos

El catálogo demo vive en tablas propias, sin referencias a `usuarios`, `tareas` ni `tipos_tarea` reales. El manifest v1 versionado contiene **6 usuarios, 12 tipos de tarea y 24 tareas, de las que 8 están completadas**. En PROD se validaron 42 registros fixture, revisión demo 1 y el recuento público 6/12/24/8; los datos reales permanecieron intactos.

Los fixtures **no se cargan al arrancar**. Un administrador con `DEMO_RESTORE` consulta `restore-preview` y solicita el restore explícitamente con `If-Match`. El restore deja los elementos gestionados en `DRAFT`; una operación separada con `DEMO_PUBLISH` controla su visibilidad pública. La API pública solo lee recursos publicados de las tablas demo. El procedimiento y sus límites están en [Catálogo demo](docs/DEMO-CATALOG.md).

## Seguridad

Las contraseñas se almacenan con BCrypt. Spring Security usa JWT y cadenas de autenticación separadas para usuarios y administradores; los permisos administrativos se comprueban por operación. La demo privada distingue `DEMO_READ`, `DEMO_WRITE`, `DEMO_PUBLISH` y `DEMO_RESTORE`, sin herencia entre ellos. Las lecturas de recursos demo privados entregan ETag fuerte; `PATCH`, `DELETE`, cambios de publicación y restore requieren `If-Match` para detectar versiones obsoletas. La persistencia también aplica concurrencia optimista.

La API pública demo acepta únicamente `GET` y no requiere JWT. CORS restringe los orígenes configurados. La administración dispone de límites de peticiones y auditoría de mutaciones; sus alcances y límites están documentados en [Seguridad administrativa](docs/ADMIN-SECURITY-V2.md) y [Catálogo demo](docs/DEMO-CATALOG.md).

## Desarrollo local y pruebas

Se requiere Java 21 para Gradle y Docker Engine/Desktop con Compose v2 para la ejecución local y los tests que usan PostgreSQL mediante Testcontainers.

```bash
cp .env.local.example .env.local
# Sustituir los valores de ejemplo por valores locales propios.
docker compose -f compose.local.yaml --env-file .env.local up --build
curl -fsS http://127.0.0.1:8080/actuator/health
```

La configuración local permanece fuera de Git. Para comprobar el backend:

```bash
./gradlew test
./gradlew build
```

En la verificación de v1.0.0 (11 de octubre de 2026), la suite ejecutó **337 tests: 337 correctos, 0 fallidos y 0 omitidos**. Cubre seguridad, API de usuarios y administración, migraciones Flyway en PostgreSQL, catálogo demo, restore, publicación y concurrencia. El workflow genera un SBOM CycloneDX y aplica el gate OSV antes de publicar la imagen; OWASP Dependency-Check se ejecuta aparte de forma semanal o manual.

## CI/CD y despliegue

El flujo es **`develop` → DEV** y **`master` → PROD**. GitHub Actions ejecuta controles, construye una imagen identificada por el SHA del commit y la publica en GHCR. Un poller systemd por entorno detecta el SHA de su rama y ejecuta el despliegue con Docker. El despliegue verifica salud antes de marcar el SHA como activo. Flyway no revierte migraciones al volver a una imagen anterior. Consultar [CD y rollback](docs/CD-DEPLOY.md) y [recuperación de backups](docs/RECUPERACION-BACKUPS.md).
