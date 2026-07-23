// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertxJsonSupport}, verifying that the re-exported Vert.x Jackson module
 * enables a plain {@link ObjectMapper} to round-trip {@link JsonObject}/{@link JsonArray} without
 * data loss (FR-JSON-015B).
 */
class VertxJsonSupportTest {

    @Test
    @DisplayName("module() registers VertxModule so a plain ObjectMapper round-trips a JsonObject")
    void module_returnsVertxModule_roundTripsJsonObject() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(VertxJsonSupport.module());

        JsonObject original = new JsonObject()
                .put("name", "alice")
                .put("count", 42)
                .put("active", true)
                .put("missing", (Object) null)
                .put("nested", new JsonObject().put("city", "helsinki").put("zip", 100))
                .put("tags", new JsonArray().add("a").add("b").add(3));

        byte[] bytes = mapper.writeValueAsBytes(original);
        JsonObject decoded = mapper.readValue(bytes, JsonObject.class);

        assertEquals(original, decoded);
    }
}
