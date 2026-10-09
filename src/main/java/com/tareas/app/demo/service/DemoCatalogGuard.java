package com.tareas.app.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Shared first lock for demo mutations and fixture restore. */
@Component
@RequiredArgsConstructor
public class DemoCatalogGuard {
    private final JdbcTemplate db;

    public void lock() {
        // V9 creates the singleton. An empty result also keeps pre-V9 in-memory tests usable.
        db.queryForList("SELECT revision FROM demo_catalog_control WHERE id=1 FOR UPDATE");
    }
}
