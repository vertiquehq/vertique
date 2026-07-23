// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDispatchContextRegistry}.
 *
 * <p>Verifies boot-time validation of duplicate keys and types, as well as acceptance of empty,
 * encoder-only, and decoder-only configurations. MDC coexistence tests live in
 * {@code vertique-logging} where {@code MDCContexts} is defined.
 */
class ServiceDispatchContextRegistryTest {

    // --- Empty sets ---

    @Test
    @DisplayName("empty encoder and decoder sets construct successfully (FR-CTX-044)")
    void emptySetsConstructOk() {
        assertDoesNotThrow(() -> new ServiceDispatchContextRegistry(Set.of(), Set.of()));
    }

    // --- Encoder validation ---

    @Test
    @DisplayName("duplicate encoder key fails construction (FR-CTX-045)")
    void duplicateEncoderKeyFails() {
        // Two encoders over DISTINCT types that both override key() to the same string — this
        // tests key-collision detection independent of type-duplicate detection.
        ServiceDispatchContextEncoder<StringCtx> enc1 = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "shared.key";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextEncoder<IntCtx> enc2 = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String key() {
                return "shared.key";
            }

            @Override
            public Object encode(IntCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new ServiceDispatchContextRegistry(Set.of(enc1, enc2), Set.of()));
        assertTrue(ex.getMessage().contains("shared.key"), "error message should name the colliding key");
    }

    @Test
    @DisplayName("duplicate encoder type fails construction (FR-CTX-046)")
    void duplicateEncoderTypeFails() {
        // Two encoders over the SAME type with distinct keys — tests duplicate-type detection.
        ServiceDispatchContextEncoder<StringCtx> enc1 = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "key1";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextEncoder<StringCtx> enc2 = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "key2";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new ServiceDispatchContextRegistry(Set.of(enc1, enc2), Set.of()));
        assertTrue(ex.getMessage().contains(StringCtx.class.getName()), "error message should name the duplicate type");
    }

    // --- Decoder validation ---

    @Test
    @DisplayName("duplicate decoder key fails construction (FR-CTX-045)")
    void duplicateDecoderKeyFails() {
        // Two decoders over DISTINCT types that both override key() to the same string — tests
        // key-collision detection independent of type-duplicate detection.
        ServiceDispatchContextDecoder<StringCtx> dec1 = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "shared.key";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        ServiceDispatchContextDecoder<IntCtx> dec2 = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String key() {
                return "shared.key";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new ServiceDispatchContextRegistry(Set.of(), Set.of(dec1, dec2)));
        assertTrue(ex.getMessage().contains("shared.key"), "error message should name the colliding key");
    }

    @Test
    @DisplayName("duplicate decoder type fails construction (FR-CTX-046)")
    void duplicateDecoderTypeFails() {
        // Two decoders over the SAME type with distinct keys — tests duplicate-type detection.
        ServiceDispatchContextDecoder<StringCtx> dec1 = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "key1";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        ServiceDispatchContextDecoder<StringCtx> dec2 = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "key2";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new ServiceDispatchContextRegistry(Set.of(), Set.of(dec1, dec2)));
        assertTrue(ex.getMessage().contains(StringCtx.class.getName()), "error message should name the duplicate type");
    }

    // --- Encoder-only and decoder-only ---

    @Test
    @DisplayName("encoder-only configuration is valid (FR-CTX-047)")
    void encoderOnlyIsValid() {
        ServiceDispatchContextEncoder<StringCtx> encoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextRegistry registry =
                assertDoesNotThrow(() -> new ServiceDispatchContextRegistry(Set.of(encoder), Set.of()));
        assertEquals(1, registry.encoders().size());
        assertTrue(registry.decoders().isEmpty());
    }

    @Test
    @DisplayName("decoder-only configuration is valid (FR-CTX-047)")
    void decoderOnlyIsValid() {
        ServiceDispatchContextDecoder<StringCtx> decoder = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.of(new StringCtx(value.toString()));
            }
        };
        ServiceDispatchContextRegistry registry =
                assertDoesNotThrow(() -> new ServiceDispatchContextRegistry(Set.of(), Set.of(decoder)));
        assertTrue(registry.encoders().isEmpty());
        assertEquals(1, registry.decoders().size());
    }

    @Test
    @DisplayName("decoderForType returns present for registered type")
    void decoderForTypeReturnsPresent() {
        ServiceDispatchContextDecoder<StringCtx> decoder = new ServiceDispatchContextDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(Object value, ServiceDispatchDecodeContext context) {
                return ContextDecodeResult.of(new StringCtx(value.toString()));
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(), Set.of(decoder));
        Optional<ServiceDispatchContextDecoder<?>> found = registry.decoderForType(StringCtx.class);
        assertTrue(found.isPresent());
    }

    @Test
    @DisplayName("decoderForType returns empty for unregistered type")
    void decoderForTypeReturnsEmpty() {
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(), Set.of());
        assertTrue(registry.decoderForType(StringCtx.class).isEmpty());
    }
}
