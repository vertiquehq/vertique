// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.json.JsonProfileId;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.Payloads;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies registration through the registry: a payload type that is not object-shaped keeps the definition out. */
class SchemaRegistrationTest {

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    @Test
    @DisplayName("a scalar decision type fails registration and leaves the definition unresolvable")
    void scalarDecisionTypeFailsRegistrationAndLeavesTheDefinitionUnresolvable() {
        // Given: a registry that carries the schema validator, and definitions with different decision types
        var scalar = Definitions.withDecision("scalar-saga", 1L, String.class);
        var alias = Definitions.withDecision("alias-saga", 1L, Payloads.RequiredAliasRecord.class);
        var sealed = Definitions.withDecision("sealed-saga", 1L, Payloads.SealedShape.class);

        // When: the String one is registered
        // Then: registration fails naming the id, version, step, decision and type, and nothing resolves
        var scalarRegistry = registry();
        assertThatThrownBy(() -> scalarRegistry.register(scalar))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'scalar-saga' v1 step 'review' decision 'note' payload type 'java.lang.String'"
                        + " is not object-shaped (root type string); wrap the value in a record");
        assertThatThrownBy(() -> scalarRegistry.resolveCurrent("scalar-saga"))
                .isInstanceOf(WorkflowDefinitionMissingException.class);

        // When: the one with a required alias is registered
        var aliasRegistry = registry();
        aliasRegistry.register(alias);

        // Then: it resolves
        assertThat(aliasRegistry.resolveCurrent("alias-saga").plan().definitionId())
                .isEqualTo("alias-saga");

        // When: the one with a polymorphic type is registered
        // Then: it fails as not object-shaped, with no root type
        var sealedRegistry = registry();
        assertThatThrownBy(() -> sealedRegistry.register(sealed))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'sealed-saga' v1 step 'review' decision 'note' payload type '"
                        + Payloads.SealedShape.class.getName()
                        + "' is not object-shaped (root type none); wrap the value in a record");
        assertThatThrownBy(() -> sealedRegistry.resolveCurrent("sealed-saga"))
                .isInstanceOf(WorkflowDefinitionMissingException.class);
    }

    @Test
    @DisplayName("a scalar start payload fails registration")
    void scalarStartPayloadFailsRegistration() {
        // Given: a registry with the schema validator and a definition that starts with a String
        var registry = registry();

        // When / Then: registration fails with the start payload wording
        assertThatThrownBy(() -> registry.register(Definitions.withStart("start-saga", 4L, String.class)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'start-saga' v4 start payload")
                .hasMessageContaining("payload type 'java.lang.String' is not object-shaped (root type string)");
        assertThatThrownBy(() -> registry.resolveCurrent("start-saga"))
                .isInstanceOf(WorkflowDefinitionMissingException.class);
    }

    @Test
    @DisplayName("object and void payload types register, and the generated entries are cached")
    void objectAndVoidPayloadTypesRegister() {
        // Given: a registry with the schema validator
        var registry = registry();

        // When: definitions with an object decision and with a void decision are registered
        registry.register(Definitions.withDecision("record-saga", 1L, Payloads.PlainRecord.class));
        registry.register(Definitions.withDecisionUnderSecondContract("void-saga", 1L, void.class));

        // Then: both resolve, and only the object type has an entry
        assertThat(registry.resolveCurrent("record-saga")).isNotNull();
        assertThat(registry.resolveCurrent("void-saga")).isNotNull();
        assertThat(cache.snapshot().keySet())
                .containsExactly(new SchemaKey(Payloads.PlainRecord.class.getName(), JsonProfileId.of("vertique")));
    }

    @Test
    @DisplayName("the cache keeps an entry even when the registry later rejects the candidate")
    void theCacheKeepsAnEntryWhenTheRegistryLaterRejectsTheCandidate() {
        // Given: a registry in which version 1 of a definition id names the Primary contract
        var registry = registry();
        registry.register(Definitions.withDecision("bound-saga", 1L, Payloads.PlainRecord.class));

        // When: version 2 of that id names another contract, passes the validator and is then rejected on publish
        assertThatThrownBy(() -> registry.register(
                        Definitions.withDecisionUnderSecondContract("bound-saga", 2L, Payloads.Comment.class)))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("'bound-saga' v2");

        // Then: the rejected candidate stays unresolvable while its payload entry remains cached
        assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "bound-saga", 2L)))
                .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertThat(cache.find(new SchemaKey(Payloads.Comment.class.getName(), JsonProfileId.of("vertique"))))
                .isPresent();
    }

    @Test
    @DisplayName("a raw Map decision type registers as an open object and accepts any object payload")
    void aRawMapDecisionTypeRegistersAsAnOpenObjectAndAcceptsAnyObjectPayload() {
        // Given: a registry with the schema validator and a definition whose decision type is the raw java.util.Map
        var registry = registry();

        // When: it is registered
        registry.register(Definitions.withDecision("map-saga", 1L, Map.class));

        // Then: it resolves
        assertThat(registry.resolveCurrent("map-saga").plan().definitionId()).isEqualTo("map-saga");

        // And: the cached document is an object schema
        assertThat(cache.findByTypeName(Map.class.getName())
                        .orElseThrow()
                        .document()
                        .getString("type"))
                .isEqualTo("object");

        // And: any object payload is accepted by the cached schema
        var schemas = new DefaultWorkflowPayloadSchemas(registry, cache);
        assertThat(schemas.validateDecision(
                                new DefinitionKey(WorkflowScopes.DEFAULT, "map-saga", 1L),
                                "review",
                                "note",
                                new JsonObject().put("anything", 1))
                        .valid())
                .isTrue();
    }

    private DefaultWorkflowRegistry registry() {
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                PayloadSchemaTestProfiles.builtIn(), PayloadSchemaTestProfiles.VERTIQUE, Optional.empty(), cache);
        return new DefaultWorkflowRegistry(null, Set.of(validator));
    }
}
