package com.tareas.app.admin.controller;

import com.tareas.app.admin.security.AdminUserDetails;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminMeController {
    public record AdminMeResponse(String username, List<String> permissions) {}

    @GetMapping("/me")
    public ResponseEntity<AdminMeResponse> me(@AuthenticationPrincipal AdminUserDetails principal) {
        List<String> permissions = principal.getAdminUser().getPermissions().stream()
                .map(Enum::name).sorted().toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AdminMeResponse(principal.getAdminUser().getUsername(), permissions));
    }
}
