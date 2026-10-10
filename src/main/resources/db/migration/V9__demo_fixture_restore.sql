ALTER TABLE demo_catalog_control
    ADD COLUMN manifest_version integer,
    ADD COLUMN last_restored_at timestamp with time zone,
    ADD COLUMN last_restore_id uuid;

-- A control row is metadata, not fixture data. No catalog rows are seeded at startup.
INSERT INTO demo_catalog_control (id, revision) VALUES (1, 0);

CREATE TABLE demo_fixture_registry (
    kind varchar(16) NOT NULL,
    fixture_key varchar(80) NOT NULL,
    public_id uuid NOT NULL,
    row_id bigint NOT NULL,
    active boolean NOT NULL DEFAULT true,
    PRIMARY KEY (kind, fixture_key),
    CONSTRAINT uk_demo_fixture_registry_row UNIQUE (kind, row_id),
    CONSTRAINT uk_demo_fixture_registry_public UNIQUE (kind, public_id),
    CONSTRAINT ck_demo_fixture_registry_kind CHECK (kind IN ('USER', 'TYPE', 'TASK'))
);

ALTER TABLE admin_audit_events
    ADD COLUMN previous_revision bigint,
    ADD COLUMN new_revision bigint,
    ADD COLUMN restore_counts varchar(160);
