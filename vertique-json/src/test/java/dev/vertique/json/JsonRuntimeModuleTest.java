// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dagger.Component;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonProfileRegistry;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger boot test for {@link JsonRuntimeModule}.
 *
 * <p>Constructs a minimal {@code @Component} that installs only {@link JsonRuntimeModule} (so the
 * empty {@code @Multibinds Set<JsonProfileCodec>} is the only application input) and verifies the
 * {@link JsonProfileRegistry} binding resolves to {@link DefaultJsonProfileRegistry},
 * is non-null, and resolves the built-in {@code vertx} profile to Vert.x's shared mapper.
 */
class JsonRuntimeModuleTest {

    @Singleton
    @Component(modules = JsonRuntimeModule.class)
    interface JsonTestComponent {
        JsonProfileRegistry registry();
    }

    @Test
    @DisplayName("JsonProfileRegistry binds to DefaultJsonProfileRegistry and resolves system")
    void registryInterface_boundToDefaultImpl() {
        JsonTestComponent component = DaggerJsonRuntimeModuleTest_JsonTestComponent.create();

        JsonProfileRegistry registry = component.registry();

        assertNotNull(registry, "registry must be resolvable");
        assertInstanceOf(DefaultJsonProfileRegistry.class, registry);
        assertNotSame(
                DatabindCodec.mapper(), Jackson2JsonProfileCodec.objectMapper(registry.profile(JsonProfileId.SYSTEM)));
    }
}
