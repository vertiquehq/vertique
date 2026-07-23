// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in {@link VertxJsonMapperProfile}, verifying that its id is the reserved
 * {@code vertx} id and that its mapper is Vert.x's shared {@link DatabindCodec#mapper()} instance
 * returned directly, with no copy (FR-JSON-006).
 */
class VertxJsonMapperProfileTest {

    @Test
    @DisplayName("id() is the reserved vertx profile id")
    void id_isVertx() {
        assertEquals(JsonProfileId.VERTX, new VertxJsonMapperProfile().id());
    }

    @Test
    @DisplayName("mapper() returns the shared DatabindCodec.mapper() instance directly")
    void mapper_returnsDatabindCodecInstance_sameReference() {
        assertSame(DatabindCodec.mapper(), new VertxJsonMapperProfile().mapper());
    }
}
