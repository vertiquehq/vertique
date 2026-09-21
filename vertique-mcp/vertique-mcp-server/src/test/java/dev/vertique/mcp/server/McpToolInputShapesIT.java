// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpResultType;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.AliasedQuantityPayload;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.AnySetterNamedPayload;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.AnySetterOnlyPayload;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.CountingToolInvoker;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.DtoAnySetterPayload;
import dev.vertique.mcp.server.McpToolInputShapesITFixture.ObjectAnySetterPayload;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T005 TP-003 to TP-005 — what the MCP tool-input boundary accepts, proven through the real
 * {@code tools/call} path against schemas the real generator produced and the real hardener hardened.
 *
 * <p>Every rejection row asserts the terminal event's {@link McpErrorType#INPUT_VALIDATION}
 * classification, which only the dispatcher's stage-1 schema check produces, and that {@code prepare()}
 * — the generated fixed input boundary — was never entered. The attack values are ones the binder
 * would otherwise coerce (a numeric string for an integer, an epoch-day number for a date, numeric
 * list items, an unknown enum spelling the profile maps to its default constant), so a binder rejection
 * cannot be mistaken for a schema rejection: a binder failure in this fixture is reported as
 * {@code INPUT_PROCESSING}, and a coerced value would reach the handler and increment its counter.
 *
 * <p>A real server, bound and connected on the literal {@code 127.0.0.1} (see
 * {@code docs/standards/testing.md} § Dynamic Port Allocation).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpToolInputShapesIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String SCHEMA_MESSAGE = "Invalid tool arguments: schema validation failed";

    private final Vertx vertx = Vertx.vertx();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    private McpToolInputShapesITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    // --- TP-003: the five restored AC-013.1 shapes ---

    @Test
    @DisplayName("TP-003: a coercible wrong-typed value at a restored position is INPUT_VALIDATION")
    void restoredShapesRejectCoercibleWrongTypesAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.PRIVATE_DATE_TOOL,
                new JsonObject().put("due", 19000),
                "an epoch-day number the binder maps to 2022-01-08 must be rejected as a date string");
        assertSchemaRejection(
                McpToolInputShapesITFixture.BUILDER_TOOL,
                new JsonObject().put("name", "ada").put("quantity", "2"),
                "the numeric string \"2\" the binder coerces to 2 must be rejected as an integer");
        assertSchemaRejection(
                McpToolInputShapesITFixture.GETTER_ONLY_LIST_TOOL,
                new JsonObject().put("tags", new JsonArray().add(1).add(2)),
                "numeric items the binder coerces to strings must be rejected as string items");
        assertSchemaRejection(
                McpToolInputShapesITFixture.NESTED_PRIVATE_DATE_TOOL,
                new JsonObject().put("detail", new JsonObject().put("due", 19000)),
                "the nested position must reject the coercible epoch-day number too");
    }

    @Test
    @DisplayName("TP-003: an unknown key on a restored shape is INPUT_VALIDATION")
    void restoredShapesRejectUnknownKeysAsInputValidation() throws Exception {
        startServer();

        for (String toolName : new String[] {
            McpToolInputShapesITFixture.PRIVATE_DATE_TOOL,
            McpToolInputShapesITFixture.BUILDER_TOOL,
            McpToolInputShapesITFixture.GETTER_ONLY_LIST_TOOL,
            McpToolInputShapesITFixture.GETTER_ONLY_MAP_TOOL,
            McpToolInputShapesITFixture.NESTED_PRIVATE_DATE_TOOL
        }) {
            assertSchemaRejection(
                    toolName,
                    new JsonObject().put("zzz", "unknown"),
                    "the restored shape's object must be closed, so an unknown key never reaches the binder");
        }
    }

    // --- H4: getter-only collection with no backing field ---

    /**
     * The MCP-level half of the H4 proof. {@code GetterOnlyCollectionNoBackingFieldDto.getItems()} has
     * no backing field named {@code items} at all — the property's only storage is a private field
     * named {@code internal}, populated in place through the getter, which is how Jackson binds a
     * getter-only mutable collection with no setter. The REST-level half is {@code
     * ProfiledSchemaSynthesisIT
     * .getterOnlyCollectionWithNoBackingFieldGateRejectsWrongTypedItemsAndAcceptsValidBody}; the
     * unit-level half (that the property is published with its item schema) is {@code
     * dev.vertique.json.schema.GetterOnlyCollectionDescriptionTest
     * .getterOnlyCollectionWithNoBackingFieldPublishesItsItemSchema} in {@code vertique-json-schema}.
     */
    @Test
    @DisplayName("H4: a wrong-typed item on a getter-only collection with no backing field is INPUT_VALIDATION,"
            + " a well-typed body reaches the handler")
    void getterOnlyCollectionWithNoBackingFieldRejectsWrongTypedItemsAndAcceptsValidBody() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.GETTER_ONLY_COLLECTION_NO_BACKING_FIELD_TOOL,
                new JsonObject().put("items", new JsonArray().add("x")),
                "a string item must be rejected against the published items schema (type: integer)");
        assertAccepted(
                McpToolInputShapesITFixture.GETTER_ONLY_COLLECTION_NO_BACKING_FIELD_TOOL,
                new JsonObject().put("items", new JsonArray().add(1).add(2).add(3)),
                "a well-typed body must reach the handler");
    }

    // --- BG1: Lombok builder, constrained private field, no getter — validator-present case ---

    /**
     * The MCP-level half of the BG1 proof, validator-present only: {@code
     * McpToolInputShapesITFixture.BG1_TOOL} is registered through a separate, validator-backed {@link
     * dev.vertique.mcp.server.runtime.McpToolRuntimeFactory} — every other tool in this fixture stays
     * on the validator-less one. The REST-level half is {@code ProfiledSchemaSynthesisIT
     * .bg1GateRejectsTooLongNameAndAcceptsValidNameUnderAValidator}; the unit-level half (type-only
     * without a validator, {@code maxLength} with one) is {@code
     * dev.vertique.json.schema.MetadataConstraintSourceCoverageTest
     * .lombokBuilderNoGetterPropertyIsTypeOnlyWithoutAValidatorAndConstrainedWithOne} in {@code
     * vertique-json-schema}. Only the validator-present case has a row here: without a validator, the
     * constraint is not enforced by the schema at all, which is the package's per-mode behavior, not a
     * gap this proof needs to re-demonstrate at the MCP boundary.
     */
    @Test
    @DisplayName("BG1: on the validator-backed tool, a too-long name is INPUT_VALIDATION and a valid name reaches"
            + " the handler, for a Lombok builder's constrained, getter-less private field")
    void bg1RejectsTooLongNameAndAcceptsValidNameUnderAValidator() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.BG1_TOOL,
                new JsonObject().put("name", "toolong"),
                "a 7-character name must be rejected against the metadata-supplement-rendered maxLength: 5");
        assertAccepted(
                McpToolInputShapesITFixture.BG1_TOOL,
                new JsonObject().put("name", "ada"),
                "a 3-character name must reach the handler");
    }

    // --- TP-004: any-setter types ---

    @Test
    @DisplayName("TP-004: an any-setter type with named properties accepts a typed extra")
    void anySetterWithNamedPropertiesAcceptsTypedExtras() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpToolInputShapesITFixture.ANY_SETTER_NAMED_TOOL);

        assertAccepted(
                McpToolInputShapesITFixture.ANY_SETTER_NAMED_TOOL,
                new JsonObject().put("name", "ada").put("x", "y"),
                "a String-typed extra key must reach the handler beside the type's named property");

        AnySetterNamedPayload payload = (AnySetterNamedPayload) tool.lastPayload();
        assertThat(payload.argument0().name).isEqualTo("ada");
        assertThat(payload.argument0().extras)
                .as("the extra key must arrive with its value, not merely pass the schema")
                .containsEntry("x", "y");
    }

    @Test
    @DisplayName("TP-004: an any-setter-only type accepts a typed extra")
    void anySetterOnlyTypeAcceptsTypedExtras() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpToolInputShapesITFixture.ANY_SETTER_ONLY_TOOL);

        assertAccepted(
                McpToolInputShapesITFixture.ANY_SETTER_ONLY_TOOL,
                new JsonObject().put("x", 5),
                "a type with no named property was never closed, so its typed extra is accepted");

        AnySetterOnlyPayload payload = (AnySetterOnlyPayload) tool.lastPayload();
        assertThat(payload.argument0().extras).containsEntry("x", 5);
    }

    @Test
    @DisplayName("TP-004: an Object-valued any-setter accepts any extra, including a nested object")
    void objectValuedAnySetterAcceptsAnyExtra() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpToolInputShapesITFixture.OBJECT_ANY_SETTER_TOOL);

        assertAccepted(
                McpToolInputShapesITFixture.OBJECT_ANY_SETTER_TOOL,
                new JsonObject().put("name", "ada").put("x", new JsonObject().put("nested", true)),
                "an unconstrained extras value type is the empty schema, so any extra is accepted");

        ObjectAnySetterPayload payload = (ObjectAnySetterPayload) tool.lastPayload();
        assertThat(payload.argument0().extras)
                .as("the nested object extra must reach the handler intact")
                .containsEntry("x", java.util.Map.of("nested", true));
    }

    @Test
    @DisplayName("TP-004: a DTO-valued any-setter accepts a well-formed DTO extra")
    void plainDtoExtraIsAccepted() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpToolInputShapesITFixture.DTO_ANY_SETTER_TOOL);

        assertAccepted(
                McpToolInputShapesITFixture.DTO_ANY_SETTER_TOOL,
                new JsonObject().put("name", "ada").put("x", new JsonObject().put("name", "b")),
                "a DTO-typed extra whose keys the DTO declares must be accepted");

        DtoAnySetterPayload payload = (DtoAnySetterPayload) tool.lastPayload();
        assertThat(payload.argument0().extras.get("x")).isNotNull();
        assertThat(payload.argument0().extras.get("x").name)
                .as("the DTO extra must reach the handler materialized, not merely pass the schema")
                .isEqualTo("b");
    }

    @Test
    @DisplayName("TP-004: a coercible wrong-typed extra is INPUT_VALIDATION")
    void anySetterRejectsCoercibleWrongTypedExtrasAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.ANY_SETTER_NAMED_TOOL,
                new JsonObject().put("name", "ada").put("x", 5),
                "the number 5 the binder coerces to \"5\" must be rejected against the String extras schema");
        assertSchemaRejection(
                McpToolInputShapesITFixture.ANY_SETTER_ONLY_TOOL,
                new JsonObject().put("x", "1"),
                "the numeric string \"1\" the binder coerces to 1 must be rejected against the Integer "
                        + "extras schema");
    }

    @Test
    @DisplayName("TP-004: a class-level additionalProperties FALSE rejects every extra")
    void classLevelFalseRejectsExtrasAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.CLOSED_ANY_SETTER_TOOL,
                new JsonObject().put("name", "ada").put("x", "y"),
                "a type the application declared closed publishes no extras description and rejects every "
                        + "extra key");
    }

    @Test
    @DisplayName("TP-004: an unknown key inside a DTO extra is INPUT_VALIDATION")
    void plainDtoExtraRejectsAnUnknownNestedKeyAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.DTO_ANY_SETTER_TOOL,
                new JsonObject()
                        .put("name", "ada")
                        .put("x", new JsonObject().put("name", "b").put("zzz", 1)),
                "the DTO used as an extras value must itself be closed, so an unknown nested key is a "
                        + "schema rejection and never reaches the binder (design proof v2, V07)");
    }

    @Test
    @DisplayName("TP-004: a map subclass with an any-setter is described as the map the binder fills")
    void mapSubclassWithAnAnySetterIsDescribedAsAMap() throws Exception {
        startServer();

        // Jackson binds the type as a map: its field is never filled and its any-setter never invoked
        // (measured), so the document describes its entries and the hardener leaves the object open.
        assertAccepted(
                McpToolInputShapesITFixture.MAP_SUBCLASS_TOOL,
                new JsonObject().put("empty", "x"),
                "a legal map entry is what the binder accepts, so the gate accepts it too");
        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_SUBCLASS_TOOL,
                new JsonObject().put("empty", 5),
                "the entries are described with the map's value type, so a wrong-typed entry is refused");
    }

    // --- TP-005: aliases, reserved names, and storage names ---

    @Test
    @DisplayName("TP-005: an alias-routed key carrying a coercible value is INPUT_VALIDATION")
    void aliasRoutedKeysAreRejectedAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.ALIASED_QUANTITY_TOOL,
                new JsonObject().put("qty", "999"),
                "the alias spelling carries the property's own schema, so the numeric string the binder "
                        + "coerces is rejected before it reaches the @Max(10) slot (security round 7, N1)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.ALIASED_ENUM_TOOL,
                new JsonObject().put("r", "ADMIN"),
                "the alias spelling carries the enum's own constants, so a spelling the profile would bind "
                        + "to the @JsonEnumDefaultValue constant is rejected (security round 7, E)");
    }

    @Test
    @DisplayName("TP-005: a valid alias spelling beside a valid extra reaches the handler")
    void validAliasBesideAValidExtraIsAccepted() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpToolInputShapesITFixture.ALIASED_QUANTITY_TOOL);

        assertAccepted(
                McpToolInputShapesITFixture.ALIASED_QUANTITY_TOOL,
                new JsonObject().put("qty", 5).put("x", "1"),
                "a valid alias spelling and a valid extra on the same any-setter type must both be admitted");

        AliasedQuantityPayload payload = (AliasedQuantityPayload) tool.lastPayload();
        assertThat(payload.argument0().quantity)
                .as("the alias spelling must bind the named property")
                .isEqualTo(5);
        assertThat(payload.argument0().extras)
                .as("the extra must reach the handler intact beside the aliased property")
                .containsEntry("x", "1");
    }

    @Test
    @DisplayName("TP-005: under vertique-strict, a property sent under both spellings is INPUT_VALIDATION")
    void bothSpellingsAreRejectedUnderVertiqueStrict() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.ALIASED_QUANTITY_STRICT_TOOL,
                new JsonObject().put("quantity", 5).put("qty", 5),
                "the strict rule form admits at most one spelling of an optional aliased property, and "
                        + "neither parser rejects two different key names");
    }

    @Test
    @DisplayName("TP-005: reserved, contested, storage, and never-published names are INPUT_VALIDATION")
    void reservedAndStorageNamesAreRejectedAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.RESERVED_NAMES_TOOL,
                new JsonObject().put("role", "admin"),
                "a read-only name is bound by Jackson and never published, so it must stay rejected "
                        + "(security round 7, N4)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.RESERVED_NAMES_TOOL,
                new JsonObject().put("id", "forged"),
                "an ignored name is bound into the extras map and must stay rejected (security round 7, N3)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.RESERVED_NAMES_TOOL,
                new JsonObject().put("extras", new JsonObject().put("role", "admin")),
                "a method any-getter's storage name fills the map directly and must stay rejected "
                        + "(design proof v4, SG1)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.SHARED_ALIAS_TOOL,
                new JsonObject().put("x", "TOOLONG"),
                "a spelling two properties claim is published with the claimant the binder routes it to, "
                        + "the @Size(max = 3) property, so an over-long value is refused before it reaches it "
                        + "(design proof v7, DA1 and DA2)");
        assertAccepted(
                McpToolInputShapesITFixture.SETTER_ONLY_TOOL,
                new JsonObject().put("admin", true),
                "a name bound only through a setter is described with the setter's parameter type, so a "
                        + "well-typed value reaches the member (design proof v7, SO1)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.SETTER_ONLY_TOOL,
                new JsonObject().put("admin", "yes"),
                "a name bound only through a setter is described with the setter's parameter type, so a "
                        + "wrong-typed value never reaches the member (design proof v7, SO1)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.HIDDEN_ALIAS_TOOL,
                new JsonObject().put("lvl", 999),
                "an alias of a @Schema(hidden = true) property is released nowhere, so it must not reach the "
                        + "@Max(10) slot — the 0.2.0 verdict (design proof v9, AH1-hidden-alias-any)");

        String publishedSchema =
                fixture.tool(McpToolInputShapesITFixture.SHARED_ALIAS_TOOL).inputSchema();
        JsonObject shape =
                new JsonObject(publishedSchema).getJsonObject("properties").getJsonObject("payload");
        // The binder's own routing decides which claimant a contested spelling reaches; the published
        // schema under the spelling must be that claimant's, so the gate checks what the binder binds.
        McpToolInputShapesITFixture.ContestedSpellingWithAnySetter bound =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue("{\"x\":\"abc\"}", McpToolInputShapesITFixture.ContestedSpellingWithAnySetter.class);
        String claimant = "abc".equals(bound.b) ? "b" : "abc".equals(bound.a) ? "a" : null;
        assertThat(claimant)
                .as("the binder must route the contested spelling to one claimant")
                .isNotNull();
        assertThat(shape.getJsonObject("properties").getJsonObject("x"))
                .as("the contested spelling must be published with the schema of the claimant the binder"
                        + " routes it to")
                .isEqualTo(shape.getJsonObject("properties").getJsonObject(claimant));
    }

    // --- T010 TP-002: an alias spelling naming a member the document never publishes ---

    @Test
    @DisplayName("TP-002: an alias naming a hidden member is refused, with and without an any-setter")
    void anAliasNamingAHiddenMemberIsRefused() throws Exception {
        startServer();

        // Both shapes are asserted through assertAll, so the parent's verdict is recorded for each of
        // them rather than only for whichever fails first.
        assertAll(
                () -> assertHiddenMemberSpellingRefused(McpToolInputShapesITFixture.SPELLING_NAMES_HIDDEN_ANY_TOOL),
                () -> assertHiddenMemberSpellingRefused(McpToolInputShapesITFixture.SPELLING_NAMES_HIDDEN_CLOSED_TOOL));
    }

    /**
     * Asserts one CO-007 shape: the colliding spelling is refused whatever value it carries, and the
     * aliasing property itself still reaches the handler.
     *
     * <p>The decisive value is {@code 5}. At T010's parent the document publishes {@code secret}
     * carrying the aliasing property's own {@code {"maximum":10}}, so a larger value is refused by that
     * misplaced copy rather than admitted through the hole; {@code 5} satisfies the published maximum
     * and violates the hidden member's own {@code @Max(3)}, which is the member the key actually binds.
     */
    private void assertHiddenMemberSpellingRefused(String toolName) throws Exception {
        assertSchemaRejection(
                toolName,
                new JsonObject().put("secret", 5),
                "DECISIVE (CO-007): the spelling 'secret' is already this type's own property name, so it "
                        + "is published nowhere and, where extras are described, stays reserved. Published "
                        + "with the aliasing property's @Max(10) schema it admits 5 into a member declared "
                        + "@Max(3) that the document deliberately hides, and because 'level' is published "
                        + "the reserved set releases its spellings and the guard disappears with it. The "
                        + "closed shape is the regression row: 0.2.0 refused this key because the hardener "
                        + "closed a type with no declared extras");
        assertSchemaRejection(
                toolName,
                new JsonObject().put("secret", 99),
                "the same key is refused whatever value it carries, because the name is published nowhere "
                        + "and reserved rather than described with some other member's constraint");
        assertAccepted(
                toolName,
                new JsonObject().put("level", 5),
                "the aliasing property itself is untouched by the collision rule, so a blanket refusal "
                        + "cannot satisfy the two rows above");
    }

    // --- TP-006: case-insensitive binding (Change 3) ---

    @Test
    @DisplayName("TP-006: a case-insensitively bound property is accepted under another ASCII casing")
    void caseInsensitiveShapeAcceptsAnotherCasingOfAPublishedProperty() throws Exception {
        startServer();

        assertAccepted(
                McpToolInputShapesITFixture.CASE_INSENSITIVE_TOOL,
                new JsonObject().put("NAME", "ab"),
                "the binder accepts \"NAME\" case-insensitively, and the schema must too — this is the"
                        + " gap Change 3 closes: the generator used to refuse the whole type outright");
    }

    @Test
    @DisplayName("TP-006: a reserved name is rejected under every ASCII casing, not just its own")
    void caseInsensitiveShapeRejectsAReservedNameUnderAnotherCasing() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.CASE_INSENSITIVE_TOOL,
                new JsonObject().put("SECRETKEY", "x"),
                "the reserved name's folded pattern must exclude every ASCII casing, not only the"
                        + " canonical spelling — a schema that reserved 'secretKey' by exact enum alone would"
                        + " accept this key");
    }

    @Test
    @DisplayName(
            "TP-006: DECISIVE — the MCP hardener keeps accepting a patternProperties match on an otherwise-closed type")
    void caseInsensitiveClosedShapeAcceptsAnotherCasingThroughTheHardenerClosure() throws Exception {
        startServer();

        // The type declares no any-setter, so InputPropertyDescriber leaves additionalProperties
        // unset and the MCP hardener closes it with additionalProperties: false. Draft 2020-12
        // semantics say that keyword governs only a key matched by neither properties nor
        // patternProperties, so "NAME" — matched by the folded pattern — must still be accepted.
        assertAccepted(
                McpToolInputShapesITFixture.CASE_INSENSITIVE_CLOSED_TOOL,
                new JsonObject().put("NAME", "ab"),
                "a patternProperties match must still be accepted once the hardener has closed the"
                        + " object with additionalProperties: false");
    }

    @Test
    @DisplayName(
            "TP-006: a case-insensitive, otherwise-closed shape still rejects a key that matches no folded pattern")
    void caseInsensitiveClosedShapeStillRejectsATrulyUnknownKey() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.CASE_INSENSITIVE_CLOSED_TOOL,
                new JsonObject().put("zzz", "unknown"),
                "a key matching neither properties nor patternProperties must still be rejected by the"
                        + " hardener's additionalProperties: false closure");
    }

    // --- Shared actions and assertions ---

    /**
     * Sends {@code shapeArguments} as the tool's single {@code payload} parameter and asserts the call
     * was rejected by stage 1: the bounded schema-rejection result, the {@code INPUT_VALIDATION}
     * terminal classification, and no entry into the generated input boundary at all.
     */
    private void assertSchemaRejection(String toolName, JsonObject shapeArguments, String why) throws Exception {
        CountingToolInvoker<?> tool = fixture.tool(toolName);
        int prepareCallsBefore = tool.prepareCallCount();
        int invocationsBefore = tool.invocationCount();

        HttpResponse<Buffer> response = await(callTool(toolName, shapeArguments));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as(toolName + ": " + why + " — the call must settle as a tool-error result")
                .isTrue();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                .as(toolName + ": " + why + " — the rejection must be the bounded schema rejection")
                .isEqualTo(SCHEMA_MESSAGE);

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.TOOL_ERROR);
        assertThat(terminal.errorType())
                .as(toolName + ": DECISIVE — " + why + "; only the stage-1 schema check classifies as "
                        + "INPUT_VALIDATION, so a binder rejection (INPUT_PROCESSING) cannot satisfy this")
                .isEqualTo(McpErrorType.INPUT_VALIDATION);
        assertThat(terminal.resultType()).isEqualTo(McpResultType.COMPLETE);
        assertThat(tool.prepareCallCount())
                .as(toolName + ": DECISIVE — the generated fixed input boundary must never be entered")
                .isEqualTo(prepareCallsBefore);
        assertThat(tool.invocationCount())
                .as(toolName + ": DECISIVE — the handler must never run")
                .isEqualTo(invocationsBefore);
    }

    /** Sends {@code shapeArguments} and asserts the handler ran exactly once with a non-error result. */
    private void assertAccepted(String toolName, JsonObject shapeArguments, String why) throws Exception {
        CountingToolInvoker<?> tool = fixture.tool(toolName);
        int invocationsBefore = tool.invocationCount();

        HttpResponse<Buffer> response = await(callTool(toolName, shapeArguments));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as(toolName + ": " + why + " — the call must not settle as an error; text: "
                        + result.getJsonArray("content").getJsonObject(0).getString("text"))
                .isFalse();
        assertThat(tool.invocationCount())
                .as(toolName + ": DECISIVE — " + why + "; the handler must have run exactly once")
                .isEqualTo(invocationsBefore + 1);
    }

    private void startServer() throws Exception {
        fixture = McpToolInputShapesITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private Future<HttpResponse<Buffer>> callTool(String toolName, JsonObject shapeArguments) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", toolName)
                .put("arguments", new JsonObject().put("payload", shapeArguments));
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", nextRequestId.getAndIncrement())
                .put("method", "tools/call")
                .put("params", params);
        return client.post(fixture.port(), McpToolInputShapesITFixture.LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName)
                .sendBuffer(body.toBuffer());
    }

    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody)
                .as("every admitted tools/call commits to SSE framing before the input pipeline runs")
                .startsWith(SSE_PREFIX);
        JsonObject result =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        assertThat(result)
                .as("every row here settles as a CallToolResult, never a JSON-RPC error")
                .isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
