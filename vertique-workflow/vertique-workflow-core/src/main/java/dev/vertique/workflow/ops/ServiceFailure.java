// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import java.util.Objects;

/** A service call failure without exception details. */
public record ServiceFailure(Reason reason, int attempts) {
    public ServiceFailure {
        Objects.requireNonNull(reason);
    }

    public enum Reason {
        RETRIES_EXHAUSTED,
        PERMANENT,
        REPLY_REFUSED,
        REPORTED
    }
}
