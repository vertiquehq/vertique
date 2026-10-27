// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.OrderedPayloads;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the property order of described payloads: the profile mapper's deserialization order first, then every
 * other key of the document's top-level {@code properties} in the document's own (sorted) order, each key once.
 */
class PropertyOrderTest {

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    @Test
    @DisplayName("record components come in declaration order while properties stay canonical")
    void recordComponentsComeInDeclarationOrderWhilePropertiesStayCanonical() {
        // Given: a record whose declaration order differs from the sorted order, registered as a start type
        // When: the start payload is described
        PayloadSchema schema = startPayloadOf(OrderedPayloads.Expense.class);

        // Then: the property order is the declaration order while the document's keys iterate sorted
        assertThat(schema.propertyOrder()).containsExactly("requester", "amountCents", "department");
        assertThat(propertyKeys(schema)).containsExactly("amountCents", "department", "requester");
    }

    @Test
    @DisplayName("@JsonPropertyOrder leads and every remaining property follows exactly once")
    void jsonPropertyOrderLeadsAndEveryRemainingPropertyFollowsOnce() {
        // Given: a record with an explicit order for two of its four properties
        // When: it is described
        PayloadSchema schema = startPayloadOf(OrderedPayloads.Ordered.class);

        // Then: the explicit names lead, the other two follow once each, and the keys are the document's keys
        assertThat(schema.propertyOrder()).startsWith("department", "requester");
        assertThat(schema.propertyOrder()).containsExactlyInAnyOrder("department", "requester", "amountCents", "note");
        assertThat(schema.propertyOrder()).hasSize(4);
        assertThat(schema.propertyOrder()).containsExactlyInAnyOrderElementsOf(propertyKeys(schema));
    }

    @Test
    @DisplayName("alias spellings follow every primary name exactly once")
    void aliasSpellingsFollowEveryPrimaryNameExactlyOnce() {
        // Given: a record whose second property has an alias spelling that sorts between the primary names
        // When: it is described
        PayloadSchema schema = startPayloadOf(OrderedPayloads.Aliased.class);

        // Then: the primary names come first in mapper order and the alias spelling last
        assertThat(schema.propertyOrder()).containsExactly("requester", "department", "dept");

        // And: every key of the document appears exactly once
        assertThat(propertyKeys(schema)).containsExactly("department", "dept", "requester");
        assertThat(schema.propertyOrder()).doesNotHaveDuplicates();
        assertThat(schema.propertyOrder()).containsExactlyInAnyOrderElementsOf(propertyKeys(schema));

        // And: the canonical document is exactly what the generator produces
        assertThat(schema.document()).isEqualTo(Registries.generated(OrderedPayloads.Aliased.class));
    }

    @Test
    @DisplayName("creator parameters come in creator order")
    void creatorParametersComeInCreatorOrder() {
        // Given: a creator whose parameter order differs from the sorted order and from the getter order
        // When: it is described
        PayloadSchema schema = startPayloadOf(OrderedPayloads.Created.class);

        // Then: the property order is the creator's parameter order
        assertThat(schema.propertyOrder()).containsExactly("zeta", "alpha", "mid");
        assertThat(propertyKeys(schema)).containsExactly("alpha", "mid", "zeta");
    }

    @Test
    @DisplayName("an any-setter adds no key of its own to the property order")
    void anySetterAddsNoKeyOfItsOwn() {
        // Given: a type with two named properties in an explicit order and an any-setter
        // When: it is described
        PayloadSchema schema = startPayloadOf(OrderedPayloads.WithExtras.class);

        // Then: the order lists the named properties in mapper order, each document key exactly once, and no
        // any-setter storage name
        assertThat(schema.propertyOrder()).containsExactly("zulu", "alpha");
        assertThat(schema.propertyOrder()).doesNotHaveDuplicates();
        assertThat(schema.propertyOrder()).containsExactlyInAnyOrderElementsOf(propertyKeys(schema));
        assertThat(schema.propertyOrder()).doesNotContain("extras");
    }

