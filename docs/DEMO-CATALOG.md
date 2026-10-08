# Catálogo demo

V8 crea `demo_users`, `demo_task_types`, `demo_tasks` y `demo_catalog_control` sin
insertar datos ni conceder permisos. La API administrativa privada usa únicamente
las tres primeras tablas. DEV tiene V8 aplicada; PROD sigue en V7. Fixtures,
restore y permisos reales están pendientes.

## API pública de solo lectura

`/api/public/demo` ofrece `GET /users`, `GET /users/{publicId}`,
`GET /task-types`, `GET /task-types/{publicId}`, `GET /tasks`,
`GET /tasks/{publicId}` y `GET /stats`. La cadena de seguridad pública permite
únicamente GET, sin autenticación; ignora cualquier cabecera `Authorization`.
POST, PUT, PATCH, DELETE y OPTIONS reciben 403. La cadena administrativa y sus
permisos siguen separados. Las respuestas usan `Cache-Control: no-store`; no hay
ETag público porque una lista y sus padres pueden cambiar entre peticiones.

Las respuestas públicas usan DTOs separados en `demo.dto.publicapi`, con nombres
JSON camelCase según la convención actual. `publicId` es la única clave de cada
recurso; las rutas de detalle reciben UUID, no IDs SQL. Los campos son:

| DTO | Campos |
| --- | --- |
| `PublicDemoUser` | `publicId`, `handle`, `displayName`, `bio` |
| `PublicDemoTaskType` | `publicId`, `userPublicId`, `name`, `description`, `color` |
| `PublicDemoTask` | `publicId`, `userPublicId`, `taskTypePublicId`, `title`, `description`, `dueDate`, `completed`, `urgency` |
| `PublicDemoStats` | `users`, `taskTypes`, `tasks`, `completedTasks` |

Las listas devuelven `content`, `page`, `size`, `totalElements`, `totalPages` y
`hasNext`. `page` va de 0 a 49 y `size` de 1 a 20; los valores por defecto son
0 y 20. `sort` acepta `campo`, `campo,asc` o `campo,desc`: usuarios `handle` o
`displayName` (por defecto `handle,asc`), tipos `name` (por defecto `name,asc`),
tareas `dueDate` o `title` (por defecto `dueDate,asc`). Se añade `publicId` ascendente
como desempate estable. Parámetros, rangos y órdenes no permitidos responden 400.
Una publicación concurrente puede cambiar el contenido entre páginas o entre los
recuentos de `stats`; no se promete una instantánea del catálogo completo.

| Lista | Filtros públicos |
| --- | --- |
| Usuarios | `search` sobre `handle` y `displayName` |
| Tipos | `userPublicId`, `search` sobre `name` |
| Tareas | `userPublicId`, `taskTypePublicId`, `completed`, `urgency`, `search` sobre `title` |

`search` se recorta, requiere 2–60 caracteres y compara sin distinguir mayúsculas.
`%` y `_` se tratan como texto literal; las consultas son parametrizadas. Cada
recurso debe estar `PUBLISHED`. Los tipos exigen usuario publicado; las tareas
exigen usuario y tipo publicados y tipo del mismo usuario. Estas condiciones se
aplican también a detalles, recuentos y tareas completadas. No se consultan
tablas reales ni se reutiliza `DemoAdminService`; no se serializan `fixture_key`,
versiones, fechas internas, auditoría ni estado de publicación.

No se ha añadido límite por IP: requiere comprobar antes la cadena
Cloudflare/proxy y no confiar en `X-Forwarded-For` sin esa verificación. CORS
conserva la política actual; un origen para una futura web pública requiere una
decisión separada. Esta fase no introduce fixtures ni mutaciones públicas.

## Endpoints privados

Todos los endpoints viven bajo `/api/admin/demo` y requieren un JWT administrativo.
Un JWT Android no sirve. Los IDs de las rutas son IDs internos de tablas demo.
Las respuestas devuelven DTOs, nunca entidades JPA ni `fixture_key`.

| Recurso | Endpoints |
| --- | --- |
| Usuarios | `GET /users`, `GET /users/{id}`, `POST /users`, `PATCH /users/{id}`, `PATCH /users/{id}/publication` |
| Tipos | `GET /task-types`, `GET /task-types/{id}`, `POST /task-types`, `PATCH /task-types/{id}`, `DELETE /task-types/{id}`, `PATCH /task-types/{id}/publication` |
| Tareas | `GET /tasks`, `GET /tasks/{id}`, `POST /tasks`, `PATCH /tasks/{id}`, `DELETE /tasks/{id}`, `PATCH /tasks/{id}/publication` |
| Estadísticas | `GET /stats` |

`GET /stats` devuelve `usersTotal`, `usersPublished`, `typesTotal`, `typesPublished`,
`tasksTotal`, `tasksPublished` y `tasksCompleted`, exclusivamente desde tablas demo.
No existe `DELETE /users/{id}`.

