package com.tareas.app.admin.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
public class AdminRateLimitService {
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision permit() { return new Decision(true, 0); }
        static Decision deny(long seconds) { return new Decision(false, Math.max(1, seconds)); }
    }

    private record Slot(Bucket bucket, long lastSeenNanos) {
        Slot seenAt(long now) { return new Slot(bucket, now); }
    }

    private final Map<String, Slot> buckets = new HashMap<>();

    @Value("${app.admin.rate-limit.enabled:true}") private boolean enabled;
    @Value("${app.admin.rate-limit.login-global-per-minute:120}") private int loginGlobal;
    @Value("${app.admin.rate-limit.login-account-per-minute:6}") private int loginAccount;
    @Value("${app.admin.rate-limit.refresh-global-per-minute:240}") private int refreshGlobal;
    @Value("${app.admin.rate-limit.refresh-token-per-minute:12}") private int refreshToken;
    @Value("${app.admin.rate-limit.refresh-account-per-minute:30}") private int refreshAccount;
    @Value("${app.admin.rate-limit.read-per-minute:240}") private int read;
    @Value("${app.admin.rate-limit.write-per-minute:60}") private int write;
    @Value("${app.admin.rate-limit.delete-per-minute:12}") private int delete;
    @Value("${app.admin.rate-limit.window-ms:60000}") private long windowMs;
    @Value("${app.admin.rate-limit.idle-ms:600000}") private long idleMs;
    @Value("${app.admin.rate-limit.max-keys:10000}") private int maxKeys;

    @PostConstruct
    void validateConfiguration() {
        if (windowMs <= 0 || idleMs < windowMs || maxKeys < 3
                || loginGlobal <= 0 || loginAccount <= 0 || refreshGlobal <= 0
                || refreshToken <= 0 || refreshAccount <= 0 || read <= 0 || write <= 0 || delete <= 0) {
            throw new IllegalStateException("Configuración de rate limiting administrativo inválida");
        }
    }

    public Decision allowLoginGlobal() { return allow("login:global", loginGlobal); }
    public Decision allowLoginAccount(String email) {
        // Never retain a client-supplied email (or a potentially huge value) as a map key.
        if (email == null || email.length() > 255) return Decision.deny(60);
        return allow("login:account:" + sha256(email), loginAccount);
    }
    public Decision allowRefreshGlobal() { return allow("refresh:global", refreshGlobal); }
    public Decision allowRefreshToken(String fingerprint) { return allow("refresh:token:" + fingerprint, refreshToken); }
    public Decision allowRefreshAccount(Long adminId) { return allow("refresh:account:" + adminId, refreshAccount); }
    public Decision allowRead(Long adminId) { return allow("read:" + adminId, read); }
    public Decision allowWrite(Long adminId) { return allow("write:" + adminId, write); }
    public Decision allowDelete(Long adminId) { return allow("delete:" + adminId, delete); }

    private synchronized Decision allow(String key, int limit) {
        if (!enabled) return Decision.permit();
        long now = System.nanoTime();
        Slot slot = buckets.get(key);
        if (slot == null) {
            if (buckets.size() >= maxKeys) evictIdleAt(now);
            if (buckets.size() >= maxKeys) {
                // Fail closed for new identities; never clear all quotas under pressure.
                return Decision.deny(Math.max(1, (idleMs + 999) / 1000));
            }
            Bucket bucket = Bucket.builder().addLimit(Bandwidth.classic(limit,
                    Refill.greedy(limit, Duration.ofMillis(windowMs)))).build();
            slot = new Slot(bucket, now);
        }
        buckets.put(key, slot.seenAt(now));
        ConsumptionProbe probe = slot.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) return Decision.permit();
        long nanos = probe.getNanosToWaitForRefill();
        return Decision.deny(Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L));
    }

    @Scheduled(fixedRate = 60_000)
    public synchronized void evictIdle() {
        evictIdleAt(System.nanoTime());
    }

    private void evictIdleAt(long now) {
        long idleNanos = Duration.ofMillis(idleMs).toNanos();
        buckets.values().removeIf(slot -> now - slot.lastSeenNanos() >= idleNanos);
    }

    synchronized int activeKeys() { return buckets.size(); }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
