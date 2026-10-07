# Catálogo demo: versión puente de permisos

## Alcance

Esta versión amplía únicamente `AdminPermission` con `DEMO_READ`, `DEMO_WRITE`,
`DEMO_PUBLISH` y `DEMO_RESTORE`. `AdminUser` almacena el enum como texto en
`admin_permissions`; `AdminUserDetails` convierte cada valor a una autoridad con
el mismo nombre. El JWT administrativo identifica al administrador y las
autoridades se vuelven a leer de la base de datos en cada solicitud. No hay
jerarquía de permisos ni rutas demo en esta fase.

La versión puente debe desplegarse **antes** de que exista cualquier fila DEMO.
Así, cuando una versión futura amplíe el esquema e inserte esos valores, esta
imagen ya podrá leerlos si hay que revertir la aplicación.

## Estado de PostgreSQL

V5 contiene `ck_admin_permission`, que admite solo `ADMIN_READ`, `USER_WRITE`,
`USER_DELETE` y `TASK_WRITE`. V1–V7 permanecen inmutables; esta fase no añade V8
ni cambia el CHECK. Por tanto, los permisos DEMO todavía **no se pueden
persistir** en las bases DEV V7 ni PROD V4→V7. Las pruebas simulan la ampliación
futura del CHECK solo en un contenedor PostgreSQL desechable.

`DevDataSeeder` enumera explícitamente los cuatro permisos administrativos
históricos al crear cuentas DEV. Su comportamiento anterior se conserva sin
conceder ninguno de los permisos DEMO, incluso tras la futura ampliación del
CHECK. Las cuentas ya existentes no se modifican.

## Secuencia de despliegue

1. Desplegar la imagen puente, sin filas DEMO.
2. Validar DEV con Flyway V7 y la autenticación administrativa existente.
3. Promover la misma imagen puente a PROD. PROD parte de V4: las migraciones
   existentes V5–V7 deben aplicarse y validarse antes de introducir V8. V5
   conserva los accesos anteriores al copiar los cuatro permisos existentes a
   administradores previos; no concede permisos DEMO.
4. Introducir V8 mediante una migración expansiva que permita los cuatro
   valores DEMO, conservando los valores actuales y las filas existentes.
5. Desplegar después la API demo y definir por separado la concesión explícita
   de permisos. Esta versión puente no expone rutas demo.

## Rollback

El rollback de la aplicación no revierte Flyway ni elimina filas. Antes de V8,
la imagen anterior puede leer las filas antiguas. Después de que se persista
**cualquier** permiso DEMO, la imagen anterior a esta versión puente deja de
ser un rollback seguro: su enum no reconoce esos textos y la carga JPA del
administrador falla. Mantener disponible la imagen puente como versión mínima
de rollback mientras existan filas DEMO. Si una API demo futura escribe datos,
revertir a la imagen puente dejará esas rutas inaccesibles, pero conservará las
filas y permitirá autenticar administradores con permisos DEMO.

## Comprobaciones pendientes para V8

- Diseñar y probar la ampliación del CHECK sin alterar V5 ni perder permisos
  existentes; verificar la secuencia DEV V7→V8 y PROD V4→V8.
- Probar Flyway y Hibernate con la **migración V8 real**. La prueba actual solo
  simula su esquema final y mantiene el historial de Flyway en V7.
- Mantener la concesión de permisos como operación explícita y auditable;
  revisar que ninguna ruta sobre `usuarios`, `tareas` o `tipos_tarea` acepte
  autoridades DEMO.
