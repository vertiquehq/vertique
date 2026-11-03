// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.util.List;
import java.util.Optional;

/**
 * One page of an entry's administration history.
 *
 * @param items the entries of this page, newest first; an unmodifiable copy
 * @param next  position from which to read the following page; empty on the last page
 */
public record OutboxAdminHistoryPage(List<OutboxAdminHistoryEntry> items, Optional<OutboxAdminHistoryKey> next) {

    /**
     * Creates a page.
     *
     * @param items the entries of this page; copied, never {@code null}, no {@code null} element
     * @param next  position of the following page; {@code null} reads as empty
     * @throws dev.vertique.core.exception.ValidationException if {@code items} is absent or has a
     *         {@code null} element
     */
    public OutboxAdminHistoryPage {
        items = AdminChecks.copyOf(items, "items");
        next = AdminChecks.optional(next);
    }
}
