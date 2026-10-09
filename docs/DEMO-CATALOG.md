# Catálogo demo

V8 crea `demo_users`, `demo_task_types`, `demo_tasks` y `demo_catalog_control` sin
insertar datos ni conceder permisos. V9 añade metadatos y registro de identidad
para restore. Esta rama no se ha desplegado: DEV tiene V8 y PROD sigue sin la pila demo.

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
decisión separada. Esta fase no introduce mutaciones públicas.

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
| `DEMO_RESTORE` | Solo preview y restore de fixtures |

Los permisos no se heredan entre sí. `ADMIN_READ`, `USER_WRITE`, `USER_DELETE` y
`TASK_WRITE` no dan acceso al catálogo demo. La cadena de seguridad y los servicios
comprueban los permisos de forma independiente. Esta fase no asigna permisos DEMO
a ninguna cuenta.

El backoffice privado obtiene las capacidades actuales mediante
`GET /api/admin/me` con `Authorization: Bearer <JWT admin>`. La respuesta contiene
solo `username` y `permissions` (nombres de `AdminPermission`); lleva
`Cache-Control: no-store`. La cadena admin consulta `admin_permissions` en la
base de datos en cada solicitud, también cuando se reutiliza un JWT ya emitido.
Un JWT Android no sirve para esta ruta. Angular debe volver a consultarla tras
iniciar o renovar sesión y cuando necesite actualizar las capacidades mostradas;
la autorización efectiva se decide de nuevo en cada petición a la API.

La API autoriza `DEMO_READ`, `DEMO_WRITE`, `DEMO_PUBLISH` y `DEMO_RESTORE` por
separado. El backoffice interactivo necesita `DEMO_READ` para descubrir y ver
recursos y obtener sus ETag. Por ello, un rol operativo de edición recibe
normalmente `DEMO_READ` + `DEMO_WRITE`, y uno de publicación `DEMO_READ` +
`DEMO_PUBLISH`. `DEMO_WRITE` y `DEMO_PUBLISH` no implican `DEMO_READ`; tampoco
`DEMO_RESTORE` concede las otras capacidades.

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

El CORS administrativo conserva los orígenes configurados para cada entorno.
Permite enviar `Authorization`, `Content-Type` e `If-Match` en preflight y expone
`ETag` y `Retry-After` al navegador. Esto incluye PATCH, DELETE y POST de restore.
El preview de restore y su respuesta también devuelven ETag. Las respuestas
administrativas 429 ya incluyen `Retry-After`; no cambian las cuotas.

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
ni `tareas`. `fixture_key` es opcional para registros personalizados y no se
expone por la API. V8 amplió `ck_admin_permission` para reconocer los cuatro
permisos DEMO, sin concederlos. V1–V8 permanecen inmutables.

La imagen bridge que reconoce DEMO es el rollback mínimo seguro para DEV cuando
V8 ya está aplicada.

## Fixtures versionados y restore

La fuente de verdad es [`src/main/resources/demo/catalog-v1.json`](../src/main/resources/demo/catalog-v1.json).
El manifest tiene versión 1, seis usuarios, doce tipos y veinticuatro tareas
sintéticas. Cada elemento declara `fixtureKey` y `publicId` UUID literales
y estables. El backend valida el manifest al construir el servicio: claves y
UUID únicos, referencias, propietario de cada tipo, fechas, campos obligatorios,
rangos, colores y límites de texto. Un manifest inválido impide arrancar el
servicio; nunca se aplica parcialmente. No hay auto-seeding al arrancar.

V9 crea una fila singleton de control con revisión inicial 0. Es metadato,
no contenido demo. `demo_fixture_registry` registra tipo, clave, UUID e ID SQL
de cada fixture creado por restore. Solo este registro prueba que una fila es
gestionada. Una coincidencia de nombre, handle, clave o UUID sin registro se
presenta como conflicto y no se adopta. Las filas con `fixture_key NULL` y
las claves ajenas siguen siendo personalizadas y no se editan ni se borran.

