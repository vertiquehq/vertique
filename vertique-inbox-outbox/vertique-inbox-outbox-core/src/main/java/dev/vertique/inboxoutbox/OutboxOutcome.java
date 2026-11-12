// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import jakarta.annotation.Nullable;
import java.util.Map;

/** INTERNAL outcome carried from an outbox destination to a framework consumer. */
public sealed interface OutboxOutcome {
    long entryId();

    String destination();

    String handle();

    Map<String, Object> dispatchContext();

    int attempts();

    record Success(
            long entryId,
            String destination,
            String handle,
            Map<String, Object> dispatchContext,
            int attempts,
            @Nullable Object value)
            implements OutboxOutcome {}

    record Failure(
            long entryId,
            String destination,
            String handle,
            Map<String, Object> dispatchContext,
            int attempts,
            Kind kind)
            implements OutboxOutcome {
        public enum Kind {
            RETRIES_EXHAUSTED,
            PERMANENT
        }
    }
}
