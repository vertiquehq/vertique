// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * The outcome of delivering a {@link CorrelatedSignal}.
 *
 * @param outcome whether the delivery was recorded or was a duplicate of one already recorded
 * @param definitionId the id of the definition of the instance the signal was recorded for; {@code null} for a
 *     {@link Outcome#DUPLICATE} delivery
 */
public record CorrelatedSignalReceipt(
        Outcome outcome, @Nullable String definitionId) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code outcome} is {@code null}
     * @throws IllegalArgumentException if a {@link Outcome#DUPLICATE} receipt carries a definition id
     */
    public CorrelatedSignalReceipt {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.DUPLICATE && definitionId != null) {
            throw new IllegalArgumentException("definitionId must be null for a duplicate delivery");
        }
    }

    /** What a delivery did. */
    public enum Outcome {
        /** The signal was recorded for an instance and an executor applies it afterwards. */
        RECORDED,
        /** A delivery with the same dedup key was already recorded, so nothing was recorded again. */
        DUPLICATE
    }
}
