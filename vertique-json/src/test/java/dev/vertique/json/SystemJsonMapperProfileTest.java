// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in {@link SystemJsonMapperProfile}, verifying that its id is the reserved
 * {@code system} id and that its mapper is a cached <em>copy</em> of Vert.x's shared
 * {@link DatabindCodec#mapper()} — never the shared instance itself, and the same instance on every
 * call.
 */
class SystemJsonMapperProfileTest {

    @Test
    @DisplayName("id() is the reserved system profile id")
    void id_isSystem() {
        assertEquals(JsonProfileId.SYSTEM, new SystemJsonMapperProfile().id());
    }

    @Test
    @DisplayName("mapper() returns a copy of DatabindCodec.mapper(), never the shared instance")
    void mapper_returnsCopy_notTheSharedInstance() {
        assertNotSame(DatabindCodec.mapper(), new SystemJsonMapperProfile().mapper());
    }

    @Test
    @DisplayName("mapper() returns the same cached instance on every call")
    void mapper_isStableAcrossCalls() {
        SystemJsonMapperProfile profile = new SystemJsonMapperProfile();

        assertSame(profile.mapper(), profile.mapper());
    }
}
