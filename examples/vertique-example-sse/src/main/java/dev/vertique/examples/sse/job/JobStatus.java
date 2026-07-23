// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.job;

/**
 * Terminal and intermediate states of a job in the SSE progress feed.
 */
public enum JobStatus {

    /** Job has been created but not yet started. */
    PENDING,

    /** Job is actively executing and emitting progress events. */
    RUNNING,

    /** Job completed successfully. Terminal state. */
    DONE,

    /** Job terminated with an error. Terminal state. */
    FAILED
}
