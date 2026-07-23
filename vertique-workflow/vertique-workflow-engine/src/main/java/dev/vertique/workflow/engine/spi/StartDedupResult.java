// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;

/**
 * The result of an atomic start-dedup claim operation
 * ({@link WorkflowDedupRepository#claimOrResolveStart}).
 *
 * <p>This is a dialect-neutral SPI record: it carries only domain types so it can be shared by any
 * {@link WorkflowDedupRepository} implementation regardless of the backing database.
 *
 * @param workflowId          the winning workflow instance id — either the one this caller proposed
 *                            (when {@code didInsert=true}) or the one a prior caller already
 *                            committed (when {@code didInsert=false})
 * @param didInsert           {@code true} if this caller won the race and inserted the new dedup
 *                            row; {@code false} if the row already existed
 * @param existingFingerprint the fingerprint stored in the winning row — on insert this equals the
 *                            value just written; on conflict it equals the prior value. May be
 *                            {@code null} for legacy rows that predate version-pinning.
 */
public record StartDedupResult(WorkflowInstanceId workflowId, boolean didInsert, String existingFingerprint) {}
