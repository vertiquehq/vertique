// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;

/**
 * What a typed workflow client is bound to: the definition it starts, the contract it speaks and the
 * optional scope and version selections made when the client was created.
 *
 * <p>A client created by {@link WorkflowClientFactory} carries one binding. Generated and reflective
 * proxies both build their start command through {@link #startCommand}, so the two always send the
 * same command and no proxy names the {@link StartCommand} constructor.
 *
 * @param definitionId id of the workflow definition the client starts; never blank
 * @param contractName logical name of the contract the client implements; never blank
 * @param startScope scope the started instances live in, or {@code null} to let the engine resolve it
 * @param targetScope scope whose definition version a start targets, or {@code null} to let the engine
 *     resolve it
 * @param version explicit definition version a start pins, or {@code null} to start the current version
 */
public record WorkflowClientBinding(
        String definitionId,
        String contractName,
        @Nullable String startScope,
        @Nullable String targetScope,
        @Nullable Long version) {

    /**
     * Validates the binding.
     *
     * @throws IllegalArgumentException if {@code definitionId} or {@code contractName} is {@code null}
     *     or blank, or {@code version} is not positive
     */
    public WorkflowClientBinding {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (contractName == null || contractName.isBlank()) {
            throw new IllegalArgumentException("contractName must not be blank");
        }
        if (version != null && version <= 0) {
            throw new IllegalArgumentException("version must be positive, was " + version);
        }
    }

    /**
     * Builds the command that starts an instance through this binding.
     *
     * <p>The command names the bound definition and contract and carries the bound version and scopes
     * unchanged; a {@code null} version or scope stays {@code null} for the engine to resolve.
     *
     * @param payload the start payload
     * @param idempotencyKey the caller-supplied idempotency key
     * @param businessKey the optional business key
     * @param subjectRef the optional subject reference
     * @return the start command; never {@code null}
     */
    public StartCommand startCommand(
            Object payload,
            String idempotencyKey,
            @Nullable String businessKey,
            @Nullable WorkflowSubjectRef subjectRef) {
        return new StartCommand(
                definitionId,
                contractName,
                payload,
                idempotencyKey,
                businessKey,
                subjectRef,
                version,
                startScope,
                targetScope);
    }
}
