-- Expand-only: las filas existentes quedan sin marca hasta una adopcion explicita.
ALTER TABLE usuarios ADD COLUMN dev_fixture_key varchar(80);
CREATE UNIQUE INDEX uk_usuarios_dev_fixture_key ON usuarios(dev_fixture_key);
