// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.job;

/**
 * Immutable snapshot of a job's progress at a single point in time.
 *
 * @param jobId   the unique job identifier
 * @param seq     monotonically increasing sequence number for this event within the job stream
 * @param status  current status of the job at the time this event was emitted
 * @param percent completion percentage in the range [0, 100]
 * @param message optional human-readable description; may be {@code null} for progress events
 */
public record JobProgressEvent(String jobId, int seq, JobStatus status, int percent, String message) {}
