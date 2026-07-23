// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the context-producing SPIs declare their context-type parameter bounded by
 * {@link ContextValue}.
 *
 * <p>This is the bytecode-level proof of the compile-time contract from PRD context-001 (FR-CTX-210):
 * an extension author cannot declare an encoder/decoder/adapter over a non-{@code ContextValue}
 * context type, so a malformed context type is rejected at compile time rather than at erased-path
 * reinstatement (runtime). Only the context-type parameter is bounded; wire/snapshot/envelope type
 * parameters intentionally remain unbounded.
 */
class ContextValueSpiBoundsTest {

    @Test
    @DisplayName("ServiceDispatchContextEncoder<T> is bounded by ContextValue")
    void serviceDispatchEncoderBounded() {
        assertEquals(
                ContextValue.class,
                ServiceDispatchContextEncoder.class.getTypeParameters()[0].getBounds()[0]);
    }

    @Test
    @DisplayName("ServiceDispatchContextDecoder<T> is bounded by ContextValue")
    void serviceDispatchDecoderBounded() {
        assertEquals(
                ContextValue.class,
                ServiceDispatchContextDecoder.class.getTypeParameters()[0].getBounds()[0]);
    }

    @Test
    @DisplayName("DurableContextMetadataEncoder<T> is bounded by ContextValue")
    void durableEncoderBounded() {
        assertEquals(
                ContextValue.class,
                DurableContextMetadataEncoder.class.getTypeParameters()[0].getBounds()[0]);
    }

    @Test
    @DisplayName("DurableContextMetadataDecoder<T> is bounded by ContextValue")
    void durableDecoderBounded() {
        assertEquals(
                ContextValue.class,
                DurableContextMetadataDecoder.class.getTypeParameters()[0].getBounds()[0]);
    }

    @Test
    @DisplayName("ContextValueAdapter<T> is bounded by ContextValue")
    void contextValueAdapterBounded() {
        assertEquals(
                ContextValue.class,
                ContextValueAdapter.class.getTypeParameters()[0].getBounds()[0]);
    }
}
