# Seguridad administrativa 2.0

## Permisos y transición

La API administrativa utiliza exclusivamente JWT admin; los JWT Android siguen en la otra cadena de Spring Security. `ROLE_ADMIN` deja de ser una autorización suficiente. Los privilegios se cargan de `admin_permissions` en cada petición, por lo que un cambio de permisos se aplica también a JWT admin ya emitidos. No hay endpoint HTTP para conceder permisos. La concesión se hace mediante un procedimiento operativo de base de datos, con identidad verificada y revisión, nunca mediante parámetros de petición.

| Permiso | Capacidad |
| --- | --- |
| `ADMIN_READ` | Consultar usuarios, tareas y tipos administrativos. |
| `USER_WRITE` | Modificar identidad o estado enabled de usuarios Android. |
| `USER_DELETE` | Borrar usuarios Android y sus recursos y sesiones. |
| `TASK_WRITE` | Crear, editar y borrar tareas y tipos de usuarios Android. |

La migración V5 registra los cuatro permisos para cada cuenta administrativa **que ya exista al migrar**, pues esas cuentas ya tenían acceso completo. Las cuentas creadas después quedan sin permisos por defecto. El seed optativo de DEV otorga explícitamente los cuatro permisos a sus cuentas admin creadas desde cero, conservando su uso actual; su cuenta de demo es exclusiva de DEV y **no** es la futura demo pública. Antes de habilitar el seed en un DEV recién creado, revisar que sus credenciales sigan siendo privadas. Una cuenta nueva en PROD requiere concesión explícita tras su alta. El rollback de imagen conserva las tablas nuevas; la imagen anterior las ignora y sigue aplicando su antigua política de administración.

## Matriz de endpoints

| Método y ruta | Permiso |
| --- | --- |
| `GET /api/admin/users`, `/api/admin/users/{id}`, `/api/admin/users/{id}/tasks`, `/api/admin/users/{id}/task-types`, `/api/admin/tasks`, `/api/admin/tasks/{id}`, `/api/admin/task-types`, `/api/admin/task-types/{id}` | `ADMIN_READ` |
| `PATCH /api/admin/users/{id}`, `/api/admin/users/{id}/enabled` | `USER_WRITE` |
| `DELETE /api/admin/users/{id}` | `USER_DELETE` |
| `POST /api/admin/users/{id}/tasks`, `/api/admin/users/{id}/task-types` | `TASK_WRITE` |
| `PATCH` y `DELETE /api/admin/users/{id}/tasks/{taskId}`, `/api/admin/users/{id}/task-types/{taskTypeId}` | `TASK_WRITE` |
| `POST /api/admin/auth/login`, `/refresh`, `/logout` | Sin JWT previo; sujetos a sus propios límites y validación de credenciales o refresh token. |

La cadena admin deniega cualquier método o ruta nueva que no esté en la matriz. Los servicios de mutación repiten las comprobaciones de permisos para impedir que otra entrada interna omita la autorización HTTP. Un permiso no se puede añadir con un campo del JSON ni una query string.

## Borrados

El seeder DEV aplica la marca de protección únicamente a fixtures cuya procedencia está registrada con `dev_fixture_key`; la [guía de adopción DEV](DEV-FIXTURE-PROTECTION.md) describe las filas anteriores a esa marca.

`usuarios.protected_from_admin_mutation` es `false` por defecto y solo se modifica de forma operativa en la base de datos o por el seeder DEV para fixtures de procedencia demostrable, fuera de la API admin. Cuando es `true`, se rechaza con 409 cualquier edición o borrado administrativo del usuario, sus tareas y sus tipos. La guarda bloquea la fila del usuario durante la transacción para serializar mutaciones admin concurrentes. Las operaciones propias del usuario Android siguen su política existente. Las cuentas `admin_users` están en otra tabla y no son objetivo del endpoint de usuarios. Para usuarios no protegidos, el borrado mantiene la operación anterior: elimina tareas, tipos, refresh tokens Android y el usuario dentro de una transacción. Los JWT Android de acceso ya emitidos se siguen rechazando al no encontrar al usuario; los refresh tokens se eliminan. Los refresh tokens administrativos no están ligados al usuario Android y permanecen.

## Rate limiting administrativo

La cadena admin instala su propio filtro, independiente de la cadena Android. Los valores están centralizados en `application.properties` bajo `app.admin.rate-limit.*` y pueden configurarse por entorno. Ventana de 60 segundos con reposición gradual:

| Cubo | Capacidad por minuto | Clave |
| --- | ---: | --- |
| Login global | 120 | Común a la API admin |
| Login por cuenta | 6 | SHA-256 del email normalizado |
| Refresh/logout global | 240 | Común a la API admin |
| Refresh por token | 12 | SHA-256 del token, solo en memoria |
| Refresh por cuenta | 30 | ID admin, compartido entre tokens rotados |
| Lecturas autenticadas | 240 | ID admin |
| Escrituras autenticadas | 60 | ID admin |
| Borrados autenticados | 12 | ID admin |

Seis intentos de login por cuenta frenan pruebas repetidas de contraseña; 120 globales mantienen margen para varias cuentas legítimas sin dejar el endpoint ilimitado. Doce refresh por token y 30 por cuenta impiden que la rotación del token reinicie toda la cuota; 240 lecturas, 60 escrituras y 12 borrados por administrador separan navegación habitual de automatización intensa. El borrado además exige `USER_DELETE`, protección de recurso y auditoría transaccional; el cubo no es su único control. Las denegaciones devuelven JSON 429 y `Retry-After`: el tiempo hasta reponer la cuota o, si el mapa está lleno, el plazo conservador de inactividad configurado. El mapa se limita a 10 000 claves y elimina cada clave tras 10 minutos de inactividad; cuando está lleno, una identidad nueva recibe 429 sin reiniciar las cuotas existentes. Los cubos viven en memoria y se reinician al reiniciar la API; no constituyen una cuota distribuida entre réplicas. La cuenta y el hash del refresh token evitan usar como identidad una IP tomada de cabeceras manipulables.

