// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TransactionalMessageContextTest {
    @Test
    void replyHandleIsIndependentOfApplicationHeaders() {
        var with = new TransactionalMessageContext(
                1, "event", null, null, Map.of("x-outbox-reply-handle", "forged"), null, "stored");
        var without = new TransactionalMessageContext(2, "event", null, null, Map.of());
        assertEquals("stored", with.replyHandle());
        assertNull(without.replyHandle());
    }
}