| Permiso | Operaciones |
| --- | --- |
| `DEMO_READ` | Todos los GET anteriores |
| `DEMO_WRITE` | POST y PATCH de edición; DELETE de tipos y tareas |
| `DEMO_PUBLISH` | Solo PATCH de `/publication` |
| `DEMO_RESTORE` | Reservado, sin endpoint |

Los permisos no se heredan entre sí. `ADMIN_READ`, `USER_WRITE`, `USER_DELETE` y
`TASK_WRITE` no dan acceso al catálogo demo. La cadena de seguridad y los servicios
comprueban los permisos de forma independiente. Esta fase no asigna permisos DEMO
a ninguna cuenta.

## DTOs, listas y validación

`POST` requiere todos los campos obligatorios; `PATCH` admite solo los campos
presentes. Los IDs de relación son obligatorios al crear y no se pueden cambiar
después. Los campos de texto se recortan y rechazan HTML, URLs externas y
caracteres de control. Un campo opcional omitido no se cambia; `null` en un PATCH
tampoco borra su valor.

| Recurso | Restricciones |
| --- | --- |
| Usuario | `handle` normalizado a minúsculas, único, 3–40 caracteres ASCII (`a-z`, `0-9`, `_`, `-`); `displayName` 2–80; `bio` hasta 500 |
| Tipo | `demoUserId` obligatorio; `name` 2–50; `description` hasta 500; `color` `#RRGGBB` |
| Tarea | `demoUserId`, `demoTaskTypeId`, `dueDate` ISO `YYYY-MM-DD`, `completed` y `urgency` obligatorios; `title` 3–100; `description` hasta 500; `urgency` 0–2 |

Las listas usan `page` desde cero y `size` de 1 a 100 (20 por defecto). `sort`
acepta un único campo de la lista cerrada y dirección opcional, por ejemplo
`sort=title,desc`. Se añade `id` ascendente para desempatar. Campos permitidos:

| Lista | Filtros | `sort` permitido |
| --- | --- | --- |
| Usuarios | `search` sobre handle/displayName, `publicationStatus` | `id`, `handle`, `displayName`, `createdAt`, `updatedAt`, `publicationStatus` |
| Tipos | `demoUserId`, `search` sobre name, `publicationStatus` | `id`, `name`, `createdAt`, `updatedAt`, `publicationStatus` |
| Tareas | `demoUserId`, `demoTaskTypeId`, `completed`, `urgency`, `publicationStatus`, `search` sobre title | `id`, `title`, `dueDate`, `urgency`, `completed`, `createdAt`, `updatedAt`, `publicationStatus` |

La búsqueda ignora mayúsculas y trata `%` y `_` como texto literal.

## Publicación y concurrencia

`PATCH .../publication` recibe `{"publicationStatus":"PUBLISHED"}` o
`{"publicationStatus":"DRAFT"}`. Publicar un tipo exige que su usuario esté
publicado. Publicar una tarea exige usuario y tipo publicados y que el tipo
pertenezca a ese usuario. Despublicar un usuario se bloquea si alguno de sus
tipos o tareas está publicado; despublicar un tipo se bloquea si tiene tareas
publicadas. Borrar un tipo con tareas asociadas responde 409.

Cada lectura individual y respuesta de creación o mutación devuelve un ETag fuerte
de formato `"vN"`, donde `N` es el campo `version` del recurso. Cada PATCH,
DELETE o cambio de publicación requiere `If-Match: "vN"`. Falta de cabecera
responde 428, formato incorrecto 400 y versión obsoleta 412. Los servicios bloquean
en orden usuario, tipo y tarea dentro de transacciones para serializar cambios
de relaciones y publicación; la columna `@Version` también protege las escrituras.
El orden de bloqueo evita que se publique una tarea mientras se despublica su
tipo o usuario. Los GET de listas y estadísticas no representan una instantánea
atómica de todo el catálogo.

## Errores y auditoría

La API devuelve 400 para datos inválidos, 401 sin autenticación administrativa,
403 sin el permiso DEMO específico, 404 si falta un recurso, 409 para conflictos
de unicidad o relación, 412 para ETag obsoleto y 428 si falta `If-Match`.
Las respuestas siguen el formato del `GlobalExceptionHandler` existente.

Cada mutación correcta escribe `admin_audit_events` en la misma transacción.
Las solicitudes de mutación fallidas se auditan en una transacción independiente.
Se guardan ID del actor administrativo cuando existe, operación, tipo e ID del
recurso, resultado, fecha y un `X-Request-ID` válido cuando se envía. No se
guardan cuerpos, tokens ni contenido de los campos demo.

## Persistencia y compatibilidad

La FK compuesta `(demo_task_type_id, demo_user_id)` de `demo_tasks` impide asociar
tipos de otro usuario. Las tablas demo no referencian `usuarios`, `tipos_tarea`
ni `tareas`. `fixture_key` permanece opcional para una fase posterior y no se
expone por la API. V8 amplió `ck_admin_permission` para reconocer los cuatro
permisos DEMO, sin concederlos. V1–V8 permanecen inmutables.

La imagen bridge que reconoce DEMO es el rollback mínimo seguro para DEV cuando
V8 ya está aplicada. El restore y los fixtures requieren una fase posterior.
