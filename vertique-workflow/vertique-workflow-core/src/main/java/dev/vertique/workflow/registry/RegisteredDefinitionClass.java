// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

/**
 * The definition id and contract that one registered definition class stands for, as answered by
 * {@link WorkflowRegistry#definitionClass(Class)}.
 *
 * @param definitionId id of the workflow definition the class was registered under
 * @param contractName logical name of the definition's contract
 * @param contract the definition's contract interface
 */
public record RegisteredDefinitionClass(String definitionId, String contractName, Class<?> contract) {}
