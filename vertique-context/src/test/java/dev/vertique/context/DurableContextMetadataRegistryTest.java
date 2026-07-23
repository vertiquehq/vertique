// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableContextMetadataRegistry}.
 *
 * <p>Verifies boot-time validation of duplicate types and duplicate namespaces for both encoder and
 * decoder sets, as well as acceptance of empty sets.
 */
class DurableContextMetadataRegistryTest {

    // --- Empty sets ---

    @Test
    @DisplayName("empty encoder and decoder sets construct successfully (FR-CTX-112)")
    void emptySetsConstructOk() {
        assertDoesNotThrow(() -> new DurableContextMetadataRegistry(Set.of(), Set.of()));
    }

    // --- Encoder duplicate type ---

    @Test
    @DisplayName("duplicate encoder type fails construction (FR-CTX-113)")
    void duplicateEncoderTypeFails() {
        // Both encoders register the same type (StringCtx) — duplicate type must be rejected.
        DurableContextMetadataEncoder<StringCtx> enc1 = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "ns1";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("ns1", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataEncoder<StringCtx> enc2 = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "ns2";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("ns2", new JsonObject().put("value", value.value()));
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(enc1, enc2), Set.of()));
        assertTrue(ex.getMessage().contains(StringCtx.class.getName()), "error message should name the duplicate type");
    }

    // --- Encoder duplicate namespace ---

    @Test
    @DisplayName("duplicate encoder namespace fails construction (FR-CTX-113)")
    void duplicateEncoderNamespaceFails() {
        // Two distinct types (StringCtx vs IntCtx) sharing the same namespace — must be rejected.
        DurableContextMetadataEncoder<StringCtx> enc1 = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "shared-ns";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("shared-ns", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataEncoder<IntCtx> enc2 = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "shared-ns";
            }

            @Override
            public DurableMetadata encode(IntCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("shared-ns", new JsonObject().put("value", Integer.toString(value.value())));
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(enc1, enc2), Set.of()));
        assertTrue(ex.getMessage().contains("shared-ns"), "error message should name the duplicate namespace");
    }

    // --- Namespace validity ---

    @Test
    @DisplayName("blank encoder namespace fails construction")
    void blankEncoderNamespaceFails() {
        DurableContextMetadataEncoder<StringCtx> enc = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "  ";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.empty();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(enc), Set.of()));
        assertTrue(ex.getMessage().contains("null or blank"), "error message should explain the blank namespace");
    }

    @Test
    @DisplayName("reserved-prefix encoder namespace fails construction")
    void reservedPrefixEncoderNamespaceFails() {
        DurableContextMetadataEncoder<StringCtx> enc = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "vertique-correlation";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("vertique-correlation", new JsonObject().put("value", value.value()));
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(enc), Set.of()));
        assertTrue(ex.getMessage().contains("reserved"), "error message should explain the reserved prefix");
    }

    // --- Decoder duplicate type ---

    @Test
    @DisplayName("duplicate decoder type fails construction (FR-CTX-114)")
    void duplicateDecoderTypeFails() {
        // Both decoders register the same type (StringCtx) — duplicate type must be rejected.
        DurableContextMetadataDecoder<StringCtx> dec1 = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "ns1";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        DurableContextMetadataDecoder<StringCtx> dec2 = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "ns2";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(), Set.of(dec1, dec2)));
        assertTrue(ex.getMessage().contains(StringCtx.class.getName()), "error message should name the duplicate type");
    }

    // --- Decoder duplicate namespace ---

    @Test
    @DisplayName("duplicate decoder namespace fails construction (FR-CTX-114)")
    void duplicateDecoderNamespaceFails() {
        // Two distinct types (StringCtx vs IntCtx) sharing the same namespace — must be rejected.
        DurableContextMetadataDecoder<StringCtx> dec1 = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "shared-ns";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        DurableContextMetadataDecoder<IntCtx> dec2 = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "shared-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> new DurableContextMetadataRegistry(Set.of(), Set.of(dec1, dec2)));
        assertTrue(ex.getMessage().contains("shared-ns"), "error message should name the duplicate namespace");
    }

    // --- Non-overlapping mixed sets succeed ---

    @Test
    @DisplayName("non-overlapping encoder and decoder sets construct successfully")
    void nonOverlappingSetConstructsOk() {
        // Distinct types (StringCtx for encoder, IntCtx for decoder) with distinct namespaces — must succeed.
        DurableContextMetadataEncoder<StringCtx> enc = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "enc-ns";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of("enc-ns", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataDecoder<IntCtx> dec = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "dec-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        assertDoesNotThrow(() -> new DurableContextMetadataRegistry(Set.of(enc), Set.of(dec)));
    }
}
