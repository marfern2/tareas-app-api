# Donit — API

Donit es una aplicación de gestión de tareas formada por esta API Spring Boot, un panel de administración Angular y una aplicación Android Kotlin/Compose. Este repositorio concentra los datos, las reglas de acceso y los endpoints para usuarios y administradores.

## Stack y arquitectura

- Java 21, Spring Boot, Spring Security, JPA, PostgreSQL y Flyway.
- JWT de acceso y refresh tokens; autenticación de usuarios y de administradores separadas.
- Docker Compose para API y PostgreSQL, imágenes en GHCR y Cloudflare Tunnel para las URLs públicas.
- Spring Boot Actuator en `/actuator/health`; OpenAPI/Swagger cuando se habilita.

La API permite registro, login, perfil y gestión de tareas y tipos de tarea. El panel administra usuarios, tareas y tipos de tarea. Android usa la API de su propio entorno. La base de datos y los secretos pertenecen a cada entorno; Flyway aplica las migraciones al arrancar.

## Entornos

| Entorno | API pública o local | Admin | Puerto API en el host |
|---|---|---|---|
| LOCAL | `http://localhost:8080` | `http://localhost:4200` | `127.0.0.1:8080` |
| DEV | `https://donit-api-dev.marfern.dev` | `https://admin-dev.marfern.dev` | `127.0.0.1:8082` |
| PROD | `https://donit-api.marfern.dev` | `https://admin-donit.marfern.dev` | `127.0.0.1:8080` |

DEV y PROD tienen contenedores, PostgreSQL, redes, volúmenes y secretos separados. Las URLs públicas llegan a los puertos locales mediante Cloudflare Tunnel. Consulta [entornos](docs/ENTORNOS.md) y la [configuración del túnel](infra/cloudflare-tunnel/README.md).

## Ejecución local

Se necesita Docker Engine/Desktop con Compose v2. Desde la raíz del repositorio:

```bash
cp .env.local.example .env.local
# Sustituir todos los valores de ejemplo por secretos locales propios.
docker compose -f compose.local.yaml --env-file .env.local up --build
curl -fsS http://127.0.0.1:8080/actuator/health
```

La API local escucha en `127.0.0.1:8080` y PostgreSQL local en `127.0.0.1:5432`. `.env.local` está ignorado por Git y excluido del contexto Docker. Swagger, si se habilita, está en `/swagger-ui/index.html`.

## Tests

```bash
./gradlew test
```

El workflow de publicación ejecuta tests y un control de dependencias antes de construir la imagen. Los tests usan una clave JWT exclusiva de test.

## CI/CD y despliegue

`feature/*` se integra en `develop` para DEV; después de validar se promueve `develop` a `master` para PROD. GitHub Actions publica `ghcr.io/marfern2/tareas-app-api:<SHA40>`; los aliases de rama no identifican el despliegue. Pollers systemd seleccionan el SHA de la rama, y el deploy sincroniza imagen y artefactos operativos del mismo commit. Tras comprobar salud local y pública escribe `.deployed-sha`; un candidato fallido queda en `.failed-sha`.

El rollback usa imagen y artefactos del SHA anterior. Flyway **no** revierte automáticamente migraciones ya aplicadas. Hay copias de seguridad de PostgreSQL independientes para DEV y PROD. Procedimientos: [CD y rollback](docs/CD-DEPLOY.md), [backups y recuperación PROD](docs/RECUPERACION-BACKUPS.md).

## Seguridad

Las contraseñas se almacenan con BCrypt. La API valida firma, emisor, audiencia y caducidad de JWT; usa secretos distintos para usuario y administrador, y separados entre DEV y PROD. Las rutas protegidas requieren `Authorization: Bearer <token>`. CORS se limita a los orígenes configurados, y el endpoint de salud no revela detalles internos. No versionar `.env` ni secretos.

## Estado

Backend DEV y PROD operativos; CD de ambos entornos operativo. La aplicación Android y el panel Admin también tienen entornos DEV y PROD separados.
