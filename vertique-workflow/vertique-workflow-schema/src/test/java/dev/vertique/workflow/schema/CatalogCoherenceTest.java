// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.ValidationPayloads;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that what the service describes and validates follows the workflow registry, not the schema cache that the
 * registration validator fills before the registry has decided whether to keep the definition.
 */
class CatalogCoherenceTest {

    private static final String SHARED_ID = "shared";
    private static final String REJECTED_ID = "rejected";

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    /**
     * A registry whose only validator is the schema validator followed by a check that rejects {@link
     * #REJECTED_ID}. The registry keeps its validators unordered, so one wrapper gives the order.
     */
    private DefaultWorkflowRegistry registryWithALaterValidator() {
        PayloadSchemaRegistrationValidator schema = new PayloadSchemaRegistrationValidator(
                PayloadSchemaTestProfiles.builtIn(),
                PayloadSchemaTestProfiles.VERTIQUE,
                Optional.of(Validations.BEAN_VALIDATOR),
                cache);
        WorkflowDefinitionValidator wrapper = candidate -> {
            schema.validate(candidate);
            if (REJECTED_ID.equals(candidate.plan().definitionId())) {
                throw new WorkflowDefinitionException("a later validator rejects this definition");
            }
        };
        return new DefaultWorkflowRegistry(null, Set.of(wrapper));
    }

    @Test
    @DisplayName("a rejected duplicate version leaves the first descriptor unchanged")
    void rejectedDuplicateVersionLeavesTheFirstDescriptorUnchanged() {
        // Given: a definition registered for (shared, 1) with a decision that requires alpha
        DefaultWorkflowRegistry registry = registryWithALaterValidator();
        registry.register(Definitions.withDecision(SHARED_ID, 1L, ValidationPayloads.FirstChoice.class));
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);
        Optional<WorkflowDefinitionDescriptor> before =
                schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, SHARED_ID, 1L));
        assertThat(before).isPresent();

        // When: another definition for the same id and version, whose decision requires beta, is registered
        assertThatThrownBy(() -> registry.register(
                        Definitions.withDecision(SHARED_ID, 1L, ValidationPayloads.SecondChoice.class)))
                .isInstanceOf(WorkflowDefinitionException.class);

        // Then: the descriptor is the one taken before the attempt
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, SHARED_ID, 1L)))
                .isEqualTo(before);

        // And: control, the duplicate's schema was cached before the registry refused it
        assertThat(cache.findByTypeName(ValidationPayloads.SecondChoice.class.getName()))
                .isPresent();

        // And: validation still runs against the first definition's schema
        assertThat(schemas.validateDecision(
                                new DefinitionKey(WorkflowScopes.DEFAULT, SHARED_ID, 1L),
                                "review",
                                "note",
                                new JsonObject().put("alpha", "a"))
                        .valid())
                .isTrue();
        assertThat(schemas.validateDecision(
                                new DefinitionKey(WorkflowScopes.DEFAULT, SHARED_ID, 1L),
                                "review",
                                "note",
                                new JsonObject().put("beta", "b"))
                        .violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("/alpha", "required"));
    }

    @Test
    @DisplayName("a definition rejected by a later validator is not describable or validatable")
    void definitionRejectedByALaterValidatorIsNotDescribable() {
        // Given: a registry whose later validator rejects the definition after the schema validator accepted it
        DefaultWorkflowRegistry registry = registryWithALaterValidator();
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When: that definition is registered
        assertThatThrownBy(() -> registry.register(
                        Definitions.withDecision(REJECTED_ID, 1L, ValidationPayloads.ShortComment.class)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("later validator");

        // Then: control, the cache holds the schema of its payload type
        assertThat(cache.findByTypeName(ValidationPayloads.ShortComment.class.getName()))
                .isPresent();

        // And: the definition is not describable
        assertThat(schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, REJECTED_ID, 1L)))
                .isEmpty();

        // And: it is not validatable, whatever the payload
        assertThat(schemas.validateDecision(
                                new DefinitionKey(WorkflowScopes.DEFAULT, REJECTED_ID, 1L),
                                "review",
                                "note",
                                new JsonObject().put("comment", "ok"))
                        .violations())
                .containsExactly(ViolationDetail.unlocated("definition is not registered", "definition"));
    }
}
