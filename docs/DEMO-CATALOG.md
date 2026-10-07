# Catálogo demo: persistencia V8

V8 prepara el almacenamiento de un catálogo público independiente. Todavía no hay API,
servicios de publicación, fixtures, restore ni concesión de permisos DEMO. La migración
no copia datos reales ni inserta filas en ninguna tabla demo.

## Esquema

| Tabla | Identidad y relaciones | Estado |
| --- | --- | --- |
| `demo_users` | `id` interno, `public_id` UUID único, `handle` normalizado y único | `DRAFT`/`PUBLISHED` |
| `demo_task_types` | `id`, `public_id`; `demo_user_id` → `demo_users` | `DRAFT`/`PUBLISHED` |
| `demo_tasks` | `id`, `public_id`; `demo_user_id` → `demo_users`; (`demo_task_type_id`, `demo_user_id`) → `demo_task_types` | `DRAFT`/`PUBLISHED` |
| `demo_catalog_control` | fila singleton opcional (`id = 1`), `revision` con bloqueo optimista | inicialmente vacía |

La FK compuesta impide que una tarea use un tipo perteneciente a otro demo user,
incluso si se escribe SQL directamente. Ninguna tabla demo referencia `usuarios`,
`tipos_tarea`, `tareas` o tablas administrativas, y ninguna tabla real referencia
las tablas demo. Las entidades JPA viven en `com.tareas.app.demo`; las referencias
entre ellas son IDs escalares, validados por PostgreSQL.

`fixture_key` es opcional y único cuando existe, por tabla. `public_id` es único y
obligatorio. Los estados se guardan como texto con `CHECK`; `PUBLISHED` requiere
`published_at`. El `handle` admite solo minúsculas ASCII, dígitos, `_` y `-`,
comienza por letra o dígito y mide de 3 a 40 caracteres. Los campos `version` y
`revision` se usan con `@Version`. `created_at` y `updated_at` usan `timestamptz`.
No hay email, contraseña ni credenciales en el catálogo.

Los índices únicos cubren `public_id`, `handle` y `fixture_key`. Los índices de
lectura cubren `(publication_status, handle)` para usuarios,
`(demo_user_id, publication_status, name)` para tipos y
`(demo_user_id, publication_status, due_date, id)` para tareas. Un índice en
`(demo_task_type_id, demo_user_id)` apoya la FK compuesta y consultas por tipo.
`completed` y `urgency` se filtran después de acotar por usuario y estado;
se evitaron índices adicionales hasta tener consultas y cardinalidad reales.

## Compatibilidad

V8 amplía `ck_admin_permission` para aceptar los cuatro valores DEMO y conserva
los cuatro permisos anteriores. No concede ninguno. V1–V7 permanecen inmutables.
La migración se prueba desde una base vacía, V4 y V7, con PostgreSQL y
`hibernate.ddl-auto=validate`.

La imagen/código bridge que ya reconoce `DEMO_READ`, `DEMO_WRITE`, `DEMO_PUBLISH`
y `DEMO_RESTORE` es el rollback mínimo seguro. Flyway del bridge tolera V8
aplicada y Hibernate ignora las tablas adicionales. Una versión anterior al
bridge deja de ser segura en cuanto exista cualquier fila DEMO en
`admin_permissions`, porque su enum no reconoce esos valores. El rollback no
revierte V8 ni elimina datos.

## Siguiente fase

Definir operaciones de lectura y escritura del catálogo, transiciones de
publicación y autorización DEMO. El restore y los fixtures requieren una fase
posterior con reglas y auditoría explícitas.
