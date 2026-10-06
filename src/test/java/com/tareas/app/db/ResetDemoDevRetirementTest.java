package com.tareas.app.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResetDemoDevRetirementTest {
    private static final Path SCRIPT = Path.of("scripts/reset-demo-dev.sh");

    @Test
    void destructiveInvocationFailsBeforeContactingDatabase() throws Exception {
        Process process = new ProcessBuilder("bash", SCRIPT.toString(), "--confirm-reset-dev")
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(new String(process.getInputStream().readAllBytes())).contains("retirado");
    }

    @Test
    void checkFailsClosedOutsideExpectedDevInstallation() throws Exception {
        Process process = new ProcessBuilder("bash", SCRIPT.toString(), "--check")
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(new String(process.getInputStream().readAllBytes())).contains("DEV esperada");
    }

    @Test
    void retiredScriptCannotMutateFixturesPermissionsOrAudit() throws Exception {
        String script = Files.readString(SCRIPT).toUpperCase();
        assertThat(script).doesNotContain("DELETE FROM", "TRUNCATE ", "DROP TABLE", "UPDATE ",
                "INSERT INTO", "DOCKER COMPOSE", "BACKUP-DB.SH");
        assertThat(script).contains("BEGIN TRANSACTION READ ONLY", "PROTECTED_FROM_ADMIN_MUTATION",
                "ADMIN_PERMISSIONS", "ADMIN_AUDIT_EVENTS");
    }
}
