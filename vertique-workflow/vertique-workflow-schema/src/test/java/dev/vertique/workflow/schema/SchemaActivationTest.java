// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.engine.testsupport.NoOpWorkflowDefinitionLifecycle;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.Payloads;
import dev.vertique.workflow.schema.fixtures.ReviewNoteWorkflowV1;
import dev.vertique.workflow.schema.fixtures.ReviewState;
import dev.vertique.workflow.schema.fixtures.StartReview;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies activation of a stored definition through the definition service: the schema validator rejects the
 * candidate exactly as it does at registration, and the failed activation can be retried.
 */
class SchemaActivationTest {

    private static final DefinitionKey REVIEW_NOTE_V1 = new DefinitionKey(WorkflowScopes.DEFAULT, "review-note", 1L);

    private static final DefinitionFilter GLOBAL = new DefinitionFilter(WorkflowScopes.DEFAULT, null, null);

    private static final String SCALAR_DECISION_MESSAGE =
            "definition 'review-note' v1 step 'review' decision 'note' payload type 'java.lang.String'"
                    + " is not object-shaped (root type string); wrap the value in a record";

    // --- Wiring ---

    /** Everything the schema module needs from the application, plus the callbacks the document names. */
    @Module
    static final class ApplicationModule {

        private ApplicationModule() {}

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return PayloadSchemaTestProfiles.builtIn();
        }

