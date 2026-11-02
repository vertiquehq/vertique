// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import io.vertx.core.Future;

/** INTERNAL framework consumer for correlated outbox outcomes. */
public interface OutboxOutcomeConsumer {
    String HANDLE_HEADER = "x-outbox-reply-handle";
    String MODE_HEADER = "x-outbox-reply-mode";
    String MODE_COMPLETES_LATER = "completes-later";

    Future<Boolean> awaits(String handle);

    Future<Void> onOutcome(OutboxOutcome outcome);
}