    @Test
    @DisplayName("document keys the mapper does not know follow in canonical order and mapper names absent from the"
            + " document are dropped")
    void documentKeysUnknownToTheMapperFollowInCanonicalOrder() {
        // Given: a profile that publishes, for a record, a schema whose properties the record does not have
        JsonSchemaFragment fragment = JsonSchemaFragment.parse("{\"type\": \"object\", \"properties\":"
                + " {\"zeta\": {\"type\": \"string\"}, \"alpha\": {\"type\": \"string\"}}}");
        var profiles = PayloadSchemaTestProfiles.with(PayloadSchemaTestProfiles.withOverrides(
                "replaced", List.of(JsonSchemaTypeOverride.input(OrderedPayloads.Replaced.class, fragment))));
        var registry =
                Registries.withSchemaValidator(cache, profiles, PayloadSchemaTestProfiles.systemProfile("replaced"));
        registry.register(Definitions.withStart("replaced", 1L, OrderedPayloads.Replaced.class));
        var schemas = AwaitedSchemas.of(registry, cache);

        // When: the start payload is described
        PayloadSchema schema = schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "replaced", 1L))
                .orElseThrow()
                .startPayload();

        // Then: the published keys are the ones in the document, so this is a fixture precondition
        assertThat(propertyKeys(schema)).containsExactly("alpha", "zeta");

        // And: the order is those keys in canonical order, without the record's own component name
        assertThat(schema.propertyOrder()).containsExactly("alpha", "zeta");
    }

    @Test
    @DisplayName("JsonObject and ObjectNode payloads have an empty property order")
    void objectDocumentsHaveAnEmptyPropertyOrder() {
        // Given: decisions typed with the two JSON object types
        Map<String, Class<?>> decisions = new LinkedHashMap<>();
        decisions.put("vertx", JsonObject.class);
        decisions.put("jackson", ObjectNode.class);
        var registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withDecisions("open", 1L, decisions));
        var schemas = AwaitedSchemas.of(registry, cache);

        // When: the definition is described
        List<DecisionDescriptor> described = schemas.describe(new DefinitionKey(WorkflowScopes.DEFAULT, "open", 1L))
                .orElseThrow()
                .humanTasks()
                .get(0)
                .decisions();

        // Then: both decisions have a payload, an object document and no property order
        assertThat(described).hasSize(2);
        for (DecisionDescriptor decision : described) {
            assertThat(decision.payload()).isNotNull();
            assertThat(decision.payload().document()).isEqualTo(PayloadShapeRule.objectByDefinitionDocument());
            assertThat(decision.payload().propertyOrder()).isEmpty();
        }
    }

    @Test
    @DisplayName("decision payloads carry the property order too")
    void decisionPayloadsCarryThePropertyOrderToo() {
        // Given: a decision typed with the record that has an explicit order
        var registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withDecision("ordered", 1L, OrderedPayloads.Ordered.class));
        var schemas = AwaitedSchemas.of(registry, cache);

        // When: the decision is looked up
        PayloadSchema schema = schemas.decision(
                        new DefinitionKey(WorkflowScopes.DEFAULT, "ordered", 1L), "review", "note")
                .orElseThrow()
                .payload();

        // Then: its property order leads with the explicit names and holds every key once
        assertThat(schema.propertyOrder()).startsWith("department", "requester");
        assertThat(schema.propertyOrder()).hasSize(4);
        assertThat(schema.propertyOrder()).containsExactlyInAnyOrderElementsOf(propertyKeys(schema));
    }

    @Test
    @DisplayName("the property order is immutable")
    void thePropertyOrderIsImmutable() {
        // Given: a described payload
        PayloadSchema schema = startPayloadOf(OrderedPayloads.Expense.class);

        // When / Then: the caller cannot change the list
        assertThatThrownBy(() -> schema.propertyOrder().add("extra")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(schema.propertyOrder()).containsExactly("requester", "amountCents", "department");
    }

    @Test
    @DisplayName("the property order uses the profile's wire names and is cached with the schema")
    void propertyOrderUsesTheProfilesWireNamesAndIsCachedWithTheSchema() {
        // Given: a system profile that names properties in snake case
        var profiles = PayloadSchemaTestProfiles.with(PayloadSchemaTestProfiles.snakeCase("p1"));
        var registry = Registries.withSchemaValidator(cache, profiles, PayloadSchemaTestProfiles.systemProfile("p1"));

        // When: a definition whose start type has camel case components is registered
        registry.register(Definitions.withStart("snake", 1L, OrderedPayloads.SnakeNamed.class));

        // Then: the cached entry orders the wire names by the mapper while its document keys are sorted
        CachedPayloadSchema entry =
                cache.snapshot().get(new SchemaKey(OrderedPayloads.SnakeNamed.class.getName(), JsonProfileId.of("p1")));
        assertThat(entry.propertyOrder()).containsExactly("display_name", "amount_cents");
        assertThat(entry.document().getJsonObject("properties").fieldNames())
                .containsExactly("amount_cents", "display_name");
    }

    private PayloadSchema startPayloadOf(Class<?> type) {
        var registry = Registries.withSchemaValidator(cache);
        registry.register(Definitions.withStart("order", 1L, type));
        return AwaitedSchemas.of(registry, cache)
                .describe(new DefinitionKey(WorkflowScopes.DEFAULT, "order", 1L))
                .orElseThrow()
                .startPayload();
    }

    private static List<String> propertyKeys(PayloadSchema schema) {
        return new ArrayList<>(schema.document().getJsonObject("properties").fieldNames());
    }
}
