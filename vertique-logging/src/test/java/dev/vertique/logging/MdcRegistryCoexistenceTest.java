// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DurablePropagationMetadataServiceDispatchDecoder;
import dev.vertique.context.DurablePropagationMetadataServiceDispatchEncoder;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the MDC built-in encoder/decoder pair coexists with the
 * {@link DurablePropagationMetadata} built-in pair in a shared
 * {@link ServiceDispatchContextRegistry} without key or type collisions.
 *
 * <p>These tests live in {@code vertique-logging} (rather than {@code vertique-context})
 * because {@link MDCContexts} was moved to this module as part of the context-substrate split.
 * The tests remain in package {@code dev.vertique.core.context} to retain access to the
 * package-private encoder/decoder implementations used as the comparison partner.
 */
class MdcRegistryCoexistenceTest {

    @Test
    @DisplayName("DurablePropagationMetadata + MDC encoders coexist without collision")
    void builtInEncodersCoexistWithoutCollision() {
        ServiceDispatchContextEncoder<?> durableEncoder = new DurablePropagationMetadataServiceDispatchEncoder();
        ServiceDispatchContextEncoder<?> mdcEncoder = MDCContexts.serviceDispatchEncoder();

        assertDoesNotThrow(
                () -> new ServiceDispatchContextRegistry(Set.of(durableEncoder, mdcEncoder), Set.of()),
                "built-in encoder pair must not collide on key or type");
    }

    @Test
    @DisplayName("DurablePropagationMetadata + MDC decoders coexist without collision")
    void builtInDecodersCoexistWithoutCollision() {
        ServiceDispatchContextDecoder<?> durableDecoder = new DurablePropagationMetadataServiceDispatchDecoder();
        ServiceDispatchContextDecoder<?> mdcDecoder = MDCContexts.serviceDispatchDecoder();

        assertDoesNotThrow(
                () -> new ServiceDispatchContextRegistry(Set.of(), Set.of(durableDecoder, mdcDecoder)),
                "built-in decoder pair must not collide on key or type");
    }

    @Test
    @DisplayName("all four built-ins together construct a valid registry with two encoders and two decoders")
    void allFourBuiltInsConstructValidRegistry() {
        ServiceDispatchContextEncoder<?> durableEncoder = new DurablePropagationMetadataServiceDispatchEncoder();
        ServiceDispatchContextEncoder<?> mdcEncoder = MDCContexts.serviceDispatchEncoder();
        ServiceDispatchContextDecoder<?> durableDecoder = new DurablePropagationMetadataServiceDispatchDecoder();
        ServiceDispatchContextDecoder<?> mdcDecoder = MDCContexts.serviceDispatchDecoder();

        ServiceDispatchContextRegistry registry = assertDoesNotThrow(() -> new ServiceDispatchContextRegistry(
                Set.of(durableEncoder, mdcEncoder), Set.of(durableDecoder, mdcDecoder)));

        assertEquals(2, registry.encoders().size(), "registry must contain exactly two built-in encoders");
        assertEquals(2, registry.decoders().size(), "registry must contain exactly two built-in decoders");
    }

    @Test
    @DisplayName("MDC encoder and DurablePropagationMetadata encoder use distinct keys")
    void mdcKeyIsDifferentFromDurableKey() {
        String mdcKey = MDCContexts.serviceDispatchEncoder().key();
        String durableKey = new DurablePropagationMetadataServiceDispatchEncoder().key();

        assertTrue(
                !mdcKey.equals(durableKey),
                "MDC and DurablePropagationMetadata must use different dispatch-context keys");
    }
}
