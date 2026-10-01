package com.tareas.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("dev-admin-rotation & !prod")
@ConditionalOnProperty(name = "app.admin.rotation.enabled", havingValue = "true")
public class DevAdminCredentialRotationRunner implements ApplicationRunner {

    private final DevAdminCredentialRotationService rotationService;
    private final ConfigurableApplicationContext applicationContext;
    private final String environment;
    private final String username;
    private final String targetEmail;

    public DevAdminCredentialRotationRunner(
            DevAdminCredentialRotationService rotationService,
            ConfigurableApplicationContext applicationContext,
            @Value("${app.admin.rotation.environment:}") String environment,
            @Value("${app.admin.rotation.username:}") String username,
            @Value("${app.admin.rotation.target-email:}") String targetEmail
    ) {
        this.rotationService = rotationService;
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.username = username;
        this.targetEmail = targetEmail;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"development".equals(environment)
                || !"admin-dev".equals(username)
                || !"admin@gmail.com".equalsIgnoreCase(targetEmail)) {
            throw new IllegalStateException("Salvaguardas de rotacion DEV no satisfechas");
        }

        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String password = input.readLine();
        String confirmation = input.readLine();
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("La nueva password DEV debe tener al menos 12 caracteres");
        }
        if (!password.equals(confirmation)) {
            throw new IllegalArgumentException("Las passwords introducidas no coinciden");
        }

        rotationService.rotate(username, targetEmail, password);
        password = null;
        confirmation = null;
        System.out.println("ROTATION_OK: credencial privada DEV actualizada y sesiones antiguas revocadas");
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
