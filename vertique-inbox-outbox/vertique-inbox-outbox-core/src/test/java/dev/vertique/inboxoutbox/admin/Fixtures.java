// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.inboxoutbox.DestinationType;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** Valid sample values shared by the administration type tests. */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    private Fixtures() {}

    static OutboxDeadLetterSummary summary() {
        return new OutboxDeadLetterSummary(
                1L,
                2L,
                DestinationType.KAFKA,
                "topic",
                "order.placed",
                "Order",
                "o-1",
                3,
                3,
                "java.io.IOException",
                NOW,
                NOW);
    }

    static OutboxDeadLetterContent content() {
        return new OutboxDeadLetterContent(1L, 2L, "{\"a\":1}", Map.of("k", "v"));
    }

    static OutboxAdminActor actor() {
        return new OutboxAdminActor("user", "alice");
    }

    static OutboxAdminCommand command() {
        return new OutboxAdminCommand(UUID.randomUUID(), 1L, 2L, "because", actor());
    }

    static OutboxAdminReceipt retryReceipt() {
        return new OutboxAdminReceipt(
                UUID.randomUUID(),
                1L,
                OutboxAdminAction.RETRY,
                OutboxAdminDisposition.REQUEUED,
                2L,
                OptionalLong.of(3L),
                NOW);
    }

    static OutboxAdminHistoryEntry historyEntry() {
        return new OutboxAdminHistoryEntry(
                UUID.randomUUID(),
                1L,
                OutboxAdminAction.DISCARD,
                OutboxAdminDisposition.DISCARDED,
                2L,
                OptionalLong.empty(),
                NOW,
                actor(),
                "because",
                3,
                3,
                "java.io.IOException",
                DestinationType.KAFKA,
                "topic",
                "order.placed",
                "Order",
                "o-1");
    }
}