        @Provides
        static JsonConfig jsonConfig() {
            return PayloadSchemaTestProfiles.VERTIQUE;
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor startStateMappers() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                            "review.init", StartReview.class, ReviewState.class, start -> new ReviewState(start.id())))
                    .register(new NamedStartStateMapper<>(
                            "review.initFromText", String.class, ReviewState.class, ReviewState::new));
        }

        /** Lists the classes the loaded documents name; a service-loaded document resolves names through the catalog only. */
        @Provides
        @IntoSet
        static WorkflowTypeCatalog typeCatalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, ReviewNoteWorkflowV1.class.getName(), ReviewNoteWorkflowV1.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, ReviewState.class.getName(), ReviewState.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, StartReview.class.getName(), StartReview.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, String.class.getName(), String.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.TYPE, Payloads.PlainRecord.class.getName(), Payloads.PlainRecord.class));
        }

        @Provides
        @IntoSet
        static StateReducerContributor stateReducers() {
            return builder -> builder.register(
                    new NamedStateReducer<>("review.apply", ReviewState.class, (state, payload) -> state));
        }
    }

    /**
     * Binds an in-memory registry store and a lifecycle that publishes the way the engine's lifecycle does: it claims
     * the stored candidate, restores its definition through the loader, builds its plan, and completes the claim, or
     * releases the claim when the build fails.
     */
    @Module
    static final class RegistryModule {

        private RegistryModule() {}

        @Provides
        @Singleton
        static WorkflowDefinitionRegistryStore registryStore() {
            return new InMemoryWorkflowDefinitionRegistryStore();
        }

        @Provides
        @Singleton
        static WorkflowDefinitionLifecycle lifecycle(
                WorkflowDefinitionRegistryStore store,
                Set<PersistedDefinitionLoader> loaders,
                Provider<WorkflowRegistry> registry) {
            return new BuildingLifecycle(store, loaders, registry);
        }
    }

    /** Publishes a stored candidate after building its plan, which runs the registration validators. */
    private static final class BuildingLifecycle extends NoOpWorkflowDefinitionLifecycle {
        private final WorkflowDefinitionRegistryStore store;
        private final Set<PersistedDefinitionLoader> loaders;
        private final Provider<WorkflowRegistry> registry;

        BuildingLifecycle(
                WorkflowDefinitionRegistryStore store,
                Set<PersistedDefinitionLoader> loaders,
                Provider<WorkflowRegistry> registry) {
            this.store = store;
            this.loaders = loaders;
            this.registry = registry;
        }

        @Override
        public Future<Void> publish(DefinitionKey key) {
            return store.claim(key).compose(claim -> store.find(key)
                    .compose(row -> store.findDocument(key).compose(document -> {
                        try {
                            WorkflowDefinition<?, ?> definition = loaders.stream()
                                    .filter(loader ->
                                            loader.supports(row.orElseThrow().documentFormat()))
                                    .findFirst()
                                    .orElseThrow()
                                    .load(row.orElseThrow(), document.orElseThrow());
                            ((DefaultWorkflowRegistry) registry.get()).build(key.scopeKey(), definition, Map.of());
                        } catch (RuntimeException failure) {
                            return store.release(claim).<Void>transform(released -> Future.failedFuture(failure));
                        }
                        return store.complete(claim);
                    })));
        }
    }

    @Singleton
    @Component(
            modules = {
                WorkflowCoreModule.class,
                WorkflowDefinitionModule.class,
                RegistryModule.class,
                WorkflowSchemaModule.class,
                ApplicationModule.class
            })
    interface Platform {

        WorkflowDefinitionService definitions();

        WorkflowRegistry registry();
    }

    // --- Tests ---

    @Test
    @DisplayName("activation through the definition service fails the same way")
    void activationThroughTheDefinitionServiceFailsTheSameWay() {
        // Given: a stored candidate whose human task declares payloadType java.lang.String
        Platform platform = DaggerSchemaActivationTest_Platform.create();
        WorkflowDefinitionService definitions = platform.definitions();
        await(definitions.load(
                WorkflowScopes.DEFAULT,
                document(StartReview.class.getName(), "review.init", String.class.getName()),
                DocumentFormat.YAML,
                metadata()));

        // When: it is activated
        // Then: activation fails with the registration wording and the registry does not resolve the id
        assertThatThrownBy(() -> await(definitions.publish(REVIEW_NOTE_V1)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage(SCALAR_DECISION_MESSAGE);
        assertThatThrownBy(
                        () -> platform.registry().resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "review-note", 1L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
    }

    @Test
    @DisplayName("a failed activation leaves the candidate retryable with the same outcome")
    void aFailedActivationLeavesTheCandidateRetryable() {
        // Given: a stored candidate with a scalar decision type
        Platform platform = DaggerSchemaActivationTest_Platform.create();
        WorkflowDefinitionService definitions = platform.definitions();
        await(definitions.load(
                WorkflowScopes.DEFAULT,
                document(StartReview.class.getName(), "review.init", String.class.getName()),
                DocumentFormat.YAML,
                metadata()));

        // When: activation fails once
        assertThatThrownBy(() -> await(definitions.publish(REVIEW_NOTE_V1)))
                .isInstanceOf(WorkflowDefinitionException.class);

        // Then: the candidate is still a candidate, and a second activation fails the same way
        assertThat(await(definitions.list(GLOBAL)))
                .extracting(WorkflowDefinitionRecord::status)
                .containsExactly(DefinitionStatus.CANDIDATE);
        assertThatThrownBy(() -> await(definitions.publish(REVIEW_NOTE_V1)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage(SCALAR_DECISION_MESSAGE);
    }

    @Test
    @DisplayName("a scalar start payload type fails activation with the start payload wording")
    void aScalarStartPayloadTypeFailsActivation() {
        // Given: a stored candidate that starts with a String
        Platform platform = DaggerSchemaActivationTest_Platform.create();
        WorkflowDefinitionService definitions = platform.definitions();
        await(definitions.load(
                WorkflowScopes.DEFAULT,
                document(String.class.getName(), "review.initFromText", Payloads.PlainRecord.class.getName()),
                DocumentFormat.YAML,
                metadata()));

        // When / Then: activation fails naming the start payload
        assertThatThrownBy(() -> await(definitions.publish(REVIEW_NOTE_V1)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'review-note' v1 start payload")
                .hasMessageContaining("payload type 'java.lang.String' is not object-shaped (root type string)");
    }

    @Test
    @DisplayName("a candidate with object payload types activates")
    void aCandidateWithObjectPayloadTypesActivates() {
        // Given: a stored candidate whose decision type is a record
        Platform platform = DaggerSchemaActivationTest_Platform.create();
        WorkflowDefinitionService definitions = platform.definitions();
        await(definitions.load(
                WorkflowScopes.DEFAULT,
                document(StartReview.class.getName(), "review.init", Payloads.PlainRecord.class.getName()),
                DocumentFormat.YAML,
                metadata()));

        // When: it is activated
        await(definitions.publish(REVIEW_NOTE_V1));

        // Then: the service reports it active
        assertThat(await(definitions.list(GLOBAL)))
                .extracting(WorkflowDefinitionRecord::status)
                .containsExactly(DefinitionStatus.AVAILABLE);
    }

    // --- Helpers ---

    /** Blocks until the future completes and rethrows its original failure rather than a wrapper. */
    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting a definition operation", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("definition operation did not complete in time", e);
        }
    }

    private static SourceMetadata metadata() {
        return new SourceMetadata("test", null, null, null, null, Instant.now());
    }

    /** The review workflow document with the three type-related placeholders filled in. */
    private static byte[] document(String startPayloadType, String initialStateMapper, String decisionPayloadType) {
        try (InputStream resource =
                SchemaActivationTest.class.getResourceAsStream("/definitions/review-note.workflow.yaml")) {
            String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("START_PAYLOAD_TYPE", startPayloadType)
                    .replace("INITIAL_STATE_MAPPER", initialStateMapper)
                    .replace("DECISION_PAYLOAD_TYPE", decisionPayloadType)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the review workflow document", e);
        }
    }
}