`GET /api/admin/demo/fixtures/restore-preview` requiere exclusivamente
`DEMO_RESTORE`, es de solo lectura y devuelve revisión actual y objetivo
(actual + 1), versión del manifest, ETag, listas de claves para `create`,
`update`, `unchanged`, `retired` y `conflicts` por recurso, y el número de
filas personalizadas. También informa la versión del manifest previamente
aplicada y conflictos globales; un binario con manifest anterior al ya aplicado
no puede degradar el catálogo. Una fila se clasifica `update` si cualquier campo
gestionado difiere, si está publicada o si debe reactivarse. `retired`
significa que la clave está en el registro pero ya no en el manifest. Se
reportan conflictos por identidad perdida, colisión con filas no registradas
y despublicación que ocultaría hijos personalizados publicados. Preview no
audita por ser una lectura sin mutación; el rate limiter administrativo de
lecturas sigue aplicándose.

`POST /api/admin/demo/fixtures/restore` requiere exclusivamente
`DEMO_RESTORE` e `If-Match` con el ETag exacto del preview. Sin cabecera
responde 428; una revisión o huella obsoleta, 412; conflictos, 409. La huella
incluye las filas demo y el registro, por lo que también detecta cambios
manuales aunque la revisión solo avance con restores. El servicio bloquea la
fila de control, vuelve a calcular el estado y ejecuta todo en una transacción.
Las mutaciones administrativas demo toman el mismo bloqueo antes de escribir.
Dos restores con el mismo ETag se serializan: uno puede completar y el otro
recibe 412. Cada restore exitoso incrementa la revisión exactamente una vez
y actualiza atómicamente `manifest_version`, `last_restored_at` y
`last_restore_id`. La respuesta POST devuelve las listas efectivamente
aplicadas, revisión anterior y nueva, ID de restore y el ETag resultante.

El upsert busca exclusivamente por `fixture_key` registrada. Crea en orden
usuario → tipo → tarea y restaura los campos del manifest. Verifica que el
`publicId` de cada fila registrada siga siendo el UUID estable del manifest;
si cambió, bloquea el restore con conflicto. Las ediciones manuales de campos gestionados
se pierden al restaurar. Los cambios incrementan la versión de la fila. Todos
los fixtures activos quedan `DRAFT` y `published_at=NULL`; repetir un
restore sin diferencias no modifica sus versiones aunque sí avanza la
revisión de catálogo. La API pública solo muestra `PUBLISHED`, así que
no aparecen hasta una publicación administrativa explícita.

La política de retiro conserva la fila y su identidad en el registro:
la pasa a `DRAFT`, la marca inactiva en el registro y no ejecuta `DELETE`.
Si una futura versión vuelve a incluirla, se reactiva con el mismo ID y UUID.
Se procesa en orden inverso tarea → tipo → usuario. Si un hijo personalizado
publicado quedaría oculto al pasar su padre a `DRAFT`, se detiene con 409.
El restore no toca datos reales ni usa `TRUNCATE`. El catálogo demo de QA
existente en DEV, con `fixture_key NULL`, queda fuera del alcance.

El POST consume una sola admisión del rate limiter administrativo de escritura;
el trabajo JDBC interno no hace llamadas REST. Un éxito escribe auditoría en
la misma transacción: actor, `DEMO_FIXTURE_RESTORE`, resultado, revisión
anterior y nueva, conteos create/update/delete/unchanged/retired y
`X-Request-ID` válido o un identificador generado si falta. Un fallo se audita en transacción independiente
cuando la auditoría está disponible. Un fallo de auditoría del éxito provoca
rollback de filas, registro y control. No se guardan cuerpos, manifest,
tokens ni contraseñas.

Limitaciones: la revisión aumenta también en restores idempotentes; el ETag
es una instantánea del estado demo completo y puede caducar por cambios en
filas personalizadas. Cambios SQL externos que eludan el bloqueo de control
no tienen la misma garantía de serialización que la API administrativa.
V9 debe aplicarse antes de habilitar estos endpoints; esta fase no promueve
la pila a PROD ni asigna `DEMO_RESTORE` a cuentas reales.
