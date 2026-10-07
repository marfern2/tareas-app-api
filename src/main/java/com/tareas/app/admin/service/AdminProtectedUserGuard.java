package com.tareas.app.admin.service;

import com.tareas.app.admin.repository.AdminUsuarioRepository;
import com.tareas.app.exception.ResourceConflictException;
import com.tareas.app.exception.ResourceNotFoundException;
import com.tareas.app.model.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminProtectedUserGuard {
    private final AdminUsuarioRepository users;

    /** Serializes admin mutations for one user and checks the current protection flag. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Usuario requireMutable(Long userId) {
        Usuario user = users.findByIdForAdminMutation(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (Boolean.TRUE.equals(user.getProtectedFromAdminMutation())) {
            throw new ResourceConflictException("Usuario protegido contra modificaciones administrativas");
        }
        return user;
    }
}
