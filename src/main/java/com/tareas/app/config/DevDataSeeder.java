package com.tareas.app.config;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.admin.security.AdminPermission;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.EnumSet;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "app.dev.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final AdminUserRepository adminUsers;
    private final UsuarioRepository usuarios;
    private final TipoTareaRepository tipos;
    private final TareaRepository tareas;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminPassword;
    private final String demoUsername;
    private final String demoEmail;
    private final String demoPassword;
    private final String userPassword;

    public DevDataSeeder(
            AdminUserRepository adminUsers,
            UsuarioRepository usuarios,
            TipoTareaRepository tipos,
            TareaRepository tareas,
            PasswordEncoder passwordEncoder,
            @Value("${app.dev.seed.admin-username:}") String adminUsername,
            @Value("${app.dev.seed.admin-email:}") String adminEmail,
            @Value("${app.dev.seed.admin-password:}") String adminPassword,
            @Value("${app.dev.seed.demo-username:}") String demoUsername,
            @Value("${app.dev.seed.demo-email:}") String demoEmail,
            @Value("${app.dev.seed.demo-password:}") String demoPassword,
            @Value("${app.dev.seed.user-password:}") String userPassword
    ) {
        this.adminUsers = adminUsers;
        this.usuarios = usuarios;
        this.tipos = tipos;
        this.tareas = tareas;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.demoUsername = demoUsername;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
        this.userPassword = userPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        requireSecret("DEV_ADMIN_USERNAME", adminUsername);
        requireSecret("DEV_ADMIN_EMAIL", adminEmail);
        requireSecret("DEV_DEMO_USERNAME", demoUsername);
        requireSecret("DEV_DEMO_EMAIL", demoEmail);
        requireSecret("DEV_DEMO_PASSWORD", demoPassword);
        requireSecret("DEV_USER_PASSWORD", userPassword);

        AdminUser privateAdmin = createAdminIfMissing(adminUsername, adminEmail, adminPassword, "privado");
        AdminUser demoAdmin = createAdminIfMissing(demoUsername, demoEmail, demoPassword, "demo");
        if (Objects.equals(privateAdmin.getId(), demoAdmin.getId())) {
            throw new IllegalStateException("El admin privado y la demo DEV deben ser cuentas distintas");
        }

        Usuario alex = seedUser("alex-demo", "alex@example.invalid", true);
        Usuario sam = seedUser("sam-demo", "sam@example.invalid", true);
        seedUser("disabled-demo", "disabled@example.invalid", false);

        TipoTarea trabajo = seedType(alex, "Trabajo", "Proyectos y seguimiento profesional", "#4F46E5");
        TipoTarea personal = seedType(alex, "Personal", "Organizacion personal ficticia", "#16A34A");
        TipoTarea estudios = seedType(sam, "Estudios", "Aprendizaje y formacion de ejemplo", "#2563EB");
        TipoTarea urgente = seedType(sam, "Urgente", "Elementos prioritarios de demostracion", "#DC2626");

        seedTask(alex, trabajo, "Preparar presentacion semanal",
                "Revisar los indicadores ficticios del proyecto", LocalDate.now().plusDays(2), false, 1);
        seedTask(alex, personal, "Reservar cita de ejemplo",
                "Dato sintetico para mostrar una tarea personal", LocalDate.now().plusDays(7), false, 0);
        seedTask(alex, trabajo, "Publicar informe mensual",
                "Tarea ficticia ya completada", LocalDate.now().minusDays(2), true, 2);
        seedTask(sam, estudios, "Completar modulo de Kotlin",
                "Practicar colecciones y corrutinas", LocalDate.now().plusDays(4), false, 1);
        seedTask(sam, urgente, "Entregar ejercicio de arquitectura",
                "Ejemplo de tarea urgente para la demo", LocalDate.now().plusDays(1), false, 2);
        seedTask(sam, estudios, "Repasar fundamentos de SQL",
                "Actividad ficticia completada", LocalDate.now().minusDays(5), true, 0);

        log.info("DEV seed verificado de forma idempotente (sin mostrar credenciales)");
    }

    private AdminUser createAdminIfMissing(String username, String email, String password, String accountKind) {
        Optional<AdminUser> byEmail = adminUsers.findByEmail(email);
        Optional<AdminUser> byUsername = adminUsers.findByUsername(username);

        if (byEmail.isPresent() && byUsername.isPresent()
                && !Objects.equals(byEmail.get().getId(), byUsername.get().getId())) {
            throw new IllegalStateException(
                    "Conflicto real de identidad del admin " + accountKind
                            + ": email y username pertenecen a cuentas distintas");
        }

        if (byEmail.isPresent()) {
            return byEmail.get();
        }

        if (byUsername.isPresent()) {
            // El username es la identidad estable del admin. Esto permite completar una
            // migracion de email ya aplicada sin recrear ni resetear su password.
            log.info("DEV seed conserva admin {} existente por username; no cambia email ni password", accountKind);
            return byUsername.get();
        }

        requireSecret("DEV_ADMIN_PASSWORD", password);

        return adminUsers.save(AdminUser.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .permissions(EnumSet.allOf(AdminPermission.class))
                .build());
    }

    private Usuario seedUser(String username, String email, boolean enabled) {
        Optional<Usuario> byUsername = usuarios.findByUsername(username);
        Optional<Usuario> byEmail = usuarios.findByEmail(email);
        if (byUsername.isPresent() && byEmail.isPresent()
                && !Objects.equals(byUsername.get().getId(), byEmail.get().getId())) {
            throw new IllegalStateException("Conflicto de identidad en usuario ficticio DEV");
        }
        if (byUsername.isPresent()) {
            if (!email.equals(byUsername.get().getEmail())) {
                throw new IllegalStateException("Username ficticio DEV pertenece a otro email");
            }
            return byUsername.get();
        }
        if (byEmail.isPresent()) {
            throw new IllegalStateException("Email ficticio DEV pertenece a otro username");
        }
        return usuarios.save(
                Usuario.builder()
                        .username(username)
                        .email(email)
                        .password(passwordEncoder.encode(userPassword))
                        .enabled(enabled)
                        .build()
            );
    }

    private TipoTarea seedType(Usuario usuario, String nombre, String descripcion, String color) {
        return tipos.findByNombreIgnoreCaseAndUsuarioEmail(nombre, usuario.getEmail())
                .orElseGet(() -> tipos.save(TipoTarea.builder()
                        .nombre(nombre)
                        .descripcion(descripcion)
                        .color(color)
                        .usuario(usuario)
                        .build()));
    }

    private void seedTask(
            Usuario usuario,
            TipoTarea tipo,
            String titulo,
            String descripcion,
            LocalDate fecha,
            boolean completada,
            int urgencia
    ) {
        boolean taskExists = tareas.findByUsuarioId(usuario.getId()).stream()
                .anyMatch(tarea -> titulo.equals(tarea.getTitulo()));
        if (!taskExists) {
            tareas.save(Tarea.builder()
                    .titulo(titulo)
                    .descripcion(descripcion)
                    .fecha(fecha)
                    .completada(completada)
                    .urgencia(urgencia)
                    .usuario(usuario)
                    .tipoTarea(tipo)
                    .build());
        }
    }

    private static void requireSecret(String name, String value) {
        if (value == null || value.isBlank() || value.startsWith("NO_GUARDAR_")) {
            throw new IllegalStateException(name + " es obligatorio cuando DEV_SEED_ENABLED=true");
        }
    }
}
