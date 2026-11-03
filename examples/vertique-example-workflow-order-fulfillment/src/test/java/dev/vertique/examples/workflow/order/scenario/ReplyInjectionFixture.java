// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.inboxoutbox.OutboxReplies;
import jakarta.inject.Inject;

/** Verifies a service can inject replies directly. */
final class ReplyInjectionFixture {
    final OutboxReplies replies;

    @Inject
    ReplyInjectionFixture(OutboxReplies replies) {
        this.replies = replies;
    }
}
