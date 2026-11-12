// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.util.Objects;

/** INTERNAL framework control for a correlated reply; not an application contract. */
public record OutboxReplyControl(String handle, boolean completesLater) {
    public OutboxReplyControl {
        Objects.requireNonNull(handle, "handle");
    }
}
