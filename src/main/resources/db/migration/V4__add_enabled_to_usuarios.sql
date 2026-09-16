-- =============================================================================
-- V4__add_enabled_to_usuarios.sql
--
-- Anhade columna enabled a la tabla usuarios para soporte de
-- activar/deshabilitar usuario desde el panel admin.
--
-- Reglas del proyecto:
--   - V4 es inmutable una vez publicada (Flyway comprueba checksum).
--   - Se aplica SOLO sobre un schema con V1+V2+V3 ya aplicadas.
--   - Default true: usuarios existentes siguen activos.
-- =============================================================================

ALTER TABLE usuarios ADD COLUMN enabled boolean NOT NULL DEFAULT true;
