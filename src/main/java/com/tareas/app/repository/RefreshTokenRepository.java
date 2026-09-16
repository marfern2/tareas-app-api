package com.tareas.app.repository;

import com.tareas.app.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashParaActualizar(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :ahora "
            + "OR (rt.revokedAt IS NOT NULL AND rt.revokedAt < :revocadosAntesDe)")
    int eliminarExpiradosORevocados(
            @Param("ahora") LocalDateTime ahora,
            @Param("revocadosAntesDe") LocalDateTime revocadosAntesDe);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :ahora WHERE rt.usuario.id = :usuarioId AND rt.revokedAt IS NULL")
    int revocarTodosPorUsuario(@Param("usuarioId") Long usuarioId, @Param("ahora") LocalDateTime ahora);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.usuario.id = :usuarioId")
    int eliminarPorUsuario(@Param("usuarioId") Long usuarioId);

    List<RefreshToken> findByUsuarioIdAndRevokedAtIsNull(Long usuarioId);
}