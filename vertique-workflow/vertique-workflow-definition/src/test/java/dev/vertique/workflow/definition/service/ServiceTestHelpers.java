// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.mockito.Mockito;

/**
 * Shared test helpers for the {@code service} test package.
 *
 * <p>Provides factory methods for creating test infrastructure that cannot be constructed directly
 * from outside their home package (e.g., {@link WorkflowDefinitionParser} which has a
 * package-private constructor).
 */
final class ServiceTestHelpers {

    private ServiceTestHelpers() {
        // Utility class — no instances.
    }

    /**
     * Builds a {@link WorkflowDefinitionParser} mock configured to perform real YAML and JSON
     * parsing using dedicated {@link ObjectMapper} instances, without requiring access to the
     * package-private constructor.
     *
     * @return a parser that behaves like the real implementation
     */
    static WorkflowDefinitionParser realParser() {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new ParameterNamesModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        ObjectMapper jsonMapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

        WorkflowDefinitionParser parser = Mockito.mock(WorkflowDefinitionParser.class);
        Mockito.doAnswer(invocation -> {
                    byte[] bytes = invocation.getArgument(0);
                    DocumentFormat format = invocation.getArgument(1);
                    ObjectMapper m = (format == DocumentFormat.YAML) ? yamlMapper : jsonMapper;
                    try {
                        return m.readValue(bytes, WorkflowDefinitionDocument.class);
                    } catch (IOException e) {
                        throw new WorkflowDefinitionParseException(
                                "Failed to parse workflow definition document (" + format + "): " + e.getMessage(), e);
                    }
                })
                .when(parser)
                .parse(Mockito.any(), Mockito.any());
        return parser;
    }

    /**
     * Awaits a completed service Future from a JUnit thread, preserving its underlying runtime
     * failure for the original assertion.
     *
     * @param future the service result
     * @param <T> the result type
     * @return the successful result
     */
    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a workflow definition service result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("Workflow definition service Future did not complete within five seconds", e);
        }
    }

    /**
     * The row a packaged document is recorded as.
     *
     * @param origin the retained packaged document
     * @return the available row of the document
     */
    static WorkflowDefinitionRecord recordOf(DocumentOrigin origin) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                origin.key(),
                DefinitionKind.DOCUMENT,
                origin.contractName(),
                origin.stateTypeName(),
                origin.planHash(),
                DefinitionStatus.AVAILABLE,
                origin.provenance(),
                origin.document().documentFormat(),
                now,
                now,
                origin.nameBinding(),
                null);
    }

    /**
     * Builds the plan of a retained packaged document the way the plan cache builds the plan of a stored one: from
     * its source text under the names its plan hash used. Nothing is registered.
     *
     * @param registry the framework registry whose rules and type names the build uses
     * @param loader the loader that compiles the document
     * @param definitionId the id of the packaged document
     * @param version the version of the packaged document
     * @return the built plan
     */
    static RuntimeWorkflow buildPackaged(
            WorkflowRegistry registry, DocumentDefinitionLoader loader, String definitionId, long version) {
        DocumentOrigin origin = loader.documentOrigins().stream()
                .filter(candidate -> candidate.key().definitionId().equals(definitionId)
                        && candidate.key().definitionVersion() == version)
                .findFirst()
                .orElseThrow();
        WorkflowDefinition<?, ?> definition = loader.load(recordOf(origin), origin.document());
        return ((DefaultWorkflowRegistry) registry).build(origin.key().scopeKey(), definition, origin.nameBinding());
    }
}
