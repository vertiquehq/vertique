// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test view of {@link WorkflowPayloadSchemas} that waits for each returned future, so a case keeps the shape of a
 * direct call. A failed future rethrows its cause, which keeps exception assertions on the cause type.
 */
final class AwaitedSchemas {

    private static final long TIMEOUT_SECONDS = 10;

    private final WorkflowPayloadSchemas schemas;

    private AwaitedSchemas(WorkflowPayloadSchemas schemas) {
        this.schemas = schemas;
    }

    static AwaitedSchemas of(WorkflowRegistry registry, PayloadSchemaCache cache) {
        return new AwaitedSchemas(new DefaultWorkflowPayloadSchemas(registry, cache));
    }

    static AwaitedSchemas of(WorkflowRegistry registry, PayloadSchemaCache cache, WorkflowTypeNames typeNames) {
        return new AwaitedSchemas(new DefaultWorkflowPayloadSchemas(registry, cache, typeNames));
    }

    Optional<WorkflowDefinitionDescriptor> describe(DefinitionKey key) {
        return await(schemas.describe(key));
    }

    Optional<DecisionDescriptor> decision(DefinitionKey key, String stepId, String decisionName) {
        return await(schemas.decision(key, stepId, decisionName));
    }

    PayloadValidationResult validateDecision(
            DefinitionKey key, String stepId, String decisionName, @Nullable JsonObject payload) {
        return await(schemas.validateDecision(key, stepId, decisionName, payload));
    }

    PayloadValidationResult validateStart(DefinitionKey key, @Nullable JsonObject payload) {
        return await(schemas.validateStart(key, payload));
    }

    /**
     * Waits for a future and returns its value; a failure rethrows the cause.
     *
     * @param future the future
     * @param <T> the value type
     * @return the value
     */
    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("the future failed with a checked exception", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the future", e);
        } catch (TimeoutException e) {
            throw new AssertionError("the future did not complete within " + TIMEOUT_SECONDS + " seconds", e);
        }
    }
}
