-- A disabled account must not regain old sessions if it is enabled again.
-- This also covers account changes made directly in PostgreSQL.
CREATE FUNCTION revoke_disabled_admin_sessions() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.enabled AND NOT NEW.enabled THEN
        UPDATE admin_refresh_tokens
           SET revoked_at = now()
         WHERE admin_user_id = NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_revoke_disabled_admin_sessions
AFTER UPDATE OF enabled ON admin_users
FOR EACH ROW EXECUTE FUNCTION revoke_disabled_admin_sessions();

-- Existing disabled accounts may already have refresh tokens from the old code.
UPDATE admin_refresh_tokens
   SET revoked_at = now()
 WHERE revoked_at IS NULL
   AND admin_user_id IN (SELECT id FROM admin_users WHERE enabled = false);
