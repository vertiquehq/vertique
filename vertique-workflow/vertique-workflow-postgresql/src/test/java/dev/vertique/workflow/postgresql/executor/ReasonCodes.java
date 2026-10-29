// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

/**
 * The reason codes the executor stores in a command's {@code last_error}, as the proofs expect them. Each stored value
 * is {@code <CODE>: <message>}.
 */
final class ReasonCodes {

    /** A transient failure whose retry did not spend the attempt. */
    static final String TRANSIENT_FAILURE = "TRANSIENT_FAILURE";

    /** A deterministic failure of the command. */
    static final String DETERMINISTIC_FAILURE = "DETERMINISTIC_FAILURE";

    /** A segment that executed more nodes than the step cap. */
    static final String STEP_LIMIT = "STEP_LIMIT";

    /** A command that spent every attempt it was allowed. */
    static final String ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

    /** A command whose instance had ended when it was claimed. */
    static final String INSTANCE_ENDED = "INSTANCE_ENDED";

    /** A command settled because its instance was cancelled. */
    static final String INSTANCE_CANCELLED = "INSTANCE_CANCELLED";

    private ReasonCodes() {
        // constants
    }
}
