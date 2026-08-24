// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RedisTestFixtures {
    static final CacheRegion REGION = new CacheRegion("cache", "profiles", 1);
    static final CacheKey KEY = new CacheKey(REGION, "NONE", "alice");
    static final CacheRedisConfig REDIS_CONFIG = new CacheRedisConfig("primary", "it", 1);

    private RedisTestFixtures() {}

    static CacheConfig cacheConfig() {
        return new CacheConfig(true, CacheMode.CLUSTERED, 60, 86_400, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
    }

    static CacheConfig cacheConfig(String profile) {
        return new CacheConfig(
                true,
                CacheMode.CLUSTERED,
                60,
                86_400,
                profile,
                1_024,
                1_048_576,
                10_000,
                100,
                Map.of(
                        REGION.name(),
                        new dev.vertique.cache.config.CacheEntryConfig(CacheMode.CLUSTERED, -1, profile)));
    }

    static RedisCacheStore store(
            RedisCommandClient commands,
            CacheConfig config,
            JsonMapperProfileRegistry profiles,
            RedisDeadlineBoundary deadline) {
        return new RedisCacheStore(commands, REDIS_CONFIG, config, profiles, deadline);
    }

    static JsonMapperProfileRegistry profiles(String id, ObjectMapper mapper) {
        JsonProfileId profileId = JsonProfileId.of(id);
        JsonMapperProfile profile = new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return profileId;
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }
        };
        return new JsonMapperProfileRegistry() {
            @Override
            public ObjectMapper mapper(JsonProfileId requested) {
                return profile(requested).mapper();
            }

            @Override
            public JsonMapperProfile profile(JsonProfileId requested) {
                if (!profileId.equals(requested)) {
                    throw new IllegalArgumentException("unknown test JSON profile: " + requested);
                }
                return profile;
            }

            @Override
            public Set<JsonProfileId> profileIds() {
                return Set.of(profileId);
            }
        };
    }

    static Response response(String value) {
        if (value == null) {
            return null;
        }
        Response response = mock(Response.class);
        when(response.toString()).thenReturn(value);
        return response;
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static final class ImmediateDeadline implements RedisDeadlineBoundary {
        @Override
        public <T> Future<T> withDeadline(Future<T> backend, Duration deadline) {
            return backend;
        }
    }

    static final class TimeoutDeadline implements RedisDeadlineBoundary {
        private final List<Future<?>> backends = new ArrayList<>();
        private final Duration expectedDeadline;

        TimeoutDeadline(Duration expectedDeadline) {
            this.expectedDeadline = expectedDeadline;
        }

        @Override
        public <T> Future<T> withDeadline(Future<T> backend, Duration deadline) {
            if (!expectedDeadline.equals(deadline)) {
                return Future.failedFuture("unexpected deadline: " + deadline);
            }
            backends.add(backend);
            return Future.failedFuture(new java.util.concurrent.TimeoutException("test timeout"));
        }

        List<Future<?>> backends() {
            return backends;
        }
    }

    static class InMemoryRedisCommandClient implements RedisCommandClient {
        final Map<String, String> values = new HashMap<>();
        final List<List<String>> setCommands = new ArrayList<>();
        final List<List<String>> delCommands = new ArrayList<>();

        @Override
        public Future<Response> get(String key) {
            return Future.succeededFuture(response(values.get(key)));
        }

        @Override
        public Future<Response> set(List<String> command) {
            setCommands.add(List.copyOf(command));
            String key = command.get(0);
            if (command.contains("NX") && values.containsKey(key)) {
                return Future.succeededFuture(null);
            }
            values.put(key, command.get(1));
            return Future.succeededFuture(response("OK"));
        }

        @Override
        public Future<Response> del(List<String> command) {
            delCommands.add(List.copyOf(command));
            command.forEach(values::remove);
            return Future.succeededFuture(response("1"));
        }
    }

    static final class ControllableRedisCommandClient extends InMemoryRedisCommandClient {
        private boolean blockEntryReads;
        private Promise<Response> blockedEntryRead;
        private CountDownLatch entryReadStarted;
        private Promise<Response> pendingDelete;

        void blockEntryReads() {
            blockEntryReads = true;
            entryReadStarted = new CountDownLatch(1);
        }

        boolean awaitEntryReadStarted() throws InterruptedException {
            return entryReadStarted.await(2, TimeUnit.SECONDS);
        }

        void releaseEntryRead() {
            blockEntryReads = false;
            blockedEntryRead.tryComplete(response(values.get(blockedEntryReadKey)));
        }

        private String blockedEntryReadKey;

        void blockNextDelete() {
            pendingDelete = Promise.promise();
        }

        void completeDelete() {
            pendingDelete.tryComplete(response("1"));
        }

        @Override
        public Future<Response> get(String key) {
            if (blockEntryReads && !key.endsWith(":generation")) {
                blockedEntryReadKey = key;
                blockedEntryRead = Promise.promise();
                entryReadStarted.countDown();
                return blockedEntryRead.future();
            }
            return super.get(key);
        }

        @Override
        public Future<Response> del(List<String> command) {
            delCommands.add(List.copyOf(command));
            if (pendingDelete != null) {
                command.forEach(values::remove);
                return pendingDelete.future();
            }
            return super.del(command);
        }
    }
}
