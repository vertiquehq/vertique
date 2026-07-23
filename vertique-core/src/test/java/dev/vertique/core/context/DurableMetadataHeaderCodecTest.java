// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableMetadataHeaderCodec}: projection of the durable context document to
 * and from a flat string-keyed header carrier (Kafka), and the reserved-header collision guard.
 */
class DurableMetadataHeaderCodecTest {

    private static JsonObject body(String key, String value) {
        return new JsonObject().put(key, value);
    }

    @Test
    @DisplayName("toHeaders projects each namespace to a vertique-prefixed header carrying its JSON body")
    void toHeaders() {
        DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"))
                .with("localization", body("locale", "en-US"));
        Map<String, String> headers = DurableMetadataHeaderCodec.toHeaders(md);
        assertEquals(body("requestId", "r1"), new JsonObject(headers.get("vertique-correlation")));
        assertEquals(body("locale", "en-US"), new JsonObject(headers.get("vertique-localization")));
    }

    @Test
    @DisplayName("toHeaders / fromHeaders round-trip preserves equality")
    void roundTrip() {
        DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"))
                .with("localization", body("locale", "sv-FI"));
        assertEquals(md, DurableMetadataHeaderCodec.fromHeaders(DurableMetadataHeaderCodec.toHeaders(md)));
    }

    @Test
    @DisplayName("UTF-8 multibyte content round-trips")
    void utf8RoundTrip() {
        DurableMetadata md = DurableMetadata.of("localization", body("city", "Jyväskylä"));
        DurableMetadata back = DurableMetadataHeaderCodec.fromHeaders(DurableMetadataHeaderCodec.toHeaders(md));
        assertEquals("Jyväskylä", back.body("localization").orElseThrow().getString("city"));
    }

    @Test
    @DisplayName("fromHeaders ignores non-reserved (application) headers")
    void ignoresAppHeaders() {
        Map<String, String> headers = Map.of(
                "content-type", "application/json",
                "x-correlation-id", "abc",
                "vertique-correlation", body("requestId", "r1").encode());
        DurableMetadata md = DurableMetadataHeaderCodec.fromHeaders(headers);
        assertEquals(java.util.Set.of("correlation"), md.namespaces());
    }

    @Test
    @DisplayName("fromHeaders skips a reserved header whose value is not a JSON object")
    void skipsMalformed() {
        Map<String, String> headers = Map.of(
                "vertique-correlation",
                "not-json",
                "vertique-localization",
                body("locale", "en").encode());
        DurableMetadata md = DurableMetadataHeaderCodec.fromHeaders(headers);
        assertFalse(md.has("correlation"));
        assertTrue(md.has("localization"));
    }

    @Test
    @DisplayName("isReservedHeader recognizes the vertique- prefix")
    void isReservedHeader() {
        assertTrue(DurableMetadataHeaderCodec.isReservedHeader("vertique-correlation"));
        assertFalse(DurableMetadataHeaderCodec.isReservedHeader("x-correlation-id"));
        assertFalse(DurableMetadataHeaderCodec.isReservedHeader("content-type"));
        assertFalse(DurableMetadataHeaderCodec.isReservedHeader(null));
    }

    @Test
    @DisplayName("mergeForEgress overlays projected context headers onto app headers")
    void mergeForEgress() {
        DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
        Map<String, String> merged =
                DurableMetadataHeaderCodec.mergeForEgress(Map.of("content-type", "application/json"), md);
        assertEquals("application/json", merged.get("content-type"));
        assertEquals(body("requestId", "r1"), new JsonObject(merged.get("vertique-correlation")));
    }

    @Test
    @DisplayName("mergeForEgress rejects an application header using a reserved name")
    void mergeForEgressRejectsReserved() {
        DurableMetadata md = DurableMetadata.of("correlation", body("requestId", "r1"));
        Map<String, String> appHeaders = Map.of("vertique-correlation", "app-supplied");
        assertThrows(IllegalArgumentException.class, () -> DurableMetadataHeaderCodec.mergeForEgress(appHeaders, md));
    }
}
