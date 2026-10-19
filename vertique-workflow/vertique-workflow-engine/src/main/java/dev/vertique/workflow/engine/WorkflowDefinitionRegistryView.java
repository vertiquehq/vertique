// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import java.util.List;

/**
 * A read-only view of what this node's registry has applied from the durable definition table.
 *
 * <p>The view belongs to one node. It lists the committed records, in a live status, whose executable form this
 * node holds in its registry, and it counts the rows that the periodic reconciliation currently fails to apply.
 * It is an observation of this node only; another node can legitimately differ until its own reconciliation ran.
 *
 * <p>The view is an operator view. {@link #registered()} lists the applied records of every owner, with their ids,
 * versions and provenance, so it must not be exposed to tenants without filtering it to the caller's own records.
 */
public interface WorkflowDefinitionRegistryView {

    /**
     * Returns the committed records this node has applied to its registry.
     *
     * <p>Only records in a live status (active, superseded or deprecated) are listed. A record that was retired,
     * or that this node has not applied yet, is absent.
     *
     * @return an immutable snapshot ordered by definition id, then owner (the global owner first), then version
     */
    List<WorkflowDefinitionRecord> registered();

    /**
     * Returns the number of rows that currently fail to apply on this node.
     *
     * <p>This is a gauge, not a running total: a row counts while its latest attempt failed, stops counting once
     * a later attempt succeeds, and stops counting when it is no longer in the table.
     *
     * @return the number of rows whose latest attempt failed
     */
    long applyFailures();
}
