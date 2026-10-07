# Protección de fixtures DEV

## Regla

El seeder se registra solo con perfil `dev` sin `prod` y `DEV_SEED_ENABLED=true`. La clave persistente `usuarios.dev_fixture_key` identifica la procedencia; `username`, email, tipos y tareas no la demuestran. V7 añade una columna nullable y única, sin marcar filas existentes. Las filas que cree el seeder reciben:

| Clave | Política administrativa |
| --- | --- |
| `donit-dev-seed:v1:alex-demo` | Editable para QA (`protected_from_admin_mutation=false`). |
| `donit-dev-seed:v1:sam-demo` | Protegido (`true`). |
| `donit-dev-seed:v1:disabled-demo` | Protegido (`true`); permanece deshabilitado. |

En cada arranque, el seeder comprueba primero las tres claves. Si una clave existe, verifica que el nombre o email original no pertenezca a otra fila. La identidad visible de `alex-demo` puede cambiar durante QA; su clave y su ID siguen identificándolo. Si falta una clave para una fila con nombre o email coincidente, o hay una colisión, **omite todo el seed** y registra un aviso; el backend continúa arrancando. No adopta, protege ni crea usuarios, admins, tipos o tareas en ese arranque. La clave no se expone ni modifica por la API admin. El guard existente devuelve 409 para ediciones y borrados administrativos del usuario protegido, de sus tareas y de sus tipos.

PROD recibe la columna nullable por la migración compartida; ningún usuario queda marcado por V7. El seeder no se registra con perfil `prod`, incluso si se configurase `DEV_SEED_ENABLED=true` por error. No aplicar este procedimiento a PROD.

V7 es expansiva: la columna nullable no cambia los valores existentes y el índice único admite varias filas con `NULL`. En PostgreSQL 17, añadir la columna sin valor por defecto evita reescribir la tabla; la creación normal del índice puede bloquear escrituras durante el escaneo, por lo que se debe mantener el backup y la observación de salud habituales del despliegue PROD. Si se revierte solo la imagen a V6, la base conserva V7: Flyway de la imagen anterior tolera esa migración futura, y el código anterior ignora la columna adicional. El rollback no elimina las marcas ni revierte una adopción manual ya confirmada.

## Adopción de fixtures DEV existentes

Este procedimiento es operativo y posterior al PR; no se ejecuta desde el repositorio. El 7 de octubre de 2026, el DEV desplegado tenía `SPRING_PROFILES_ACTIVE=dev`, `DEV_SEED_ENABLED=true`, Flyway V6 y tres usuarios demo sin protección (IDs 9, 10 y 11). **No hace falta cambiar la configuración del servidor antes del merge:** el primer arranque con V7 migrará la columna y omitirá el seed por falta de claves, manteniendo el backend disponible. Las filas seguirán sin protección hasta su adopción; no se debe presentar la política como activa ni realizar mutaciones administrativas sobre `sam-demo` o `disabled-demo` mientras esté pendiente. Confirmar backup DEV verificado y que la operación apunta a `donit_dev`. V7 deja todas las claves existentes en `NULL`.

1. Consultar en modo lectura `id`, `username`, `email`, `enabled`, `dev_fixture_key` y `protected_from_admin_mutation` de las tres filas candidatas, sus tareas y tipos, y cualquier colisión de username o email. Registrar los IDs concretos.
2. Aportar evidencia independiente de creación por el seeder anterior para **cada ID**: por ejemplo, una comparación de backup anterior y posterior al primer arranque con seed, junto con el registro del despliegue y su configuración. Nombre, email, contraseña desconocida o contenido de tareas por sí solos no prueban procedencia. Si falta evidencia para una fila, detener la adopción y dejarla sin marca. El backend seguirá disponible y omitirá el seed de fixtures; investigar o crear un plan separado para esa cuenta.
3. Con revisión de los IDs y de la evidencia, adoptar mediante una transacción manual limitada a DEV. Sustituir los tres valores `:alex_id`, `:sam_id`, `:disabled_id` por los IDs revisados. Ejecutar las sentencias una a una y exigir exactamente una fila actualizada por cada una antes del `COMMIT`; ante otro resultado, `ROLLBACK` e investigar. No usar filtros solo por nombre/email:

```sql
BEGIN;
SELECT id, username, email, enabled, dev_fixture_key, protected_from_admin_mutation
  FROM usuarios
 WHERE id IN (:alex_id, :sam_id, :disabled_id) FOR UPDATE;

UPDATE usuarios SET dev_fixture_key = 'donit-dev-seed:v1:alex-demo',
                    protected_from_admin_mutation = false
 WHERE id = :alex_id AND username = 'alex-demo'
   AND email = 'alex@example.invalid' AND dev_fixture_key IS NULL;
UPDATE usuarios SET dev_fixture_key = 'donit-dev-seed:v1:sam-demo',
                    protected_from_admin_mutation = true
 WHERE id = :sam_id AND username = 'sam-demo'
   AND email = 'sam@example.invalid' AND dev_fixture_key IS NULL;
UPDATE usuarios SET dev_fixture_key = 'donit-dev-seed:v1:disabled-demo',
                    protected_from_admin_mutation = true
 WHERE id = :disabled_id AND username = 'disabled-demo'
   AND email = 'disabled@example.invalid' AND enabled = false
   AND dev_fixture_key IS NULL;

SELECT id, username, email, enabled, dev_fixture_key, protected_from_admin_mutation
  FROM usuarios WHERE id IN (:alex_id, :sam_id, :disabled_id);
COMMIT;
```

4. Tras adoptar las tres filas, comprobar en la base que las claves y los valores de protección son correctos. El guard administrativo aplica la protección sin reiniciar. En el siguiente arranque normal, el seed ya habilitado reconocerá las claves; otro arranque debe dejar tres fixtures de usuario, cuatro tipos y seis tareas de seed sin duplicados y conservar los hashes de contraseña existentes. El seeder puede añadir tipos o tareas de muestra ausentes; revisar este efecto antes de ese arranque.

## Verificación en DEV

Comprobar con SQL de solo lectura que cada clave aparece exactamente una vez, ligada al ID adoptado, y que la protección es `false/true/true`; `disabled-demo` continúa con `enabled=false`. Comprobar que ningún otro usuario tiene una clave `donit-dev-seed:v1:%` y que el historial Flyway incluye V7. Consultar los tres usuarios por API admin con `ADMIN_READ`. Con una cuenta de prueba que tenga permisos de escritura, comprobar que `alex-demo` admite la edición QA prevista y que los nueve intentos sobre `sam-demo` o `disabled-demo` (edición y estado de usuario, borrado de usuario, creación/edición/borrado de tarea y creación/edición/borrado de tipo) reciben 409 sin cambios de datos y generan eventos `FAILURE`. Usar un entorno y cuenta de prueba autorizados; las verificaciones de mutación no forman parte de este PR.

Si el log indica que se omitió el seed por conflicto de identidad o falta de procedencia, no forzar la clave. Revisar los IDs y la evidencia; el backend puede seguir disponible con `DEV_SEED_ENABLED=true`, pero la protección de las filas sin marca continúa pendiente.
