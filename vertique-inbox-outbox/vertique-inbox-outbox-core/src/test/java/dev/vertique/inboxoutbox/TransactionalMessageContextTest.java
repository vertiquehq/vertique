// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TransactionalMessageContextTest {
    @Test
    void toStringMasksTheReplyHandle() {
        var context = new TransactionalMessageContext(1L, "evt", "agg", "id", Map.of(), null, "secret-handle");

        assertFalse(context.toString().contains("secret-handle"), context.toString());
        assertTrue(context.toString().contains("replyHandle=***"), context.toString());
        assertTrue(new TransactionalMessageContext(1L, "evt", "agg", "id", Map.of())
                .toString()
                .contains("replyHandle=null"));
    }

    @Test
    void replyHandleIsIndependentOfApplicationHeaders() {
        var with = new TransactionalMessageContext(
                1, "event", null, null, Map.of("x-outbox-reply-handle", "forged"), null, "stored");
        var without = new TransactionalMessageContext(2, "event", null, null, Map.of());
        assertEquals("stored", with.replyHandle());
        assertNull(without.replyHandle());
    }
}
