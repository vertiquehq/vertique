// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata projection that never carries document source text.
 *
 * <p>The {@code nameBinding} is written with the plan hash and only with it: it records the names the hash was
 * computed with, so that the same version keeps its hash when a class later gains or changes a logical name.
 *
 * <p>A {@link DefinitionKind#CODE} record belongs to the default scope and has no document format. The contract and
 * state type names are the logical names of the definition's contract and state type.
 *
 * @param key the scoped identity of the definition version
 * @param kind whether the definition comes from code or from a document
 * @param contractName the logical name of the definition's contract
 * @param stateTypeName the logical name of the definition's state type
 * @param planHash the plan hash, 64 lowercase hexadecimal characters
 * @param status the lifecycle status
 * @param provenance where the definition was loaded from
 * @param documentFormat the document format of a document definition, otherwise {@code null}
 * @param createdAt when the row was created
 * @param statusChangedAt when the status last changed
 * @param nameBinding the binary name of each state and payload class the version's plan names, mapped to the name its
 *     plan hash used; copied defensively; empty when no binding is kept
 * @param failure why the version is marked failing, or {@code null} when it is not; only a document definition of a
 *     scope other than the default one is ever marked
 * @param signalNames the signal names the version declares, as {@code {"instance": [names], "branches":
 *     {"<forkStep>/<branch>": [names]}}}; {@code null} when the row was written without schema production; copied
 *     defensively
 * @param startSchema the JSON Schema of the version's start payload; {@code null} when the row was written without
 *     schema production or the version declares no start payload; copied defensively
 */
public record WorkflowDefinitionRecord(
        DefinitionKey key,
        DefinitionKind kind,
        String contractName,
        String stateTypeName,
        String planHash,
        DefinitionStatus status,
        DefinitionProvenance provenance,
        @Nullable String documentFormat,
        Instant createdAt,
        Instant statusChangedAt,
        Map<String, String> nameBinding,
        @Nullable DefinitionFailure failure,
        @Nullable JsonObject signalNames,
        @Nullable JsonObject startSchema) {

    /**
     * Constructs a record that carries neither signal names nor a start schema.
     *
     * @param key the scoped identity of the definition version
     * @param kind whether the definition comes from code or from a document
     * @param contractName the logical name of the definition's contract
     * @param stateTypeName the logical name of the definition's state type
     * @param planHash the plan hash, 64 lowercase hexadecimal characters
     * @param status the lifecycle status
     * @param provenance where the definition was loaded from
     * @param documentFormat the document format of a document definition, otherwise {@code null}
     * @param createdAt when the row was created
     * @param statusChangedAt when the status last changed
     * @param nameBinding the name binding the plan hash was computed with
     * @param failure why the version is marked failing, or {@code null}
     */
    public WorkflowDefinitionRecord(
            DefinitionKey key,
            DefinitionKind kind,
            String contractName,
            String stateTypeName,
            String planHash,
            DefinitionStatus status,
            DefinitionProvenance provenance,
            @Nullable String documentFormat,
            Instant createdAt,
            Instant statusChangedAt,
            Map<String, String> nameBinding,
            @Nullable DefinitionFailure failure) {
        this(
                key,
                kind,
                contractName,
                stateTypeName,
                planHash,
                status,
                provenance,
                documentFormat,
                createdAt,
                statusChangedAt,
                nameBinding,
                failure,
                null,
                null);
    }

    public WorkflowDefinitionRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        DefinitionValues.identifier(contractName, "contractName", 255);
        DefinitionValues.identifier(stateTypeName, "stateTypeName", 255);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        nameBinding = Map.copyOf(nameBinding);
        signalNames = signalNames == null ? null : signalNames.copy();
        startSchema = startSchema == null ? null : startSchema.copy();
        if (planHash == null || !planHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("planHash must be 64 lowercase hexadecimal characters");
        if (kind == DefinitionKind.DOCUMENT) DefinitionValues.format(documentFormat);
        else if (documentFormat != null || !WorkflowScopes.DEFAULT.equals(key.scopeKey()))
            throw new IllegalArgumentException("CODE requires the default scope and no document format");
    }
}
