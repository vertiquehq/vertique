// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocalizationContextServiceDispatchEncoder} and
 * {@link LocalizationContextServiceDispatchDecoder}.
 *
 * <p>Verifies the identity encode path and all three decoder paths (null→empty,
 * instanceof→of, wrong-type→failure).
 */
class LocalizationContextServiceDispatchCodecTest {

    private static final ServiceDispatchEncodeContext ENCODE_CTX = new ServiceDispatchEncodeContext("service-dispatch");
    private static final ServiceDispatchDecodeContext DECODE_CTX = new ServiceDispatchDecodeContext("service-dispatch");

    private final LocalizationContextServiceDispatchEncoder encoder = new LocalizationContextServiceDispatchEncoder();
    private final LocalizationContextServiceDispatchDecoder decoder = new LocalizationContextServiceDispatchDecoder();

    private final LocalizationContext sampleCtx = new LocalizationContext(
            Locale.forLanguageTag("fi"),
            ZoneId.of("Europe/Helsinki"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "test",
            "test");

    // --- Encoder ---

    @Nested
    @DisplayName("LocalizationContextServiceDispatchEncoder")
    class EncoderTests {

        @Test
        @DisplayName("type() returns LocalizationContext.class")
        void typeReturnsLocalizationContextClass() {
            assertEquals(LocalizationContext.class, encoder.type());
        }

        @Test
        @DisplayName("key() defaults to LocalizationContext FQCN")
        void keyDefaultsToFqcn() {
            assertEquals(LocalizationContext.class.getName(), encoder.key());
        }

        @Test
        @DisplayName("encode returns the same LocalizationContext instance (identity pass-through)")
        void encodeReturnsIdentity() {
            Object result = encoder.encode(sampleCtx, ENCODE_CTX);

            assertSame(sampleCtx, result, "encode must return the same object reference");
        }
    }

    // --- Decoder ---

    @Nested
    @DisplayName("LocalizationContextServiceDispatchDecoder")
    class DecoderTests {

        @Test
        @DisplayName("type() returns LocalizationContext.class")
        void typeReturnsLocalizationContextClass() {
            assertEquals(LocalizationContext.class, decoder.type());
        }

        @Test
        @DisplayName("key() defaults to LocalizationContext FQCN")
        void keyDefaultsToFqcn() {
            assertEquals(LocalizationContext.class.getName(), decoder.key());
        }

        @Test
        @DisplayName("null value yields empty result with no warnings")
        void nullValueYieldsEmptyResultNoWarnings() {
            ContextDecodeResult<LocalizationContext> result = decoder.decode(null, DECODE_CTX);

            assertFalse(result.value().isPresent(), "value should be empty for null input");
            assertTrue(result.warnings().isEmpty(), "no warnings when value is absent");
        }

        @Test
        @DisplayName("LocalizationContext instance decodes to a present result (same reference)")
        void localizationContextDecodesSuccessfully() {
            ContextDecodeResult<LocalizationContext> result = decoder.decode(sampleCtx, DECODE_CTX);

            assertTrue(result.value().isPresent(), "value should be present");
            assertSame(sampleCtx, result.value().get(), "decoded value must be the same instance");
            assertTrue(result.warnings().isEmpty(), "no warnings on success");
        }

        @Test
        @DisplayName("wrong-type value yields empty result with a failure warning")
        void wrongTypeYieldsFailureWarning() {
            ContextDecodeResult<LocalizationContext> result = decoder.decode("unexpected-string", DECODE_CTX);

            assertFalse(result.value().isPresent(), "value should be empty for wrong type");
            assertFalse(result.warnings().isEmpty(), "at least one warning must be present");
        }

        @Test
        @DisplayName("warning key matches the decoder key()")
        void warningKeyMatchesDecoderKey() {
            ContextDecodeResult<LocalizationContext> result = decoder.decode(Integer.valueOf(99), DECODE_CTX);

            assertFalse(result.warnings().isEmpty());
            assertEquals(decoder.key(), result.warnings().get(0).key());
        }

        @Test
        @DisplayName("warning reason names the unexpected class")
        void warningReasonNamesUnexpectedClass() {
            ContextDecodeResult<LocalizationContext> result = decoder.decode(Integer.valueOf(99), DECODE_CTX);

            assertFalse(result.warnings().isEmpty());
            ContextDecodeWarning warning = result.warnings().get(0);
            assertTrue(
                    warning.reason().contains(Integer.class.getName()),
                    "warning reason should contain the unexpected class name: " + warning.reason());
        }
    }
}
