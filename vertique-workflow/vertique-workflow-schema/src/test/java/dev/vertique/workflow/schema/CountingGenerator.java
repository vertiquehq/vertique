// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** A generator that records every type it is asked to describe and can be told to fail the next calls. */
final class CountingGenerator extends PayloadSchemaGenerator {

    /** The types passed to {@link #describe(Type)}, in call order. */
    final List<Type> described = new CopyOnWriteArrayList<>();

    private final Deque<RuntimeException> failures = new ArrayDeque<>();

    CountingGenerator(JsonMapperProfileRegistry registry, JsonConfig config) {
        super(registry, config, Optional.empty());
    }

    /**
     * Makes the next call to {@link #describe(Type)} throw {@code failure} after recording the type.
     *
     * @param failure the failure
     */
    void failNext(RuntimeException failure) {
        failures.add(failure);
    }

    @Override
    JsonObject describe(Type type) {
        described.add(type);
        RuntimeException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
        return super.describe(type);
    }
}
