// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

/**
 * Immutable metadata describing the workflow contract bound to a specific contract interface.
 *
 * <p>This record is stored in the {@code WorkflowRegistry} when a workflow definition is
 * registered. It links the contract interface class to the resolved definition id and version,
 * enabling the {@code WorkflowClientFactory} to locate the correct plan and runtime metadata when
 * creating a proxy.
 *
 * @param contractType the annotated contract interface class
 * @param definitionId the workflow definition id extracted from {@link WorkflowContract#definitionId()}
 * @param definitionVersion the workflow definition version extracted from
 *     {@link WorkflowContract#definitionVersion()}
 */
public record WorkflowContractMetadata(Class<?> contractType, String definitionId, long definitionVersion) {}
