# Activación final de DEV en marserver

> Guía histórica de puesta en marcha. DEV, sus hostnames y el CD ya están operativos. Para el funcionamiento actual, consultar [CD-DEPLOY.md](CD-DEPLOY.md) y [ENTORNOS.md](ENTORNOS.md).

El bootstrap puede ejecutar imágenes construidas localmente para revisión. El CD no debe activarse hasta que los cambios estén en `develop`, GHCR contenga el tag inmutable `<sha40>` y Cloudflare resuelva ambos hostnames.

## Cloudflare Dashboard

En Zero Trust > Networks > Tunnels > túnel de `marserver` > Public Hostnames:

1. Añadir `donit-api-dev.marfern.dev`, servicio HTTP `localhost:8082`.
2. Añadir `admin-dev.marfern.dev`, servicio HTTP `localhost:8083`.
3. No editar `donit-api.marfern.dev` ni `admin-donit.marfern.dev`.

Verificar:

```bash
curl -fsS https://donit-api-dev.marfern.dev/actuator/health
curl -fsS https://admin-dev.marfern.dev/health
```

## Unidades globales (requieren sudo interactivo)

```bash
sudo install -m 0644 /srv/docker/tareas-app-api-dev/infra/systemd/tareas-app-dev-{cd,monitor}.{service,timer} /etc/systemd/system/
sudo install -m 0644 /srv/docker/tareas-app-api-dev/infra/systemd/tareas-app-dev-db-backup.{service,timer} /etc/systemd/system/
sudo install -m 0644 /srv/docker/admin-donit-dev/infra/systemd/admin-donit-dev-cd.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload

# Ejecutar solo tras DNS + imágenes GHCR + rama develop:
sudo systemctl enable --now tareas-app-dev-cd.timer
sudo systemctl enable --now tareas-app-dev-monitor.timer
sudo systemctl enable --now tareas-app-dev-db-backup.timer
sudo systemctl enable --now admin-donit-dev-cd.timer
```

El backup DEV es una unidad systemd independiente con retención de 3 días. PROD conserva su backup diario validado y 14 días de retención.

## GitHub

Crear los Environments `development` y `production`, sin copiar secretos runtime del servidor. Crear `develop` desde el `master` revisado. En los tres repos, proteger `develop` con PR y el check correspondiente. No modificar el ruleset `Protect Master` del backend; después de añadir los workflows, mantener como requerido `security-scan` para backend y añadir los checks de admin/Android en sus propios repos.
