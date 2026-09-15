package com.tareas.app.admin.repository;

import com.tareas.app.admin.model.AdminRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AdminRefreshTokenRepository extends JpaRepository<AdminRefreshToken, Long> {

    Optional<AdminRefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT art FROM AdminRefreshToken art WHERE art.tokenHash = :tokenHash")
    Optional<AdminRefreshToken> findByTokenHashParaActualizar(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("DELETE FROM AdminRefreshToken art WHERE art.expiresAt < :ahora "
            + "OR (art.revokedAt IS NOT NULL AND art.revokedAt < :revocadosAntesDe)")
    int eliminarExpiradosORevocados(
            @Param("ahora") LocalDateTime ahora,
            @Param("revocadosAntesDe") LocalDateTime revocadosAntesDe);
}