El túnel Cloudflare descrito en el repositorio usa `cloudflared` en modo host y entrega HTTP al puerto publicado en `127.0.0.1` del host. DEV y PROD tienen `server.forward-headers-strategy=framework`; por ello `getRemoteAddr()` puede reflejar cabeceras reenviadas. La configuración remota del dashboard no está versionada aquí. La API admin no usa `X-Forwarded-For`, `CF-Connecting-IP` ni `getRemoteAddr()` para identificar clientes. El filtro heredado de Android permanece en su cadena y no se modifica en esta fase. Al revisar su construcción se detectó además que su campo `@Value` no se inyecta porque el filtro se instancia manualmente; su limitación queda inactiva actualmente. Corregirlo requiere una fase coordinada y pruebas de carga de la API Android para evitar un cambio inesperado de comportamiento. Para contar por IP en una fase posterior habrá que validar el peer real y la política de cabeceras del proxy en cada entorno, y probar la configuración contra cabeceras falsas.

## Auditoría

`admin_audit_events` guarda ID admin, operación, tipo e ID de recurso, resultado, fecha UTC y `X-Request-ID` si consta de caracteres seguros. No guarda contraseñas, JWT, refresh tokens, cuerpos de petición ni direcciones de email. La API no ofrece endpoints para modificar ni borrar eventos. Los éxitos de mutación se escriben en la misma transacción de negocio; si falla la auditoría, la mutación se revierte, especialmente el borrado. Un intento fallido de mutación que pasa el filtro de rate limiting se registra en una transacción independiente tras la respuesta de error. Los 429 se rechazan antes de la auditoría para que un atacante no pueda forzar escrituras de auditoría ilimitadas. Si falla el registro de un intento rechazado, la respuesta original prevalece y se registra un error genérico en log, sin datos secretos. Se requiere restringir por separado el acceso de base de datos de operadores y copias de seguridad: el mismo usuario SQL de la aplicación puede leer o alterar la tabla si queda comprometido. No se ha introducido infraestructura de mensajería.

## Amenazas mitigadas y límites

Se impide que una cuenta admin de solo lectura modifique recursos por API directa, que una ruta admin futura quede abierta por accidente, que un usuario marcado como protegido se borre por error y que un cliente falsifique `X-Forwarded-For` para eludir los límites admin. La separación JWT admin/Android se mantiene. No hay cuentas públicas admin ni endpoints públicos nuevos.

Los límites en memoria no protegen de un ataque distribuido ni agregan cuotas entre procesos: un cliente que pueda alternar réplicas obtiene una cuota por réplica. El cubo global de login o el de una cuenta concreta pueden ser agotados por un atacante y causar 429 temporal a administradores legítimos; una defensa perimetral confiable requeriría coordinación con proxy/Cloudflare. La tabla de auditoría comparte la base de datos de negocio: la retención, exportación inmutable y los permisos SQL separados quedan pendientes. La marca de protección se asigna operativamente a los usuarios concretos que la operación decida conservar y, en DEV, el seeder la aplica solo a fixtures con procedencia registrada. Los permisos revocados se aplican a la siguiente petición; una petición que ya atravesó el filtro de autenticación puede terminar con los permisos observados al comenzar. Antes de promocionar, comprobar en DEV el estado del seed admin y el tráfico real del panel para ajustar umbrales si fuese necesario.

## Contrato propuesto para la demo pública

La futura demo de solo lectura debería consumir un proveedor de datos simulados en Angular, respaldado por fixtures estáticos versionados junto al frontend. Así puede ofrecer `users`, `tasks`, `taskTypes` y `metrics` sin credenciales ni consultas a DEV/PROD. Cada entidad debe tener IDs sintéticos estables; tareas y tipos referencian únicamente IDs de usuarios sintéticos. `metrics` contiene totales y agrupaciones derivados de los mismos fixtures. Los métodos del proveedor serán `listUsers`, `getUser`, `listTasks`, `listTaskTypes` y `getMetrics`; no expondrá métodos de escritura. Angular deberá ocultar las acciones de mutación en modo demo y nunca enviar una petición a `/api/admin/**` desde ese modo. No se publica todavía una API pública ni un tercer despliegue. Un proveedor simulado mantiene la forma de los DTO del panel y facilita sustituir datos sintéticos sin riesgo de enrutar una petición a datos reales; endpoints públicos adicionales solo estarían justificados si surgiera una necesidad de servidor verificada.

Contrato de datos propuesto: `users` replica los campos de `AdminUserSummaryDTO` (`id`, `username`, `email`, `taskCount`, `taskTypeCount`); `tasks` replica `AdminTaskSummaryDTO` (`id`, `titulo`, `descripcion`, `fecha`, `completada`, `urgencia`, `usuarioId`, `usuarioUsername`, `usuarioEmail`, `tipoTareaId`, `tipoTareaNombre`, `tipoTareaColor`); `taskTypes` replica `AdminTaskTypeSummaryDTO` (`id`, `nombre`, `descripcion`, `color`, `usuarioId`, `usuarioUsername`, `taskCount`). `metrics` sería `{userCount, taskCount, completedTaskCount, pendingTaskCount, taskTypeCount}`, con enteros calculados sobre esos fixtures. Los IDs usarían números positivos y `fecha` formato ISO `YYYY-MM-DD` para conservar el formato esperado por Angular. Todos los emails serían dominios sintéticos como `example.invalid` y ninguna fila procedería de las bases reales.
