// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DispatchEnvelope} factory methods and accessor delegation.
 */
class DispatchEnvelopeTest {

    @Test
    @DisplayName("of(payload, metadata) carries payload + metadata, empty reply address")
    void carriesPayloadAndMetadata() {
        DispatchMetadata md = DispatchMetadata.of(Map.of("ctx", 1));
        DispatchEnvelope<String> env = DispatchEnvelope.of("hello", md);
        assertEquals("hello", env.payload());
        assertSame(md, env.metadata());
        assertFalse(env.replyAddress().isPresent());
    }

    @Test
    @DisplayName("of(payload, metadata, replyAddress) sets reply address")
    void setsReplyAddress() {
        DispatchEnvelope<String> env = DispatchEnvelope.of("hello", DispatchMetadata.empty(), "reply.addr");
        assertTrue(env.replyAddress().isPresent());
        assertEquals("reply.addr", env.replyAddress().orElseThrow());
    }

    @Test
    @DisplayName("empty() has null payload and empty metadata")
    void emptyEnvelope() {
        DispatchEnvelope<Void> env = DispatchEnvelope.empty();
        assertNull(env.payload());
        assertEquals(DispatchMetadata.empty(), env.metadata());
    }

    @Test
    @DisplayName("null metadata is replaced by empty metadata")
    void nullMetadataIsEmpty() {
        DispatchEnvelope<String> env = DispatchEnvelope.of("hi", (DispatchMetadata) null);
        assertEquals(DispatchMetadata.empty(), env.metadata());
    }

    @Test
    @DisplayName("of(payload) returns envelope with empty metadata")
    void simpleFactoryEmptyMetadata() {
        DispatchEnvelope<String> env = DispatchEnvelope.of("hi");
        assertEquals("hi", env.payload());
        assertTrue(env.metadata().dispatchContext().isEmpty());
    }
}
