// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.schema.fixtures.Payloads;
import dev.vertique.workflow.schema.fixtures.ValidationPayloads;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies that a payload the keyed validation accepts binds under the process profile, and where the two differ. */
class SchemaAcceptedBindsTest {

    private static final String ID = "binding";

    /**
     * One payload type with the payloads that must be accepted and bind, one the schema must reject, and the
     * payloads that add an undeclared key at the top level and in a nested object (the latter may be absent).
     */
    private record Fixture(
            Class<?> type,
            List<JsonObject> accepted,
            JsonObject rejected,
            JsonObject undeclaredTopLevel,
            JsonObject undeclaredNested) {

        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture(
                        Payloads.PlainRecord.class,
                        List.of(
                                new JsonObject().put("name", "n").put("count", 3),
                                new JsonObject().put("count", Integer.MAX_VALUE),
                                new JsonObject().put("count", Integer.MIN_VALUE),
                                new JsonObject()),
                        new JsonObject().put("count", "many"),
                        new JsonObject().put("name", "n").put("extra", 1),
                        null),
                new Fixture(
                        Payloads.OptionalAliasRecord.class,
                        List.of(
                                new JsonObject().put("quantity", 1),
                                new JsonObject().put("qty", 1),
                                new JsonObject().put("qty", 1).put("note", "n"),
                                new JsonObject()),
                        new JsonObject().put("qty", "many"),
                        new JsonObject().put("quantity", 1).put("extra", 1),
                        null),
                new Fixture(
                        Payloads.RequiredAliasRecord.class,
                        List.of(
                                new JsonObject().put("quantity", 1),
                                new JsonObject().put("qty", 1),
                                new JsonObject().put("qty", 1).put("note", "n")),
                        new JsonObject().put("note", "n"),
                        new JsonObject().put("qty", 1).put("extra", 1),
                        null),
                new Fixture(
                        ValidationPayloads.Bounded.class,
                        List.of(
                                new JsonObject().put("amount", 1),
                                new JsonObject().put("amount", 100),
                                new JsonObject()),
                        new JsonObject().put("amount", 0),
                        new JsonObject().put("amount", 1).put("extra", 1),
                        null),
                new Fixture(
                        ValidationPayloads.Nested.class,
                        List.of(
                                new JsonObject()
                                        .put("inner", new JsonObject().put("label", "l"))
                                        .put("tags", new JsonArray().add("a").add("b")),
                                new JsonObject().put("tags", new JsonArray()),
                                new JsonObject()),
                        new JsonObject().put("tags", new JsonArray().add(1)),
                        new JsonObject().put("tags", new JsonArray()).put("extra", true),
                        new JsonObject()
                                .put("inner", new JsonObject().put("label", "l").put("extra", 1))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("every fixture payload the schema accepts binds under the process profile")
    void everyFixturePayloadTheSchemaAcceptsBindsUnderTheProcessProfile(Fixture fixture) {
        // Given: a registered decision of the fixture type, and the mapper of the process profile
        PayloadSchemaCache cache = new PayloadSchemaCache();
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        Map<String, Class<?>> decisions = new LinkedHashMap<>();
        decisions.put("decide", fixture.type());
        registry.register(Definitions.withStartAndDecisions(ID, 1L, Void.class, decisions));
        WorkflowPayloadSchemas schemas = new DefaultWorkflowPayloadSchemas(registry, cache);
        ObjectMapper mapper = PayloadSchemaTestProfiles.builtIn()
                .profile(JsonProfileId.of("vertique"))
                .mapper();

        // When / Then: every accepted declared-key payload is valid and binds to an instance
        for (JsonObject payload : fixture.accepted()) {
            assertThat(schemas.validateDecision(ID, 1L, "review", "decide", payload)
                            .valid())
                    .as("schema accepts %s", payload.encode())
                    .isTrue();
            assertThatCode(() -> assertThat(mapper.readValue(payload.encode(), fixture.type()))
                            .as("bound instance of %s", payload.encode())
                            .isNotNull())
                    .as("binding %s", payload.encode())
                    .doesNotThrowAnyException();
        }

        // And: the schema rejects the payload that is wrong for the declared type
        assertThat(schemas.validateDecision(ID, 1L, "review", "decide", fixture.rejected())
                        .valid())
                .as("schema rejects %s", fixture.rejected().encode())
                .isFalse();

        // And: an undeclared key passes the schema and fails binding as an unrecognized property
        assertSchemaAcceptsBinderRejects(schemas, mapper, fixture, fixture.undeclaredTopLevel());
        if (fixture.undeclaredNested() != null) {
            assertSchemaAcceptsBinderRejects(schemas, mapper, fixture, fixture.undeclaredNested());
        }
    }

    private static void assertSchemaAcceptsBinderRejects(
            WorkflowPayloadSchemas schemas, ObjectMapper mapper, Fixture fixture, JsonObject payload) {
        assertThat(schemas.validateDecision(ID, 1L, "review", "decide", payload).valid())
                .as("schema accepts the undeclared key in %s", payload.encode())
                .isTrue();
        assertThatThrownBy(() -> mapper.readValue(payload.encode(), fixture.type()))
                .as("binding the undeclared key in %s", payload.encode())
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
