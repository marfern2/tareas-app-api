package com.tareas.app.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRateLimitServiceTest {
    private AdminRateLimitService limiter(int maxKeys, long windowMs, long idleMs) {
        AdminRateLimitService service = new AdminRateLimitService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "loginGlobal", 3);
        ReflectionTestUtils.setField(service, "loginAccount", 6);
        ReflectionTestUtils.setField(service, "refreshAccount", 2);
        ReflectionTestUtils.setField(service, "windowMs", windowMs);
        ReflectionTestUtils.setField(service, "idleMs", idleMs);
        ReflectionTestUtils.setField(service, "maxKeys", maxKeys);
        return service;
    }

    @Test
    void anonymousGlobalBucketCoversDistinctAccountsAndRecovers() throws Exception {
        AdminRateLimitService service = limiter(20, 300, 10_000);
        for (int i = 0; i < 3; i++) {
            assertThat(service.allowLoginGlobal().allowed()).isTrue();
            assertThat(service.allowLoginAccount("unknown-" + i + "@example.invalid").allowed()).isTrue();
        }
        var denied = service.allowLoginGlobal();
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
        Thread.sleep(350);
        assertThat(service.allowLoginGlobal().allowed()).isTrue();
    }

    @Test
    void mapIsBoundedAndIdleBucketsExpireWithoutResettingActiveQuota() throws Exception {
        AdminRateLimitService service = limiter(2, 60_000, 80);
        assertThat(service.allowLoginAccount("one@example.invalid").allowed()).isTrue();
        assertThat(service.allowLoginAccount("two@example.invalid").allowed()).isTrue();
        assertThat(service.allowLoginAccount("three@example.invalid").allowed()).isFalse();
        assertThat(service.activeKeys()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, ?> buckets = (Map<String, ?>) ReflectionTestUtils.getField(service, "buckets");
        assertThat(buckets.keySet()).noneMatch(key -> key.contains("example.invalid"));
        Thread.sleep(100);
        service.evictIdle();
        assertThat(service.activeKeys()).isZero();
        assertThat(service.allowLoginAccount("three@example.invalid").allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedOneBucketCapacity() throws Exception {
        AdminRateLimitService service = limiter(20, 60_000, 600_000);
        int requests = 24;
        CyclicBarrier barrier = new CyclicBarrier(requests);
        try (var executor = Executors.newFixedThreadPool(requests)) {
            List<Callable<AdminRateLimitService.Decision>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> {
                    barrier.await();
                    return service.allowLoginAccount("same@example.invalid");
                });
            }
            List<Future<AdminRateLimitService.Decision>> results = executor.invokeAll(calls);
            long admitted = 0;
            for (var future : results) {
                var decision = future.get();
                if (decision.allowed()) admitted++;
                else assertThat(decision.retryAfterSeconds()).isPositive();
            }
            assertThat(admitted).isEqualTo(6);
            assertThat(service.activeKeys()).isEqualTo(1);
        }
    }

    @Test
    void rotatingTokensCannotBypassPerAdminRefreshBucket() {
        AdminRateLimitService service = limiter(20, 60_000, 600_000);
        assertThat(service.allowRefreshAccount(7L).allowed()).isTrue();
        assertThat(service.allowRefreshAccount(7L).allowed()).isTrue();
        assertThat(service.allowRefreshAccount(7L).allowed()).isFalse();
        assertThat(service.allowRefreshAccount(8L).allowed()).isTrue();
    }
}
