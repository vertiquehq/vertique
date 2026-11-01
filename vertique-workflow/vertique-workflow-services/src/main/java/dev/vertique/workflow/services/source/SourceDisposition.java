// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.security.SecurityContextSnapshot;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * What a transport does with a message once a source has handled it.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * @param fate the transport's action on the message
 * @param errorType the type of the failure that decided the fate, or {@code null} when there is none
 * @param security the security facts the source ran under, which are those of {@code dispatchAs} when
 *     it answered an identity and those of the trigger otherwise, or {@code null} when the disposition
 *     was not made by a run
 */
public record SourceDisposition(
        Fate fate, @Nullable String errorType, @Nullable SecurityContextSnapshot security) {

    /**
     * Creates a disposition that carries no security facts.
     *
     * @param fate the transport's action on the message
     * @param errorType the type of the failure that decided the fate, or {@code null} when there is none
     * @throws NullPointerException if {@code fate} is {@code null}
     */
    public SourceDisposition(Fate fate, @Nullable String errorType) {
        this(fate, errorType, null);
    }

    /**
     * Validates the components that are required.
     *
     * @throws NullPointerException if {@code fate} is {@code null}
     */
    public SourceDisposition {
        Objects.requireNonNull(fate, "fate");
    }

    /** The transport's action on a message. */
    public enum Fate {
        /** The message is finished: acknowledge it. */
        DONE,
        /** The message may succeed later: redeliver it. */
        RETRY,
        /** The message is fine and the system is busy: deliver it again without spending a retry. */
        DEFER,
        /** The message will never succeed: refuse it without retry. */
        TERMINAL
    }
}
