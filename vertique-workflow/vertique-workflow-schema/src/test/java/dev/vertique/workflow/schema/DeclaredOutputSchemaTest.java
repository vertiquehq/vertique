// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.ExpensePayloads;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the schema of a declared output: its description, its registration rule and the descriptor. */
class DeclaredOutputSchemaTest {

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    private static DefinitionKey key(String id) {
        return new DefinitionKey(WorkflowScopes.DEFAULT, id, 1L);
    }

    @Test
    @DisplayName("an output type whose schema root is a string is refused at registration, naming the output")
    void aStringOutputIsRefused() {
        // Given a registry with the schema validator
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);

        // When a definition that declares a String output is registered
        // Then registration is refused with the output's own wording
        assertThatThrownBy(() -> registry.register(Definitions.withOutput("string-output", 1L, String.class)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("definition 'string-output' v1 output type 'java.lang.String'")
                .hasMessageContaining("is not object-shaped (root type string)")
                .hasMessageContaining("wrap the value in a record");
    }

    @Test
    @DisplayName("an object-shaped output is accepted and describe returns its schema")
    void anObjectOutputIsDescribed() {
        // Given a registered definition whose output is an object type
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withOutput("receipt", 1L, ExpensePayloads.ApprovalComment.class));
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        // When it is described
        Optional<WorkflowDefinitionDescriptor> described = schemas.describe(key("receipt"));

        // Then the output schema is the generator's document for the output type
        assertThat(described).isPresent();
        assertThat(described.get().output()).isNotNull();
        assertThat(described.get().output().document())
                .isEqualTo(Registries.generated(ExpensePayloads.ApprovalComment.class));
    }

    @Test
    @DisplayName("a definition without an output describes none, and the four-argument descriptor has none")
    void noOutputDescribesNone() {
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.expense());
        AwaitedSchemas schemas = AwaitedSchemas.of(registry, cache);

        WorkflowDefinitionDescriptor described =
                schemas.describe(key("expense")).orElseThrow();

        assertThat(described.output()).isNull();
        assertThat(new WorkflowDefinitionDescriptor("id", 1L, null, List.of()).output())
                .isNull();
    }

    @Test
    @DisplayName("the producer returns the output schema of a plan that declares one, and null otherwise")
    void theProducerDescribesTheOutput() {
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withOutput("receipt", 1L, ExpensePayloads.ApprovalComment.class));
        registry.register(Definitions.expense());
        DefaultWorkflowPlanSchemaProducer producer = new DefaultWorkflowPlanSchemaProducer(cache);

        assertThat(producer.outputSchema(registry.resolve(key("receipt"))))
                .isEqualTo(Registries.generated(ExpensePayloads.ApprovalComment.class));
        assertThat(producer.outputSchema(registry.resolve(key("expense")))).isNull();
    }

    @Test
    @DisplayName("an output type the index does not know is refused as one that cannot be loaded")
    void anUnknownOutputTypeCannotBeLoaded() {
        CountingGenerator generator =
                new CountingGenerator(PayloadSchemaTestProfiles.builtIn(), PayloadSchemaTestProfiles.VERTIQUE);
        PayloadSchemaRegistrationValidator validator =
                new PayloadSchemaRegistrationValidator(cache, generator, TestTypeNames.lenient());

        assertThatThrownBy(() -> validator.validate(Definitions.handBuiltWithOutput("d", 1L, "com.example.Missing")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 output type 'com.example.Missing' cannot be loaded");
        assertThat(cache.snapshot()).isEmpty();
    }
}
