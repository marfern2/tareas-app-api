package com.tareas.app.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

@Entity
@Table(name = "usuarios")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "El username es obligatorio")
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank(message = "El email es obligatorio")
    @Email(message = "El email debe tener formato válido")
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank(message = "La contraseña es obligatoria")
    @Column(nullable = false)
    private String password; // Aqui se guardara la contraseña que va encriptada

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "protected_from_admin_mutation", nullable = false)
    @Builder.Default
    private Boolean protectedFromAdminMutation = false;

    // Solo el seeder DEV (o una adopcion operativa verificada) asigna esta marca.
    @Column(name = "dev_fixture_key", unique = true, length = 80)
    private String devFixtureKey;
}
