// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.plan.WorkflowPlan;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Read-only view of a candidate workflow definition handed to each
 * {@link WorkflowDefinitionValidator}: the built plan plus the start payload class, which only the
 * registry knows. The view carries no callbacks.
 *
 * @param plan the validated plan of the candidate definition
 * @param startPayloadType the class passed to {@code init(...)} as the start payload type
 */
public record RegisteredWorkflowDefinition(WorkflowPlan plan, Class<?> startPayloadType) {}
