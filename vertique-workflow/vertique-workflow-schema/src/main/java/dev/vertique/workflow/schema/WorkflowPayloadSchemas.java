// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Optional;

/**
 * Read and validation access to the payload schemas of registered workflow definitions.
 *
 * <p>The schemas are generated and checked when a definition is registered; this interface only reads them and validates payloads
 * against them. Every
 * method returns a future and obtains the plan through {@link dev.vertique.workflow.registry.WorkflowRegistry#load},
 * so a version that is not resident is built on demand off the event loop; nothing here blocks, loads a class or
 * generates a schema, so every method is safe on the event loop. Describing an unknown scope, definition id,
 * version, step or decision completes with an empty result, and validating a payload for one completes with a
 * single violation; neither fails for an unknown key. A version that cannot run on this node, because no row
 * supplies it or its stored source does not build, is unknown in the same way, and the cause is logged on the server
 * and never returned. A load that fails for any other reason fails the returned future with that failure. Reads and validation fail the future with {@link IllegalStateException} when a
 * registered definition has a payload type without a cached schema, which is a wiring fault: the schema module's
 * registration validator did not run for the registry.
 *
 * <p>The service reports what a definition declares; it does not decide who may see it. Callers authorize access to
 * the definition, and check that the scope of the key is one the caller may read, before calling and before exposing a
 * descriptor.
 */
public interface WorkflowPayloadSchemas {

    /**
     * Describes the start payload and the human task decisions of one pinned definition version.
     *
     * @param key the scope, definition id and version of the pin
     * @return a future of the descriptor, or of empty when the definition or the version is not registered in the scope
     * @throws IllegalStateException (failing the returned future) if the definition is registered but the schema module's registration validator did
     *     not run for the registry, so a payload type has no schema
     */
    Future<Optional<WorkflowDefinitionDescriptor>> describe(DefinitionKey key);

    /**
     * Describes one decision of a human task.
     *
     * @param key the scope, definition id and version of the pin
     * @param stepId the id of the human task step
     * @param decisionName the decision name
     * @return a future of the decision, or of empty when the definition, version, step or decision is not declared
     * @throws IllegalStateException (failing the returned future) if the decision's payload type has no schema because the schema module's
     *     registration validator did not run for the registry
     */
    Future<Optional<DecisionDescriptor>> decision(DefinitionKey key, String stepId, String decisionName);

    /**
     * Validates a decision payload against the schema registered for one decision of a human task.
     *
     * <p>The payload is checked against the compiled schema without generating, compiling or loading anything.
     * Pattern checks are bounded in input length, so an oversized string yields a bounded-input violation instead of
     * a match attempt. A payload that the schema accepts may still be rejected when it is bound, because the schema
     * allows an undeclared property that the strict binder refuses, and the engine may reject class-level or custom
     * constraints that a schema cannot express.
     *
     * <p>A decision without a payload type accepts only {@code null}; any other payload, an empty object included, is
     * one {@code payload} violation. A decision with a payload type rejects {@code null} with one {@code payload}
     * violation. An unknown definition or version yields one {@code definition} violation, and an unknown step or
     * decision yields one {@code decision} violation; the message is fixed and never echoes the caller's ids. The
     * submitted payload is not modified.
     *
     * @param key the scope, definition id and version of the pin
     * @param stepId the id of the human task step
     * @param decisionName the decision name
     * @param payload the submitted payload, or {@code null} when none was submitted
     * @return a future of the result; the result is never {@code null}
     * @throws IllegalStateException (failing the returned future) if the decision's payload type has no schema because the schema module's
     *     registration validator did not run for the registry
     */
    Future<PayloadValidationResult> validateDecision(
            DefinitionKey key, String stepId, String decisionName, @Nullable JsonObject payload);

    /**
     * Validates a start payload against the schema registered for the start type of one definition version.
     *
     * <p>The rules are those of
     * {@link #validateDecision(DefinitionKey, String, String, JsonObject)}. A definition that declares no start payload
     * type has nothing to validate and accepts any payload, {@code null} and an empty object included, matching the
     * engine, which passes the payload through. A start type declared as {@code void} or {@link Void} accepts only
     * {@code null}; any other payload is one {@code payload} violation.
     *
     * @param key the scope, definition id and version of the pin
     * @param payload the submitted payload, or {@code null} when none was submitted
     * @return a future of the result; the result is never {@code null}
     * @throws IllegalStateException (failing the returned future) if the start type has no schema because the schema module's registration
     *     validator did not run for the registry
     */
    Future<PayloadValidationResult> validateStart(DefinitionKey key, @Nullable JsonObject payload);
}
