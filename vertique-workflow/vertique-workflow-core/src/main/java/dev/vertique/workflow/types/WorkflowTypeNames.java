// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import java.util.Optional;

/**
 * Maps logical workflow names to classes and back.
 *
 * <p>Two namespaces exist: types (state and payload classes, named by {@link WorkflowType}) and
 * contracts (interfaces, named by {@code @WorkflowContract.value()}). A name is looked up first as a
 * logical name and then as the binary name of an indexed class. A lookup never loads a class by
 * name: only classes that were indexed can be found.
 */
public interface WorkflowTypeNames {

    /**
     * Returns the logical name of a state or payload class.
     *
     * @param type the class; must not be {@code null}
     * @return the {@link WorkflowType} value, or {@link Class#getName()} when the class carries no
     *     annotation
     */
    String nameOf(Class<?> type);

    /**
     * Resolves a type name to its class.
     *
     * @param name a logical name or the binary name of an indexed class
     * @return the class
     * @throws WorkflowUnknownTypeNameException if no indexed class has that name
     */
    Class<?> resolve(String name);

    /**
     * Looks up a type name.
     *
     * @param name a logical name or the binary name of an indexed class
     * @return the class, or empty when no indexed class has that name
     */
    Optional<Class<?>> find(String name);

    /**
     * Returns the logical name of a workflow contract.
     *
     * @param contract the contract interface; must not be {@code null}
     * @return the non-empty {@code @WorkflowContract.value()}, or {@link Class#getName()} otherwise
     */
    String contractNameOf(Class<?> contract);

    /**
     * Looks up a contract name.
     *
     * @param name a logical contract name or the binary name of an indexed contract
     * @return the contract interface, or empty when no indexed contract has that name
     */
    Optional<Class<?>> findContract(String name);
}
