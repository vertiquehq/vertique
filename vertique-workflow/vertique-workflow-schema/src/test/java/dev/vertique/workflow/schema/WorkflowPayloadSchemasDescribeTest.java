// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.ExpensePayloads;
import dev.vertique.workflow.schema.fixtures.Payloads;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the descriptors assembled from a registered definition and the schema cache. */
class WorkflowPayloadSchemasDescribeTest {

    private static final String EXPENSE = "expense";
    private static final String PURCHASE = "purchase-approval";

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    @Test
    @DisplayName("describe returns start and decision schemas equal to the generator's document")
    void describeReturnsStartAndDecisionSchemasEqualToTheGeneratorsDocument() {
        // Given: a registry with the validator and the expense definition
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.expense());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When: the definition is described
        Optional<WorkflowDefinitionDescriptor> described =
                schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L));

        // Then: the descriptor is present and names the definition
        assertThat(described).isPresent();
        WorkflowDefinitionDescriptor descriptor = described.get();
        assertThat(descriptor.definitionId()).isEqualTo(EXPENSE);
        assertThat(descriptor.definitionVersion()).isEqualTo(1L);

        // And: the start payload document equals the generator's document for the start type
        assertThat(descriptor.startPayload()).isNotNull();
        assertThat(descriptor.startPayload().document())
                .isEqualTo(Registries.generated(ExpensePayloads.ExpenseStart.class));

        // And: one step review carries approve with the described comment property and reject without a payload
        assertThat(descriptor.humanTasks()).hasSize(1);
        HumanTaskStepDescriptor review = descriptor.humanTasks().get(0);
        assertThat(review.stepId()).isEqualTo("review");
        assertThat(review.decisions()).extracting(DecisionDescriptor::name).containsExactly("approve", "reject");

        DecisionDescriptor approve = review.decisions().get(0);
        assertThat(approve.nextStepId()).isEqualTo("done");
        JsonObject document = approve.payload().document();
        assertThat(document).isEqualTo(Registries.generated(ExpensePayloads.ApprovalComment.class));
        assertThat(document.getString("type")).isEqualTo("object");
        JsonObject comment = document.getJsonObject("properties").getJsonObject("comment");
        assertThat(comment.getInteger("maxLength")).isEqualTo(200);
        assertThat(comment.getString("description")).isEqualTo("Why");

        DecisionDescriptor reject = review.decisions().get(1);
        assertThat(reject.nextStepId()).isEqualTo("done");
        assertThat(reject.payload()).isNull();
    }

    @Test
    @DisplayName("describe and decision are keyed by the definition scope")
    void describeIsKeyedByTheDefinitionScope() {
        // Given: purchase-approval v1 in the default scope whose review offers approve, and purchase-approval v1
        // in the acme scope whose review offers approve and escalate
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        Map<String, Class<?>> defaultDecisions = Map.of("approve", ExpensePayloads.ApprovalComment.class);
        Map<String, Class<?>> acmeDecisions = new LinkedHashMap<>();
        acmeDecisions.put("approve", ExpensePayloads.ApprovalComment.class);
        acmeDecisions.put("escalate", ExpensePayloads.AuditNote.class);
        registry.register(Definitions.withDecisions(PURCHASE, 1L, defaultDecisions));
        registry.register("acme", Definitions.withDecisions(PURCHASE, 1L, acmeDecisions));
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);
        DefinitionKey defaultKey = new DefinitionKey(WorkflowScopes.DEFAULT, PURCHASE, 1L);
        DefinitionKey acmeKey = new DefinitionKey("acme", PURCHASE, 1L);
        DefinitionKey globexKey = new DefinitionKey("globex", PURCHASE, 1L);

        // When: each key is described and asked for the decision only the acme version declares
        WorkflowDefinitionDescriptor defaultDescriptor =
                schemas.describe(defaultKey).orElseThrow();
        WorkflowDefinitionDescriptor acmeDescriptor = schemas.describe(acmeKey).orElseThrow();

        // Then: the default descriptor lists one decision and the acme descriptor two
        assertThat(defaultDescriptor.humanTasks().get(0).decisions())
                .extracting(DecisionDescriptor::name)
                .containsExactly("approve");
        assertThat(acmeDescriptor.humanTasks().get(0).decisions())
                .extracting(DecisionDescriptor::name)
                .containsExactly("approve", "escalate");

        // and: the escalate decision is found for the acme key only
        assertThat(schemas.decision(acmeKey, "review", "escalate")).isPresent();
        assertThat(schemas.decision(defaultKey, "review", "escalate")).isEmpty();

        // and: validation follows the key too, so a payload for escalate is accepted in acme and unknown in default
        JsonObject note = new JsonObject().put("auditor", "a").put("finding", "f");
        assertThat(schemas.validateDecision(acmeKey, "review", "escalate", note).valid())
                .isTrue();
        assertThat(schemas.validateDecision(defaultKey, "review", "escalate", note)
                        .violations())
                .hasSize(1);

        // and: a scope that holds nothing of the id describes as empty and validates as one violation
        assertThat(schemas.describe(globexKey)).isEmpty();
        assertThat(schemas.decision(globexKey, "review", "approve")).isEmpty();
        assertThat(schemas.validateStart(globexKey, null).violations()).hasSize(1);
        assertThat(schemas.validateStart(acmeKey, null).valid()).isTrue();
    }

    @Test
    @DisplayName("an unknown definition id, version, step or decision is empty and never throws")
    void unknownIdOrVersionReturnsEmpty() {
        // Given: the registry of the expense definition
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.expense());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When: an unknown id, an unknown version, an unknown step and an unknown decision are asked for
        // Then: every result is empty and nothing is thrown
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "nope", 1L)))
                .isEmpty();
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 99L)))
                .isEmpty();
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "nope", "approve"))
                .isEmpty();
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "nope"))
                .isEmpty();

        // And: the known coordinates still resolve
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "approve"))
                .isPresent();
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "reject"))
                .isPresent();
    }

    @Test
    @DisplayName("reads never load a class through the registration class loader")
    void readsNeverLoadClasses() {
        // Given: a validator constructed through the class loader seam with a counting loader, and a registered
        // definition whose registration loaded the decision payload class through that loader
        CountingClassLoader loader = new CountingClassLoader(getClass().getClassLoader());
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache, loader);
        registry.register(Definitions.expense());
        assertThat(loader.loads.get()).isPositive();
        loader.loads.set(0);
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When: describe and decision run for the registered key and for unknown keys, with the counting loader as
        // the thread's context loader as well
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L)))
                    .isPresent();
            assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "approve"))
                    .isPresent();
            assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "reject"))
                    .isPresent();
            assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "nope", 1L)))
                    .isEmpty();
            assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 99L)))
                    .isEmpty();
            assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "nope"))
                    .isEmpty();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }

        // Then: the loader was asked for no class
        assertThat(loader.loads.get()).isZero();
    }

    @Test
    @DisplayName("a void decision has a null payload and documents are defensive copies")
    void voidDecisionHasNullPayloadAndDocumentsAreDefensiveCopies() {
        // Given: the expense descriptor
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.expense());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);
        List<DecisionDescriptor> decisions = schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L))
                .orElseThrow()
                .humanTasks()
                .get(0)
                .decisions();
        DecisionDescriptor approve = decisions.get(0);
        DecisionDescriptor reject = decisions.get(1);
        JsonObject original = approve.payload().document().copy();

        // When: the reject decision is read and the caller mutates approve's document and its start document
        // Then: reject has no payload
        assertThat(reject.name()).isEqualTo("reject");
        assertThat(reject.payload()).isNull();
        approve.payload().document().put("mutated", true);
        approve.payload().document().getJsonObject("properties").clear();

        // And: neither the same descriptor nor a second describe shows the mutation
        assertThat(approve.payload().document()).isEqualTo(original);
        DecisionDescriptor again = schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L))
                .orElseThrow()
                .humanTasks()
                .get(0)
                .decisions()
                .get(0);
        assertThat(again.name()).isEqualTo("approve");
        assertThat(again.payload().document()).isEqualTo(original);
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "approve")
                        .orElseThrow()
                        .payload()
                        .document())
                .isEqualTo(original);
    }

    @Test
    @DisplayName("human tasks follow plan order and decisions follow declaration order")
    void humanTasksFollowPlanOrderAndDecisionsFollowDeclarationOrder() {
        // Given: a definition whose tasks and decisions are declared against alphabetical order
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.expenseWithAudit());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When: it is described
        WorkflowDefinitionDescriptor descriptor = schemas.describe(
                        new DefinitionKey(WorkflowScopes.DEFAULT, "expense-audited", 1L))
                .orElseThrow();

        // Then: tasks come in plan order and each task's decisions in declaration order, with their next steps
        assertThat(descriptor.humanTasks())
                .extracting(HumanTaskStepDescriptor::stepId)
                .containsExactly("review", "audit");
        HumanTaskStepDescriptor review = descriptor.humanTasks().get(0);
        assertThat(review.decisions()).extracting(DecisionDescriptor::name).containsExactly("reject", "approve");
        assertThat(review.decisions())
                .extracting(DecisionDescriptor::nextStepId)
                .containsExactly("done", "audit");
        assertThat(review.decisions().get(0).payload()).isNull();
        HumanTaskStepDescriptor audit = descriptor.humanTasks().get(1);
        assertThat(audit.decisions()).extracting(DecisionDescriptor::name).containsExactly("flag", "confirm");
        assertThat(audit.decisions().get(1).payload().document())
                .isEqualTo(Registries.generated(ExpensePayloads.AuditNote.class));

        // And: the keyed lookup finds a decision of the second task
        assertThat(schemas.decision(
                        new DefinitionKey(WorkflowScopes.DEFAULT, "expense-audited", 1L), "audit", "confirm"))
                .hasValueSatisfying(
                        decision -> assertThat(decision.nextStepId()).isEqualTo("done"));
    }

    @Test
    @DisplayName("a void start has a null start payload and a void decision has a null payload")
    void voidStartAndVoidDecisionHaveNullPayloads() {
        // Given: a definition with a void start and an object decision, and another with only void payloads
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withDecision("void-start", 1L, Payloads.PlainRecord.class));
        registry.register(Definitions.withDecisionUnderSecondContract("all-void", 1L, void.class));
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When / Then: the void start has no payload while its decision does
        WorkflowDefinitionDescriptor voidStart = schemas.describe(
                        new DefinitionKey(WorkflowScopes.DEFAULT, "void-start", 1L))
                .orElseThrow();
        assertThat(voidStart.startPayload()).isNull();
        assertThat(voidStart.humanTasks().get(0).decisions().get(0).payload()).isNotNull();

        // And: the all-void definition has no payload anywhere
        WorkflowDefinitionDescriptor voidOnly = schemas.describe(
                        new DefinitionKey(WorkflowScopes.DEFAULT, "all-void", 1L))
                .orElseThrow();
        assertThat(voidOnly.startPayload()).isNull();
        assertThat(voidOnly.humanTasks().get(0).decisions().get(0).payload()).isNull();
    }

    @Test
    @DisplayName("a definition the registry rejected is not described")
    void aRejectedDefinitionIsNotDescribed() {
        // Given: a registry that rejects a definition with a scalar decision payload
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        assertThatThrownBy(() -> registry.register(Definitions.withDecision("scalar", 1L, String.class)))
                .isInstanceOf(WorkflowDefinitionException.class);
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When / Then: neither the definition nor its decision can be read
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "scalar", 1L)))
                .isEmpty();
        assertThat(schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, "scalar", 1L), "review", "note"))
                .isEmpty();
    }

    @Test
    @DisplayName("a registered definition without a cache entry is an invariant violation that names the remedy")
    void registeredDefinitionWithoutCacheEntryFailsWithAnActionableMessage() {
        // Given: a registry without the schema validator, so no entry was ever generated, and a registered definition
        DefaultWorkflowRegistry registry = Registries.withoutSchemaValidator();
        registry.register(Definitions.expense());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);
        String remedy = "the schema module's registration validator did not run for this registry:"
                + " install WorkflowSchemaModule in the component that provides WorkflowRegistry";

        // When / Then: describe fails with the remedy and the missing type name
        assertThatThrownBy(() -> schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(remedy)
                .hasMessageContaining(ExpensePayloads.ExpenseStart.class.getName());

        // And: so does the keyed decision lookup, naming the decision's type
        assertThatThrownBy(() ->
                        schemas.decision(new DefinitionKey(WorkflowScopes.DEFAULT, EXPENSE, 1L), "review", "approve"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(remedy)
                .hasMessageContaining(ExpensePayloads.ApprovalComment.class.getName());

        // And: unknown coordinates are still empty rather than a violation
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "nope", 1L)))
                .isEmpty();
    }

    @Test
    @DisplayName("describe loads a version that is not resident and completes after the plan is loaded")
    void describeLoadsANonResidentVersion() {
        // Given: a plan for acme/order v2 built elsewhere, and a registry that does not hold it and loads it on a
        // worker thread once released; the plan's review step offers approve
        DefaultWorkflowRegistry source = Registries.withSchemaValidator(cache);
        source.register(
                "acme",
                Definitions.withDecisions("order", 2L, Map.of("approve", ExpensePayloads.ApprovalComment.class)));
        DefinitionKey key = new DefinitionKey("acme", "order", 2L);
        DeferredRegistry registry = DeferredRegistry.loadsLater(key, source.resolve(key));
        WorkflowPayloadSchemas schemas = new DefaultWorkflowPayloadSchemas(registry, cache);

        // When: the version is described
        Future<Optional<WorkflowDefinitionDescriptor>> described = schemas.describe(key);

        // Then: the future is not complete when the call returns
        assertThat(described.isComplete()).isFalse();

        // And: once the load finishes it completes with a descriptor listing review and approve
        registry.release();
        WorkflowDefinitionDescriptor descriptor =
                AwaitedSchemas.await(described).orElseThrow();
        assertThat(descriptor.definitionId()).isEqualTo("order");
        assertThat(descriptor.definitionVersion()).isEqualTo(2L);
        assertThat(descriptor.humanTasks())
                .extracting(HumanTaskStepDescriptor::stepId)
                .containsExactly("review");
        assertThat(descriptor.humanTasks().get(0).decisions())
                .extracting(DecisionDescriptor::name)
                .containsExactly("approve");

        // And: the plan came from exactly one load and was never looked up in the resident set
        assertThat(registry.loads()).isEqualTo(1);
        assertThat(registry.lookups()).isZero();
    }

    @Test
    @DisplayName("an absent row completes describe and decision empty and validation with a definition violation")
    void anAbsentRowCompletesEmptyAndValidationReportsTheDefinition() {
        // Given: a registry whose load of the key fails because no row supplies the version
        DefinitionKey key = new DefinitionKey("acme", "order", 3L);
        DeferredRegistry registry = DeferredRegistry.failsLater(key, new WorkflowDefinitionNotFoundException(key));
        registry.release();
        WorkflowPayloadSchemas schemas = new DefaultWorkflowPayloadSchemas(registry, cache);

        // When / Then: describe and decision complete with an empty result rather than failing
        assertThat(AwaitedSchemas.await(schemas.describe(key))).isEmpty();
        assertThat(AwaitedSchemas.await(schemas.decision(key, "review", "approve")))
                .isEmpty();

        // And: validation answers the single definition violation of an unknown version
        PayloadValidationResult decision =
                AwaitedSchemas.await(schemas.validateDecision(key, "review", "approve", new JsonObject()));
        assertThat(decision.violations()).hasSize(1);
        assertThat(decision.violations().get(0).type()).isEqualTo("definition");
        PayloadValidationResult start = AwaitedSchemas.await(schemas.validateStart(key, null));
        assertThat(start.violations()).hasSize(1);
        assertThat(start.violations().get(0).type()).isEqualTo("definition");
    }

    @Test
    @DisplayName("a load failure other than an absent row fails the returned future")
    void aLoadFailureFailsTheFuture() {
        // Given: a registry whose load fails with a persistence error
        DefinitionKey key = new DefinitionKey("acme", "order", 2L);
        IllegalStateException cause = new IllegalStateException("database unavailable");
        DeferredRegistry registry = DeferredRegistry.failsLater(key, cause);
        registry.release();
        WorkflowPayloadSchemas schemas = new DefaultWorkflowPayloadSchemas(registry, cache);

        // When / Then: every method fails its future with that cause instead of answering empty
        assertThatThrownBy(() -> AwaitedSchemas.await(schemas.describe(key))).isSameAs(cause);
        assertThatThrownBy(() -> AwaitedSchemas.await(schemas.decision(key, "review", "approve")))
                .isSameAs(cause);
        assertThatThrownBy(() -> AwaitedSchemas.await(schemas.validateDecision(key, "review", "approve", null)))
                .isSameAs(cause);
        assertThatThrownBy(() -> AwaitedSchemas.await(schemas.validateStart(key, null)))
                .isSameAs(cause);
    }

    /** A class loader that delegates to its parent and counts every class it is asked to load. */
    static final class CountingClassLoader extends ClassLoader {

        final AtomicInteger loads = new AtomicInteger();

        CountingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            loads.incrementAndGet();
            return super.loadClass(name, resolve);
        }
    }
}
