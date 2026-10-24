// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

/**
 * What a failure disposition that takes the execution lock came to.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 */
public enum DispositionResult {
    /** The command was still claimed with this token; its failure was written. */
    APPLIED,
    /** The command was no longer claimed with this token (it was settled or claimed again); nothing was written. */
    CLAIM_LOST,
    /** Another transaction holds the instance's execution lock; nothing was written and the caller may try again. */
    BUSY
}
