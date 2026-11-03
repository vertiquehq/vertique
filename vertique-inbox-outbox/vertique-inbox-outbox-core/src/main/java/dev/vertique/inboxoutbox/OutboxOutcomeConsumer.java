// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import io.vertx.core.Future;

/** INTERNAL framework consumer for correlated outbox outcomes. */
public interface OutboxOutcomeConsumer {
    Future<Boolean> awaits(String handle);

    Future<Void> onOutcome(OutboxOutcome outcome);
}
