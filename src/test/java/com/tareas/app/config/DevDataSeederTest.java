package com.tareas.app.config;

import com.tareas.app.admin.model.AdminUser;
import com.tareas.app.admin.repository.AdminUserRepository;
import com.tareas.app.model.Tarea;
import com.tareas.app.model.TipoTarea;
import com.tareas.app.model.Usuario;
import com.tareas.app.repository.TareaRepository;
import com.tareas.app.repository.TipoTareaRepository;
import com.tareas.app.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevDataSeederTest {

    private static final String PRIVATE_EMAIL = "admin@gmail.com";
    private static final String DEMO_EMAIL = "demo@marfern.dev";
    private static final String PRIVATE_PASSWORD_FROM_ENV = "private-value-from-test-env";
    private static final String DEMO_PASSWORD_FROM_ENV = "demo-value-from-test-env";
    private static final String USER_PASSWORD_FROM_ENV = "user-value-from-test-env";

    @Test
    void seedEsIdempotenteYNoDuplicaDatos() {
        Fixture fixture = new Fixture();
        DevDataSeeder seeder = fixture.seeder();

        seeder.run(null);
        seeder.run(null);

        assertThat(fixture.adminsByEmail).hasSize(2).containsKeys(PRIVATE_EMAIL, DEMO_EMAIL);
        assertThat(fixture.usersByUsername).hasSize(3);
        assertThat(fixture.usersByUsername.get("alex-demo").getDevFixtureKey())
                .isEqualTo("donit-dev-seed:v1:alex-demo");
        assertThat(fixture.usersByUsername.get("alex-demo").getProtectedFromAdminMutation()).isFalse();
        assertThat(fixture.usersByUsername.get("sam-demo").getProtectedFromAdminMutation()).isTrue();
        assertThat(fixture.usersByUsername.get("disabled-demo").getProtectedFromAdminMutation()).isTrue();
        assertThat(fixture.usersByUsername.get("disabled-demo").getEnabled()).isFalse();
        assertThat(fixture.types).hasSize(4);
        assertThat(fixture.tasks).hasSize(6);
        assertThat(fixture.tasks).extracting(Tarea::getTitulo).doesNotHaveDuplicates();
        verify(fixture.passwordEncoder, times(5)).encode(anyString());
    }

    @Test
    void adminPrivadoExistenteNoCambiaPasswordAlReiniciar() {
        Fixture fixture = new Fixture();
        AdminUser existing = AdminUser.builder()
                .id(42L)
                .username("admin-dev")
                .email(PRIVATE_EMAIL)
                .passwordHash("hash-existente-no-modificable")
                .enabled(true)
                .build();
        fixture.putAdmin(existing);

        fixture.seeder().run(null);

        assertThat(fixture.adminsByEmail.get(PRIVATE_EMAIL).getPasswordHash())
                .isEqualTo("hash-existente-no-modificable");
        verify(fixture.passwordEncoder, times(0)).encode(PRIVATE_PASSWORD_FROM_ENV);
    }

    @Test
    void usernameExistenteConEmailAnteriorSeReconoceSinResetearlo() {
        Fixture fixture = new Fixture();
        AdminUser existing = AdminUser.builder()
                .id(42L)
                .username("admin-dev")
                .email("admin-dev@example.invalid")
                .passwordHash("hash-existente-no-modificable")
                .enabled(true)
                .build();
        fixture.putAdmin(existing);

        fixture.seeder().run(null);

        assertThat(fixture.adminsByEmail.get("admin-dev@example.invalid").getPasswordHash())
                .isEqualTo("hash-existente-no-modificable");
        assertThat(fixture.adminsByEmail).doesNotContainKey(PRIVATE_EMAIL);
        verify(fixture.passwordEncoder, times(1)).encode(DEMO_PASSWORD_FROM_ENV);
    }

    @Test
    void emailYUsernameEnCuentasDistintasFallaSinSobrescribir() {
        Fixture fixture = new Fixture();
        fixture.putAdmin(AdminUser.builder().id(42L).username("admin-dev")
                .email("other-private@example.invalid").passwordHash("hash-a").enabled(true).build());
        fixture.putAdmin(AdminUser.builder().id(43L).username("other-admin")
                .email(PRIVATE_EMAIL).passwordHash("hash-b").enabled(true).build());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> fixture.seeder().run(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicto real de identidad");
        verify(fixture.passwordEncoder, times(0)).encode(PRIVATE_PASSWORD_FROM_ENV);
    }

    @Test
    void demoSeCreaUnaSolaVez() {
        Fixture fixture = new Fixture();
        DevDataSeeder seeder = fixture.seeder();

        seeder.run(null);
        String firstHash = fixture.adminsByEmail.get(DEMO_EMAIL).getPasswordHash();
        seeder.run(null);

        assertThat(fixture.adminsByEmail.values())
                .filteredOn(admin -> DEMO_EMAIL.equals(admin.getEmail()))
                .singleElement()
                .extracting(AdminUser::getPasswordHash)
                .isEqualTo(firstHash);
        verify(fixture.passwordEncoder, times(1)).encode(DEMO_PASSWORD_FROM_ENV);
    }

    @Test
    void usuarioFicticioConUsernameOcupadoPorOtroEmailOmiteSeedSinCrearDatos() {
        Fixture fixture = new Fixture();
        Usuario existing = Usuario.builder().id(80L).username("alex-demo")
                .email("otro@example.invalid").enabled(true).build();
        fixture.usersByUsername.put(existing.getUsername(), existing);
        fixture.usersByEmail.put(existing.getEmail(), existing);

        fixture.seeder().run(null);
        assertThat(fixture.adminsByEmail).isEmpty();
        assertThat(fixture.usersByUsername).hasSize(1);
        assertThat(fixture.types).isEmpty();
        assertThat(fixture.tasks).isEmpty();
    }

    @Test
    void coincidenciaExactaSinMarcaNoSeAdoptaNiSeProtege() {
        Fixture fixture = new Fixture();
        fixture.putUser(Usuario.builder().id(80L).username("alex-demo")
                .email("alex@example.invalid").password("hash-real").enabled(true).build());

        fixture.seeder().run(null);
        assertThat(fixture.adminsByEmail).isEmpty();
        assertThat(fixture.usersByUsername).hasSize(1);
        assertThat(fixture.usersByUsername.get("alex-demo").getDevFixtureKey()).isNull();
        assertThat(fixture.usersByUsername.get("alex-demo").getProtectedFromAdminMutation()).isFalse();
        assertThat(fixture.types).isEmpty();
        assertThat(fixture.tasks).isEmpty();
    }

    @Test
    void samYDisabledExistentesSinMarcaNoSeProtegenAutomaticamente() {
        for (String username : List.of("sam-demo", "disabled-demo")) {
            Fixture fixture = new Fixture();
            Usuario existing = Usuario.builder().id(80L).username(username)
                    .email(username.equals("sam-demo") ? "sam@example.invalid" : "disabled@example.invalid")
                    .password("hash-preexistente").enabled(!username.equals("disabled-demo"))
                    .protectedFromAdminMutation(false).build();
            fixture.putUser(existing);

            fixture.seeder().run(null);
            assertThat(fixture.adminsByEmail).isEmpty();
            assertThat(fixture.usersByUsername).hasSize(1);
            assertThat(existing.getDevFixtureKey()).isNull();
            assertThat(existing.getProtectedFromAdminMutation()).isFalse();
        }
    }

    @Test
    void fixturesAdoptadosConMarcaSeReconocenYSeAplicaPolitica() {
        Fixture fixture = new Fixture();
        fixture.putUser(Usuario.builder().id(80L).username("alex-demo")
                .email("alex@example.invalid").password("hash-alex").enabled(true)
                .devFixtureKey("donit-dev-seed:v1:alex-demo")
                .protectedFromAdminMutation(true).build());
        fixture.putUser(Usuario.builder().id(81L).username("sam-demo")
                .email("sam@example.invalid").password("hash-sam").enabled(true)
                .devFixtureKey("donit-dev-seed:v1:sam-demo")
                .protectedFromAdminMutation(false).build());
        fixture.putUser(Usuario.builder().id(82L).username("disabled-demo")
                .email("disabled@example.invalid").password("hash-disabled").enabled(false)
                .devFixtureKey("donit-dev-seed:v1:disabled-demo")
                .protectedFromAdminMutation(false).build());

        fixture.seeder().run(null);
        fixture.seeder().run(null);

        assertThat(fixture.usersByUsername).hasSize(3);
        assertThat(fixture.usersByUsername.get("alex-demo").getProtectedFromAdminMutation()).isFalse();
        assertThat(fixture.usersByUsername.get("sam-demo").getProtectedFromAdminMutation()).isTrue();
        assertThat(fixture.usersByUsername.get("disabled-demo").getProtectedFromAdminMutation()).isTrue();
        assertThat(fixture.usersByUsername.get("sam-demo").getPassword()).isEqualTo("hash-sam");
        assertThat(fixture.types).hasSize(4);
        assertThat(fixture.tasks).hasSize(6);
        verify(fixture.passwordEncoder, times(2)).encode(anyString());
    }

    @Test
    void marcaDeOtroUsuarioNoAutorizaAdopcionPorNombre() {
        Fixture fixture = new Fixture();
        fixture.putUser(Usuario.builder().id(80L).username("alex-demo")
                .email("alex@example.invalid").password("hash")
                .devFixtureKey("donit-dev-seed:v1:otra-cuenta").build());

        fixture.seeder().run(null);
        assertThat(fixture.usersByUsername.get("alex-demo").getDevFixtureKey())
                .isEqualTo("donit-dev-seed:v1:otra-cuenta");
    }

    @Test
    void marcaPermiteCambiosDeIdentidadDeAlexEnQa() {
        Fixture fixture = new Fixture();
        fixture.putUser(Usuario.builder().id(80L).username("alex-qa")
                .email("alex-qa@example.invalid").password("hash")
                .devFixtureKey("donit-dev-seed:v1:alex-demo").build());

        fixture.seeder().run(null);
        assertThat(fixture.usersByUsername.get("alex-qa").getProtectedFromAdminMutation()).isFalse();
        assertThat(fixture.usersByUsername).doesNotContainKey("alex-demo");
    }

    @Test
    void marcaNoPermiteColisionConNombreOriginal() {
        Fixture fixture = new Fixture();
        fixture.putUser(Usuario.builder().id(80L).username("alex-qa")
                .email("alex-qa@example.invalid").password("hash")
                .devFixtureKey("donit-dev-seed:v1:alex-demo").build());
        fixture.putUser(Usuario.builder().id(81L).username("alex-demo")
                .email("real@example.invalid").password("hash-real").build());

        fixture.seeder().run(null);
        assertThat(fixture.usersByUsername).hasSize(2);
        assertThat(fixture.adminsByEmail).isEmpty();
    }

    @Test
    void adminPrivadoYDemoNoPuedenCompartirCuenta() {
        Fixture fixture = new Fixture();
        AdminUser shared = AdminUser.builder().id(42L).username("demo")
                .email(PRIVATE_EMAIL).passwordHash("hash-existente").enabled(true).build();
        fixture.putAdmin(shared);

        assertThatThrownBy(() -> fixture.seeder().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cuentas distintas");
        assertThat(fixture.usersByUsername).isEmpty();
    }

    @Test
    void seederSoloSeActivaConPerfilDevYFlagExplicita() {
        Profile profile = DevDataSeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = DevDataSeeder.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev & !prod");
        assertThat(property).isNotNull();
        assertThat(property.name()).containsExactly("app.dev.seed.enabled");
        assertThat(property.havingValue()).isEqualTo("true");
        assertThat(property.matchIfMissing()).isFalse();
    }

    @Test
    void condicionDePerfilYFlagNoRegistraSeederEnProd() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(DevDataSeeder.class)
                .withBean(AdminUserRepository.class, () -> mock(AdminUserRepository.class))
                .withBean(UsuarioRepository.class, () -> mock(UsuarioRepository.class))
                .withBean(TipoTareaRepository.class, () -> mock(TipoTareaRepository.class))
                .withBean(TareaRepository.class, () -> mock(TareaRepository.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("app.dev.seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevDataSeeder.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("app.dev.seed.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", "prod"))
                .withPropertyValues("app.dev.seed.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
    }

    private static final class Fixture {
        private final AdminUserRepository adminUsers = mock(AdminUserRepository.class);
        private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
        private final TipoTareaRepository tiposRepository = mock(TipoTareaRepository.class);
        private final TareaRepository tareasRepository = mock(TareaRepository.class);
        private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        private final Map<String, AdminUser> adminsByEmail = new LinkedHashMap<>();
        private final Map<String, AdminUser> adminsByUsername = new LinkedHashMap<>();
        private final Map<String, Usuario> usersByUsername = new LinkedHashMap<>();
        private final Map<String, Usuario> usersByEmail = new LinkedHashMap<>();
        private final Map<String, Usuario> usersByFixtureKey = new LinkedHashMap<>();
        private final List<TipoTarea> types = new ArrayList<>();
        private final List<Tarea> tasks = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong(1);

        private Fixture() {
            when(passwordEncoder.encode(anyString())).thenAnswer(invocation ->
                    "encoded-for-test:" + invocation.getArgument(0, String.class));

            when(adminUsers.findByEmail(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(adminsByEmail.get(invocation.getArgument(0, String.class))));
            when(adminUsers.findByUsername(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(adminsByUsername.get(invocation.getArgument(0, String.class))));
            when(adminUsers.save(any(AdminUser.class))).thenAnswer(invocation -> {
                AdminUser admin = invocation.getArgument(0, AdminUser.class);
                if (admin.getId() == null) {
                    admin.setId(ids.getAndIncrement());
                }
                putAdmin(admin);
                return admin;
            });

            when(usuarios.findByUsername(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(usersByUsername.get(invocation.getArgument(0, String.class))));
            when(usuarios.findByEmail(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(usersByEmail.get(invocation.getArgument(0, String.class))));
            when(usuarios.findByDevFixtureKey(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(usersByFixtureKey.get(invocation.getArgument(0, String.class))));
            when(usuarios.save(any(Usuario.class))).thenAnswer(invocation -> {
                Usuario user = invocation.getArgument(0, Usuario.class);
                if (user.getId() == null) {
                    user.setId(ids.getAndIncrement());
                }
                putUser(user);
                return user;
            });

            when(tiposRepository.findByNombreIgnoreCaseAndUsuarioEmail(anyString(), anyString()))
                    .thenAnswer(invocation -> types.stream()
                            .filter(type -> invocation.getArgument(0, String.class).equalsIgnoreCase(type.getNombre()))
                            .filter(type -> invocation.getArgument(1, String.class).equals(type.getUsuario().getEmail()))
                            .findFirst());
            when(tiposRepository.save(any(TipoTarea.class))).thenAnswer(invocation -> {
                TipoTarea type = invocation.getArgument(0, TipoTarea.class);
                if (type.getId() == null) {
                    type.setId(ids.getAndIncrement());
                }
                types.add(type);
                return type;
            });

            when(tareasRepository.findByUsuarioId(anyLong())).thenAnswer(invocation -> {
                Long userId = invocation.getArgument(0, Long.class);
                return tasks.stream().filter(task -> userId.equals(task.getUsuario().getId())).toList();
            });
            when(tareasRepository.save(any(Tarea.class))).thenAnswer(invocation -> {
                Tarea task = invocation.getArgument(0, Tarea.class);
                if (task.getId() == null) {
                    task.setId(ids.getAndIncrement());
                }
                tasks.add(task);
                return task;
            });
        }

        private DevDataSeeder seeder() {
            return new DevDataSeeder(
                    adminUsers,
                    usuarios,
                    tiposRepository,
                    tareasRepository,
                    passwordEncoder,
                    "admin-dev",
                    PRIVATE_EMAIL,
                    PRIVATE_PASSWORD_FROM_ENV,
                    "demo",
                    DEMO_EMAIL,
                    DEMO_PASSWORD_FROM_ENV,
                    USER_PASSWORD_FROM_ENV
            );
        }

        private void putAdmin(AdminUser admin) {
            adminsByEmail.put(admin.getEmail(), admin);
            adminsByUsername.put(admin.getUsername(), admin);
        }

        private void putUser(Usuario user) {
            usersByUsername.put(user.getUsername(), user);
            usersByEmail.put(user.getEmail(), user);
            if (user.getDevFixtureKey() != null) {
                usersByFixtureKey.put(user.getDevFixtureKey(), user);
            }
        }
    }
}
