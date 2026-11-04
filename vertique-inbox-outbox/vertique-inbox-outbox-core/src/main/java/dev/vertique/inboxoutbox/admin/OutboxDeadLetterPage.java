// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.util.List;
import java.util.Optional;

/**
 * One page of the dead-letter listing.
 *
 * @param items the summaries of this page in listing order; an unmodifiable copy
 * @param next  position from which to read the following page; empty on the last page
 */
public record OutboxDeadLetterPage(List<OutboxDeadLetterSummary> items, Optional<OutboxDeadLetterKey> next) {

    /**
     * Creates a page.
     *
     * @param items the summaries of this page; copied, never {@code null}, no {@code null} element
     * @param next  position of the following page; {@code null} reads as empty
     * @throws dev.vertique.core.exception.ValidationException if {@code items} is absent or has a
     *         {@code null} element
     */
    public OutboxDeadLetterPage {
        items = AdminChecks.copyOf(items, "items");
        next = AdminChecks.optional(next, "next");
    }
}
