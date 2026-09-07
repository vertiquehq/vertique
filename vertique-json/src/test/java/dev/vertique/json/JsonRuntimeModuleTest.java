// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dagger.Component;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger boot test for {@link JsonRuntimeModule}.
 *
 * <p>Constructs a minimal {@code @Component} that installs only {@link JsonRuntimeModule} (so the
 * empty {@code @Multibinds Set<JsonMapperProfile>} is the only application input) and verifies the
 * {@link JsonMapperProfileRegistry} binding resolves to {@link DefaultJsonMapperProfileRegistry},
 * is non-null, and resolves the built-in {@code vertx} profile to Vert.x's shared mapper.
 */
class JsonRuntimeModuleTest {

    @Singleton
    @Component(modules = JsonRuntimeModule.class)
    interface JsonTestComponent {
        JsonMapperProfileRegistry registry();
    }

    @Test
    @DisplayName("JsonMapperProfileRegistry binds to DefaultJsonMapperProfileRegistry and resolves system")
    void registryInterface_boundToDefaultImpl() {
        JsonTestComponent component = DaggerJsonRuntimeModuleTest_JsonTestComponent.create();

        JsonMapperProfileRegistry registry = component.registry();

        assertNotNull(registry, "registry must be resolvable");
        assertInstanceOf(DefaultJsonMapperProfileRegistry.class, registry);
        assertNotSame(DatabindCodec.mapper(), registry.mapper(JsonProfileId.SYSTEM));
    }
}
