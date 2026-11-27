// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.buffer.Buffer;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves core alone does not own the process JSON codec: on core's own test classpath, with no JSON
 * module present, Vert.x keeps its stock codec.
 *
 * <p>Without the framework codec, {@code codec()}, {@code install} and {@code resetForTests} fail with a
 * message naming {@code vertique-json}, and {@code profile()} stays empty.
 */
class StockCodecWithoutJsonModuleTest {

    @Test
    @DisplayName("core alone does not own the process JSON codec")
    void coreAloneDoesNotOwnTheCodec() {
        assertFalse(
                VertiqueJson.ownsCodec(),
                "without the JSON module on the classpath no Vertique JsonFactory may be selected");
    }

    @Test
    @DisplayName("without the framework codec the codec seams fail naming vertique-json and nothing is installed")
    void codecSeamsFailNamingTheJsonModule() {
        assertEquals(Optional.empty(), VertiqueJson.profile());
        assertNamesJsonModule(assertThrows(IllegalStateException.class, VertiqueJson::codec));
        assertNamesJsonModule(
                assertThrows(IllegalStateException.class, () -> VertiqueJson.install(new UnusedProfile())));
        assertNamesJsonModule(assertThrows(IllegalStateException.class, VertiqueJson::resetForTests));
        assertEquals(Optional.empty(), VertiqueJson.profile(), "a refused install must record nothing");
    }

    private static void assertNamesJsonModule(IllegalStateException e) {
        assertTrue(
                e.getMessage().contains("vertique-json"), () -> "message must name vertique-json: " + e.getMessage());
    }

    /** A profile the refused install never reaches. */
    private static final class UnusedProfile implements JsonProfileCodec {
        @Override
        public JsonProfileId id() {
            return JsonProfileId.of("unused");
        }

        @Override
        public Buffer encode(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String encodeToString(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void encodeTo(Object value, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(Buffer json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(String json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convert(Object value, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> type) {
            return Optional.empty();
        }
    }
}
