// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.Invocation;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheEvictionTest {

    @Test
    void failedTargetDoesNotEvict() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        Invocation invocation = new Invocation() {
            @Override
            public MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return new Object[] {"alice"};
            }

            @Override
            public Object instance() {
                return new Target();
            }

            @Override
            public Future<Object> proceed() {
                return Future.failedFuture("business failure");
            }
        };

        Future<Object> result = new CacheEvictAspect(store, CacheConfig.defaults(), Set.of())
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(invocation);

        assertTrue(result.failed());
        assertEquals(0, store.evictCalls);
        assertEquals(0, store.clearCalls);
    }

    @Test
    void evictionFailurePreservesSuccessfulMutation() throws NoSuchMethodException {
        var store = new CacheTestFixtures.RecordingStore();
        store.failEvictions = true;
        var method = Target.class.getDeclaredMethod("mutate", String.class);
        var metadata = CacheTestFixtures.metadata(method, "user");
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = new CacheEvictAspect(store, CacheConfig.defaults(), Set.of())
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, targetCalls))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("result-1", result);
        assertEquals(1, store.evictCalls);
    }

    static final class Target {
        @CacheEvict(name = "profile", key = "0")
        String mutate(String user) {
            return "unused";
        }
    }
}
