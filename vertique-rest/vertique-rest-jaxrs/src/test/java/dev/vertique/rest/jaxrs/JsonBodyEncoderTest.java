// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonBodyEncoder}.
 *
 * <p>Verifies that any entity type is accepted, that the encoder is registered at
 * priority {@code 1100}, and that entities are JSON-encoded into a {@link BufferedBody}
 * with {@code application/json} as the default content type. The encode tests also pin the
 * stash-read contract: when no per-method mapper is stashed under
 * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} the encoder stays on {@link Json#encode}, and when one
 * is present the entity is serialized through that resolved profile mapper instead.
 */
class JsonBodyEncoderTest {

    private final JsonBodyEncoder encoder = new JsonBodyEncoder();

    // --- canEncode ---

    @Test
    @DisplayName("Should accept any entity type when content type is null")
    void shouldAcceptAnyTypeWhenContentTypeNull() {
        assertTrue(encoder.canEncode(String.class, null));
        assertTrue(encoder.canEncode(Map.class, null));
        assertTrue(encoder.canEncode(Object.class, null));
    }

    @Test
    @DisplayName("Should accept application/json content type")
    void shouldAcceptJsonContentType() {
        assertTrue(encoder.canEncode(Map.class, "application/json"));
    }

    @Test
    @DisplayName("Should accept application/problem+json content type")
    void shouldAcceptProblemJsonContentType() {
        assertTrue(encoder.canEncode(Map.class, "application/problem+json"));
    }

    @Test
    @DisplayName("Should reject non-JSON content type")
    void shouldRejectNonJsonContentType() {
        assertFalse(encoder.canEncode(Map.class, "application/xml"));
        assertFalse(encoder.canEncode(Map.class, "text/plain"));
        assertFalse(encoder.canEncode(Map.class, "application/octet-stream"));
    }

    // --- priority ---

    @Test
    @DisplayName("Should report priority 1100")
    void shouldReportPriority1100() {
        assertEquals(1100, encoder.priority());
    }

    // --- encode ---

    @Test
    @DisplayName("Should produce BufferedBody via Json.encode when no profile mapper is stashed")
    void shouldProduceJsonBufferedBody() {
        Map<String, String> entity = Map.of("k", "v");
        RoutingContext ctx = mock(RoutingContext.class); // get(KEY) returns null => process-codec path
        SerializedBody body = encoder.encode(ctx, null, entity);

        assertInstanceOf(BufferedBody.class, body);
        BufferedBody buffered = (BufferedBody) body;
        assertEquals("application/json", buffered.contentType());
        assertEquals(Json.encode(entity), buffered.buffer().toString());
    }

    @Test
    @DisplayName("Should serialize via the stashed resolved profile mapper when one is present")
    void shouldSerializeViaStashedProfileMapper() {
        Map<String, String> entity = Map.of("k", "v");
        ObjectMapper profileMapper = JsonMapper.builder().build();
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(profileMapper);

        SerializedBody body = encoder.encode(ctx, null, entity);

        assertInstanceOf(BufferedBody.class, body);
        BufferedBody buffered = (BufferedBody) body;
        assertEquals("application/json", buffered.contentType());
        assertEquals(expectedJson(profileMapper, entity), buffered.buffer().toString());
    }

    /**
     * Computes the expected JSON string for {@code entity} via {@code mapper}, surfacing any
     * serialization failure as an assertion error rather than a checked exception in the test body.
     *
     * @param mapper the mapper to serialize with
     * @param entity the entity to serialize
     * @return the JSON string produced by {@code mapper}
     */
    private static String expectedJson(ObjectMapper mapper, Object entity) {
        try {
            return mapper.writeValueAsString(entity);
        } catch (Exception e) {
            return fail("mapper failed to serialize the expected value", e);
        }
    }
}
