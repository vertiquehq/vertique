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

    // --- T006 TP-002: the Positive/Negative family — C3p and C3c parity, both validator modes ---

    /**
     * rest-023 T006 TP-002. {@code PositiveFamilyStaticFactoryDto} (C3p: a {@code @JsonCreator} static
     * factory) and {@code PositiveFamilyConstructorDto} (C3c: a {@code @JsonCreator} constructor), each
     * carrying {@code @Positive} directly on the creator parameter, each registered twice — on the
     * validator-less factory every other tool in this fixture uses, and on the same validator-backed
     * factory {@link #bg1RejectsTooLongNameAndAcceptsValidNameUnderAValidator} uses. The unit-level
     * halves of the same fixtures are {@code
     * dev.vertique.json.schema.StaticFactoryConstraintParityTest
     * .staticFactoryCreatorParameterRendersThePositiveFamily} and {@code
     * .constructorCreatorParameterRendersThePositiveFamily} in {@code vertique-json-schema}; the
     * REST-level half is {@code
     * ProfiledSchemaSynthesisIT.positiveFamilyRejectsAViolatingValueAtBothBoundaries}.
     *
     * <p>Expected initial result (baseline, unmodified floor): C3p is {@code INPUT_VALIDATION} at
     * neither {@code amount=-5} nor {@code amount=0} in <em>either</em> mode — red — since neither the
     * floor nor the metadata supplement (Bean Validation exposes no metadata for a static factory at
     * all) can see this constraint yet. C3c is not rejected on the validator-less tool — red — but
     * already rejected on the validator-backed tool — a CHARACTERIZATION CONTROL, not a red: Bean
     * Validation exposes a constrained constructor's own parameters directly. Every {@code amount=5}
     * case is the sensitivity control, accepted (reaches the handler) in every mode/fixture combination.
     */
    @Test
    @DisplayName("T006 TP-002: a violating value at a static-factory or constructor parameter is rejected at both"
            + " boundaries, in both validator modes as measured")
    void positiveFamilyRejectsAViolatingValueAtBothBoundaries() throws Exception {
        startServer();

        // Every row is asserted through assertAll, so every mode/fixture combination's own verdict is
        // recorded in one run rather than stopping at the first failure (assertSchemaRejection and
        // assertAccepted are otherwise sequential, direct assertions elsewhere in this class).
        assertAll(
                // C3p, no validator: expected red now — the floor renders no Positive-family keyword yet.
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_TOOL,
                        new JsonObject().put("amount", -5),
                        "amount=-5 must be rejected once the floor renders exclusiveMinimum: 0"),
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_TOOL,
                        new JsonObject().put("amount", 0),
                        "amount=0 must be rejected (exclusiveMinimum, not minimum)"),
                () -> assertAccepted(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_TOOL,
                        new JsonObject().put("amount", 5),
                        "sensitivity control: amount=5 must reach the handler"),

                // C3p, validator-backed: expected red now too — Bean Validation exposes no metadata for
                // a static factory at all, so only the floor (once widened) closes this.
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_VALIDATOR_TOOL,
                        new JsonObject().put("amount", -5),
                        "amount=-5 must be rejected once the floor renders exclusiveMinimum: 0 (the"
                                + " metadata supplement alone cannot see a static factory)"),
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_VALIDATOR_TOOL,
                        new JsonObject().put("amount", 0),
                        "amount=0 must be rejected (exclusiveMinimum, not minimum)"),
                () -> assertAccepted(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_STATIC_FACTORY_VALIDATOR_TOOL,
                        new JsonObject().put("amount", 5),
                        "sensitivity control: amount=5 must reach the handler"),

                // C3c, no validator: expected red now — the floor renders no Positive-family keyword yet.
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_TOOL,
                        new JsonObject().put("amount", -5),
                        "amount=-5 must be rejected once the floor renders exclusiveMinimum: 0"),
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_TOOL,
                        new JsonObject().put("amount", 0),
                        "amount=0 must be rejected (exclusiveMinimum, not minimum)"),
                () -> assertAccepted(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_TOOL,
                        new JsonObject().put("amount", 5),
                        "sensitivity control: amount=5 must reach the handler"),

                // C3c, validator-backed: CHARACTERIZATION CONTROL — already green at main. Bean
                // Validation exposes a constrained constructor's own parameters directly.
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_VALIDATOR_TOOL,
                        new JsonObject().put("amount", -5),
                        "CHARACTERIZATION CONTROL (already green at main): the metadata supplement already"
                                + " renders exclusiveMinimum for a constructor parameter"),
                () -> assertSchemaRejection(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_VALIDATOR_TOOL,
                        new JsonObject().put("amount", 0),
                        "CHARACTERIZATION CONTROL (already green at main)"),
                () -> assertAccepted(
                        McpToolInputShapesITFixture.POSITIVE_FAMILY_CONSTRUCTOR_VALIDATOR_TOOL,
                        new JsonObject().put("amount", 5),
                        "sensitivity control: amount=5 must reach the handler"));
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

    // --- C1 (spike/deserializer-driven-schema round 4, CRITICAL): sibling-ordered unwrapped pair ---

    /**
     * C1: {@code SiblingUnwrappedParent} declares two {@code @JsonUnwrapped} siblings, in this order,
     * where only the *second* sibling ({@code SiblingUnwrappedB}) carries the {@code @JsonAnySetter}.
     * The *first* sibling ({@code SiblingUnwrappedA}) carries an aliased, constrained member
     * ({@code @JsonAlias("ak")}) and a hidden, constrained member — the exact shape T010's own hidden
     * -member probe uses, just moved onto a sibling processed before the any-setter is known.
     */
    @Test
    @DisplayName("C1: a sibling unwrapped child's alias key is rejected at the gate whatever sibling carries"
            + " the any-setter, and its hidden member's spelling is refused rather than reaching the handler")
    void siblingOrderedUnwrappedChildKeysAreRejectedAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.SIBLING_UNWRAPPED_TOOL,
                new JsonObject().put("ak", "abcdefgh"),
                "C1: the first-processed unwrapped sibling's alias \"ak\" carries its own @Size(max = 3),"
                        + " so an over-long value must be refused before it reaches the handler");
        assertSchemaRejection(
                McpToolInputShapesITFixture.SIBLING_UNWRAPPED_TOOL,
                new JsonObject().put("secret", 5),
                "C1 DECISIVE: the first-processed unwrapped sibling's hidden member \"secret\" must be"
                        + " refused rather than reaching the handler through the extras bucket unconstrained,"
                        + " even though the any-setter is declared on the second sibling, processed later");
        assertAccepted(
                McpToolInputShapesITFixture.SIBLING_UNWRAPPED_TOOL,
                new JsonObject().put("aname", "abc"),
                "the first sibling's own canonical property must still be admitted");
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

    /**
     * AC-005.2: the MCP-level half of the proof. {@code
     * dev.vertique.json.schema.CaseInsensitiveUnicodeFoldingTest.nonAsciiKeyRefusedByPropertyNames}
     * only matches the generated {@code propertyNames} regex against the confusable spelling — nothing
     * there exercises a real tool call. The REST-level half is {@code ProfiledSchemaSynthesisIT
     * .ac005NonAsciiKeyRejectedAtTheGateWithAValueFreeDetail}. U+212A KELVIN SIGN folds to ASCII
     * {@code 'k'} under Jackson's locale-independent case fold, so the binder's own case-insensitive
     * lookup would route a key spelled with it straight into the real, constrained {@code key} member
     * if the schema check did not refuse it first.
     */
    @Test
    @DisplayName("AC-005.2: a U+212A-folded key on a case-insensitive any-setter type is INPUT_VALIDATION, the"
            + " handler is never entered")
    void ac005NonAsciiKeyRejectedAsInputValidation() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.AC005_TOOL,
                new JsonObject().put("Key", "AC5"),
                "the propertyNames rule must refuse the U+212A-folded key before the binder's own"
                        + " case-insensitive lookup ever gets a chance to route it to the real \"key\" member");
    }

    // --- rest-023 T002 (D002): Optional-typed extras value ---

    /**
     * rest-023 T002 (D002; {@code evidence/probe-report-327531b4.md} § N14, § N14n). An any-setter
     * extras value of declared type {@code Optional<Plain>} admits an explicit {@code null} (Jackson's
     * own {@code Optional.empty()} mapping, closing N14n) and rejects a non-null value violating {@code
     * Plain}'s own {@code @Size(max = 3)} constraint (closing N14), distinguished from a broken request
     * path by a companion body that satisfies the constraint.
     *
     * <p>Expected initial result: red for both bodies — {@code main} rejects the {@code null} body and
     * accepts the constraint-violating body (the undescribed gap).
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An Optional-typed extras value accepts null and rejects a constraint violation")
    void optionalExtrasValueAcceptsNullAndRejectsAConstraintViolation() throws Exception {
        startServer();

        assertAccepted(
                McpToolInputShapesITFixture.OPTIONAL_EXTRAS_TOOL,
                new JsonObject().put("label", "l").putNull("x"),
                "N14n: an explicit null must bind to Optional.empty(), matching Jackson's own mapping");
        assertSchemaRejection(
                McpToolInputShapesITFixture.OPTIONAL_EXTRAS_TOOL,
                new JsonObject().put("label", "l").put("x", new JsonObject().put("name", "TOOLONG")),
                "N14: a non-null value violating Plain's own @Size(max = 3) must now be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.OPTIONAL_EXTRAS_TOOL,
                new JsonObject().put("label", "l").put("x", new JsonObject().put("name", "ab")),
                "sensitivity proof: a non-null value satisfying Plain's own constraint must stay accepted,"
                        + " distinguishing the constraint rejection from a broken request path");
    }

    // --- rest-023 T003 (D001): TP-004, map value shapes ---

    /**
     * rest-023 T003 TP-004 (D001; {@code evidence/probe-report-327531b4.md} §§ S2a, S2b, rest023b §
     * S2d). Every {@code tags}/{@code labels}/{@code opts} argument is rejected, including a {@code
     * null} inside the non-{@code Optional} {@code tags} map value (the explicit null rule); a
     * companion {@code opts} argument carrying {@code null} is accepted, since {@code opts}'s own
     * declared value type is {@code Optional<Plain>}; the {@code raw} argument stays accepted,
     * unaffected.
     *
     * <p>Expected initial result: red for every {@code tags}/{@code labels}/{@code opts} argument —
     * {@code main} accepts all of them; the {@code raw} argument's own acceptance is a characterization
     * control, not a red.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A wrong-typed or constraint-violating map value is rejected at both boundaries; a null in a"
            + " non-Optional map value is rejected; JsonNode values stay open")
    void mapValueRejectsAWrongTypedOrConstraintViolatingValueAtBothBoundaries() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("tags", new JsonObject().put("k", "TOOLONG")),
                "tags: a constraint-violating value must be rejected");
        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("tags", new JsonObject().putNull("k")),
                "tags: a null inside a non-Optional map value must be rejected as wrong-typed (the explicit"
                        + " null rule)");
        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("tags", new JsonObject().put("k", 1)),
                "tags: a wrong-typed value must be rejected");
        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("labels", new JsonObject().put("k", new JsonObject().put("name", "TOOLONG"))),
                "labels: a bean value's own constraint violation must be rejected");
        assertSchemaRejection(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("opts", new JsonObject().put("k", new JsonObject().put("name", "TOOLONG"))),
                "opts: a non-null Optional bean value's own constraint violation must be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("opts", new JsonObject().putNull("k")),
                "opts: null must be accepted — opts's own declared value type is Optional<Plain>");
        assertAccepted(
                McpToolInputShapesITFixture.MAP_VALUE_SHAPES_TOOL,
                new JsonObject().put("raw", new JsonObject().put("k", new JsonObject().put("any", 1))),
                "raw: an opaque JsonNode value must stay accepted, unaffected");
    }

    // --- rest-023 T003 (D001): TP-010, the single any-setter N16 shape ---

    /**
     * rest-023 T003 TP-010 (N16, architecture round-3 R1's own alternative, ruling X1). An argument
     * violating the single any-setter's own type-use constraint is rejected; a companion argument
     * satisfying the constraint is accepted, distinguishing the rejection from a broken request path.
     *
     * <p>Expected initial result: red — {@code main} accepts {@code {"k":"TOOLONG"}} (the any-setter's
     * own extras value carries no maxLength today).
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A body violating the single any-setter's own type-use constraint is rejected as INPUT_VALIDATION")
    void singleAnySetterValueTypeUseConstraintRejectsAViolatingBody() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.SINGLE_ANY_SETTER_TYPE_USE_TOOL,
                new JsonObject().put("k", "TOOLONG"),
                "a body violating the any-setter's own type-use constraint must be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.SINGLE_ANY_SETTER_TYPE_USE_TOOL,
                new JsonObject().put("k", "ab"),
                "a body satisfying the constraint must stay accepted, distinguishing the rejection from a"
                        + " broken request path");
    }

    // --- rest-023 T004 (D004): TP-002, several any-setters sharing a wire key ---

    /**
     * rest-023 T004 TP-002. An argument violating either any-setter's own constraint on a wire key both
     * a parent's own and an unwrapped child's own any-setter share (UW-2AS) is rejected; a companion
     * argument satisfying both is accepted. An object-valued two-any-setter pair (both value types are
     * beans) is rejected when either bean's own constraint is violated, and accepted — with the handler
     * running exactly once, the zero-false-reject proof — when both are satisfied.
     *
     * <p>Expected initial result: red for the object-valued pair's own conjunction (only one any-setter's
     * own value schema is described today); the UW-2AS argument's own rejection may already be green —
     * reported honestly against the actual baseline run, not assumed.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A body violating either any-setter's own constraint on a shared key is rejected as"
            + " INPUT_VALIDATION; an object-valued pair is accepted with zero false-reject when both"
            + " beans' own constraints are satisfied")
    void sharedAnySetterKeyRejectsAValueViolatingEitherAnySettersConstraint() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.SHARED_ANY_SETTER_CONJUNCTION_TOOL,
                new JsonObject().put("x", "TOOLONG"),
                "UW-2AS: a body violating the unwrapped child's own type-use constraint on the shared key"
                        + " must be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.SHARED_ANY_SETTER_CONJUNCTION_TOOL,
                new JsonObject().put("x", "ab"),
                "UW-2AS: a body satisfying both any-setters' own constraints on the shared key must stay"
                        + " accepted, distinguishing the rejection from a broken request path");

        assertSchemaRejection(
                McpToolInputShapesITFixture.OBJECT_VALUED_ANY_SETTER_PAIR_TOOL,
                new JsonObject()
                        .put("x", new JsonObject().put("label", "TOOLONGVALUE").put("code", "z")),
                "the object-valued pair: a body violating the parent's own bean's constraint must be" + " rejected");
        assertSchemaRejection(
                McpToolInputShapesITFixture.OBJECT_VALUED_ANY_SETTER_PAIR_TOOL,
                new JsonObject().put("x", new JsonObject().put("label", "ab").put("code", "TOOLONGVALUE")),
                "the object-valued pair: a body violating the unwrapped child's own bean's constraint must"
                        + " be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.OBJECT_VALUED_ANY_SETTER_PAIR_TOOL,
                new JsonObject().put("x", new JsonObject().put("label", "ab").put("code", "z")),
                "the object-valued pair: a body satisfying both beans' own constraints must be accepted"
                        + " with the handler running exactly once — the zero-false-reject proof");
    }

    // --- rest-023 T005 (D005): TP-003, member-level closure ---

    /**
     * rest-023 T005 TP-003 (D005; M10). An argument carrying an extra key under the member-level
     * FALSE-closed member is rejected; the same extra-key shape under the unannotated control member is
     * accepted, unaffected — distinguishing this task's own rule's scope from a global tightening. A
     * companion argument satisfying the closed member (no extra key) is accepted.
     *
     * <p>Expected initial result: red for the annotated member ({@code main} accepts it —
     * {@code evidence/probe-report-327531b4.md} § M10); the control member's own acceptance is already
     * green and stays green throughout.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An extra key under a member-level FALSE-closed member is rejected as INPUT_VALIDATION;"
            + " the same shape under an unannotated control member stays accepted")
    void memberLevelClosureRejectsAnExtraKeyUnderThatMemberOnly() throws Exception {
        startServer();

        assertSchemaRejection(
                McpToolInputShapesITFixture.MEMBER_LEVEL_CLOSURE_TOOL,
                new JsonObject().put("child", new JsonObject().put("name", "a").put("x", "1")),
                "a body carrying an extra key under the FALSE-closed member must be rejected");
        assertAccepted(
                McpToolInputShapesITFixture.MEMBER_LEVEL_CLOSURE_TOOL,
                new JsonObject().put("open", new JsonObject().put("name", "a").put("x", "1")),
                "the same extra-key shape under the unannotated control member must stay accepted,"
                        + " proving this task's own rule is scoped to the annotated member");
        assertAccepted(
                McpToolInputShapesITFixture.MEMBER_LEVEL_CLOSURE_TOOL,
                new JsonObject().put("child", new JsonObject().put("name", "a")),
                "a body satisfying the closed member (no extra key) must stay accepted");
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
