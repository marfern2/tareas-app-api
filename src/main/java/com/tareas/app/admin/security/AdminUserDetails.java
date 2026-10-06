package com.tareas.app.admin.security;

import com.tareas.app.admin.model.AdminUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public class AdminUserDetails implements UserDetails {

    private final AdminUser adminUser;

    public AdminUserDetails(AdminUser adminUser) {
        this.adminUser = adminUser;
    }

    public AdminUser getAdminUser() {
        return adminUser;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return adminUser.getPermissions().stream()
                .map(permission -> new SimpleGrantedAuthority(permission.name()))
                .toList();
    }

    @Override
    public String getPassword() {
        return adminUser.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return adminUser.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return adminUser.getEnabled();
    }
}
