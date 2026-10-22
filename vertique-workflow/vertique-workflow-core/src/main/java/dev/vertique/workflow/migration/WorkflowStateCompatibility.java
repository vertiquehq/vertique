// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import java.util.Optional;

/**
 * SPI that decides whether one workflow state class extends another.
 *
 * <p>The workflow registry asks it when a scope-owned definition and the default scope's definition of the
 * same id and contract name different state classes, and the engine asks it before it migrates an instance
 * to a definition whose state class differs from the instance's. A state class extends another when every
 * property of the source exists in the target with the same type, so the state JSON of the source reads as
 * the target. Added properties are allowed; removed or retyped properties are not. Nested objects compare
 * property by property by the same rule, collections compare their item type by the same rule, and an enum
 * target must contain every constant of the source.
 *
 * <p>A property that a target adds must tolerate absence in the stored state JSON: it reads as the type's
 * default. The comparison does not check that.
 *
 * <p>The {@code vertique-workflow-schema} module binds an implementation. Core declares the binding optional;
 * when none is bound, differing state classes are refused, never accepted.
 *
 * <p>Implementations are stateless or thread-safe and are called from any thread.
 */
public interface WorkflowStateCompatibility {

    /**
     * Reports the first property path at which {@code targetState} does not extend {@code sourceState}.
     *
     * <p>Paths are schema paths only: property names joined by {@code .}, with {@code []} after a collection
     * property (for example {@code address.city} or {@code items[].sku}). They never carry values.
     *
     * @param sourceState the state class that stored state was written with; never {@code null}
     * @param targetState the state class that must read that state; never {@code null}
     * @return the first incompatible property path, or {@link Optional#empty()} when {@code targetState}
     *     extends {@code sourceState}
     */
    Optional<String> firstIncompatibility(Class<?> sourceState, Class<?> targetState);
}
