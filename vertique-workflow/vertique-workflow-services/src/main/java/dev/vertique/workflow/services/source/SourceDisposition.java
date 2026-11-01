// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import jakarta.annotation.Nullable;

/**
 * What a transport does with a message once a source has handled it.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * @param fate the transport's action on the message
 * @param errorType the type of the failure that decided the fate, or {@code null} when there is none
 */
public record SourceDisposition(Fate fate, @Nullable String errorType) {

    /** The transport's action on a message. */
    public enum Fate {
        /** The message is finished: acknowledge it. */
        DONE,
        /** The message may succeed later: redeliver it. */
        RETRY,
        /** The message will never succeed: refuse it without retry. */
        TERMINAL
    }
}
