// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

/**
 * The result of an atomic task-dedup claim operation
 * ({@link WorkflowDedupRepository#claimOrResolveTaskCompletion} /
 * {@link WorkflowDedupRepository#claimOrResolveTaskReassignment}).
 *
 * <p>This is a dialect-neutral SPI record: it carries only plain values so it can be shared by any
 * {@link WorkflowDedupRepository} implementation regardless of the backing database.
 *
 * <p>When {@code inserted=true} the caller is the first to claim this key and should proceed with
 * the operation. When {@code inserted=false} a prior committed transaction already held the row;
 * {@code existingFingerprint} carries the fingerprint stored by the winning operation so the caller
 * can distinguish an idempotent retry (fingerprints match) from a conflict (fingerprints differ).
 *
 * @param inserted            {@code true} iff this caller inserted the row (won the race)
 * @param existingFingerprint the fingerprint stored in the winning row — on insert this equals the
 *                            value just written; on conflict it equals the prior value
 */
public record DedupClaim(boolean inserted, String existingFingerprint) {}
