// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.source;

import java.util.List;

/**
 * Source-agnostic SPI for supplying workflow definition documents at startup.
 *
 * <p>Implementations contribute zero or more {@link DefinitionResource} instances to the
 * definition pipeline. Each resource carries the raw document bytes, a format hint, and
 * provenance metadata.
 *
 * <p>Implementations are contributed via Dagger multibinding as
 * {@code Set<WorkflowDefinitionSource>} and consumed by
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionBootstrap} during startup.
 * The bootstrap iterates all sources, passes each resource through the parse → validate →
 * compile pipeline, and registers the resulting definitions into the
 * {@link dev.vertique.workflow.registry.WorkflowRegistry}.
 *
 * <p>Example registration:
 * <pre>{@code
 * @Provides @IntoSet
 * static WorkflowDefinitionSource fileSource(FileWorkflowSource source) {
 *     return source;
 * }
 * }</pre>
 *
 * <p>The {@link #resources()} method is called exactly once per bootstrap invocation.
 * Implementations should return a stable, ordered list. If the list order is significant
 * (e.g., for deterministic duplicate-detection error messages), implementations must ensure
 * a consistent iteration order.
 */
@FunctionalInterface
public interface WorkflowDefinitionSource {

    /**
     * Returns all definition resources that this source contributes.
     *
     * @return an ordered list of resources; may be empty; must not be {@code null}; must not
     *     contain {@code null} elements
     */
    List<DefinitionResource> resources();
}
