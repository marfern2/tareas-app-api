# CD — Continuous Deployment de tareas-app-api

El mismo script opera en dos contextos separados mediante `DEPLOY_ENV`.
Los pollers systemd de DEV y PROD están operativos.

## Flujo automático actual

```
feature/* -> PR develop -> security-scan -> merge
  -> ghcr.io/marfern2/tareas-app-api:<sha40>
  -> poller DEV observa origin/develop y despliega solo tareas-api-dev

develop -> PR master -> security-scan -> merge
  -> ghcr.io/marfern2/tareas-app-api:<sha40>
  -> poller PROD observa origin/master y despliega solo tareas-api
```

`cd-deploy.sh` nunca ejecuta `docker compose down` y nunca toca PostgreSQL,
Cloudflare ni volúmenes. La imagen no contiene configuración de entorno: el
perfil Spring, DB, JWT y CORS se inyectan desde el `.env` del servidor.

Los aliases `develop` y `master` apuntan al digest correspondiente, pero el
poller despliega siempre el tag inmutable SHA completo. Durante el primer
despliegue se publican además tags de transición compatibles con los pollers
ya instalados; no son la identidad canónica de la imagen.

## GitHub Environments y concurrencia

La publicación desde `develop` usa `environment: development` y el grupo
`backend-development`, con cancelación del run anterior para priorizar el SHA
más reciente. `master` usa `environment: production` y
`backend-production`, sin cancelación: una publicación PROD iniciada termina
de forma determinista antes de procesar otra.

## Estados

| Archivo | Contenido | Significado |
|---|---|---|
| `.deployed-sha` | SHA completo | Último SHA desplegado con éxito |
| `.failed-sha` | SHA completo | SHA cuyo deploy falló; no se reintenta automáticamente |

## Exit codes de `cd-deploy.sh`

| Código | Significado |
|---|---|
| `0` | deploy correcto |
| `1` | error operacional antes de modificar el servicio (imagen inexistente, git, pull, lock ocupado) |
| `2` | uso inválido / SHA inválido |
| `3` | deploy falla pero el rollback funciona |
| `4` | deploy falla **y** el rollback falla (CRÍTICO) |

Estados `3` y `4` escriben el SHA en `.failed-sha`. Un SHA fallido **no** se
reintenta automáticamente; un SHA nuevo sí se intenta con normalidad.

## Rollback manual

```bash
ssh marserver
cd /srv/docker/tareas-app-api

# Ver historial de backups de .env y el último SHA desplegado
ls -t backups-antes-deploy/.env-*.bak
cat .deployed-sha

# Volver a un SHA anterior ya publicado (imagen conservada localmente)
DEPLOY_ENV=prod ./scripts/cd-deploy.sh <sha-completo-40hex>

# El script sincroniza imagen y artefactos del SHA solicitado y valida la salud.
```

## Reintento manual de un SHA fallido

```bash
cd /srv/docker/tareas-app-api
DEPLOY_ENV=prod ./scripts/cd-deploy.sh "$(cat .failed-sha)"
```

O, si se quiere volver a intentar el SHA actual de master:

```bash
DEPLOY_ENV=prod ./scripts/cd-deploy.sh "$(git rev-parse origin/master)"
```

## Logs

```bash
tail -n 50 /srv/docker/tareas-app-api/logs/cd-deploy.log
journalctl --user -u tareas-app-cd.service -n 50
```

## Sincronización de ficheros en el servidor

El poller obtiene el SHA candidato de `origin/develop` (DEV) o `origin/master`
(PROD). El deploy hace `git fetch`, comprueba que ese SHA existe como commit
local y extrae los ficheros con `git archive <sha>`; nunca usa la punta mutable
de la rama para elegir el contenido. La imagen y `.deployed-sha` usan el mismo
SHA. En rollback, el compose y los scripts se restauran desde el SHA anterior
antes de recrear `api`.

Se sincronizan solo estos ficheros versionados:

| Entorno | Ficheros |
|---|---|
| DEV | `compose.dev.yaml`, `scripts/cd-deploy.sh`, `scripts/cd-poll.sh`, `scripts/backup-db.sh`, `scripts/monitor-health-dev.sh` |
| PROD | `compose.yaml`, `scripts/cd-deploy.sh`, `scripts/cd-poll.sh`, `scripts/backup-db.sh`, `scripts/monitor-health.sh` |

No se hace `reset --hard` ni `git clean`. `.env`, `logs/`, `backups/`,
`backups-antes-deploy/`, `.deployed-sha`, `.failed-sha` y los demás ficheros
quedan fuera de la sincronización.

## Requisito previo de GHCR

El package `ghcr.io/marfern2/tareas-app-api` debe ser **público** (el servidor
hace `docker manifest inspect` sin autenticación). Verificar tras el primer
push. Si fuera privado, el poller trataría la imagen como "aún no publicada"
indefinidamente.
