// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a typed workflow contract that can be used with
 * {@code WorkflowClientFactory.create(Class)}.
 *
 * <p>The annotated interface declares methods for starting, signalling, querying, and optionally
 * cancelling a specific workflow definition. Methods must be annotated with
 * {@link WorkflowStart}, {@link WorkflowSignal}, or {@link WorkflowQuery}.
 *
 * <p>The {@code definitionId} and {@code definitionVersion} together identify the workflow
 * definition in the {@code WorkflowRegistry}. Proxy creation fails with
 * {@code WorkflowProxyContractException} if the definition is not registered.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowContract {

    /**
     * The workflow definition id this contract targets.
     *
     * @return the definition id; must match a registered definition
     */
    String definitionId();

    /**
     * The workflow definition version this contract targets.
     *
     * @return the definition version; must match a registered version
     */
    long definitionVersion();
}
