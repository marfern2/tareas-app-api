package com.tareas.app.admin.controller;

import com.tareas.app.admin.dto.AdminLoginDTO;
import com.tareas.app.admin.dto.AdminLoginResponseDTO;
import com.tareas.app.admin.dto.AdminRefreshRequestDTO;
import com.tareas.app.admin.dto.AdminRefreshResponseDTO;
import com.tareas.app.admin.service.AdminAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponseDTO> login(@Valid @RequestBody AdminLoginDTO loginDTO) {
        AdminLoginResponseDTO response = adminAuthService.login(loginDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AdminRefreshResponseDTO> refresh(@Valid @RequestBody AdminRefreshRequestDTO refreshRequestDTO) {
        AdminRefreshResponseDTO response = adminAuthService.refrescar(refreshRequestDTO.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AdminRefreshRequestDTO refreshRequestDTO) {
        adminAuthService.cerrarSesion(refreshRequestDTO.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
