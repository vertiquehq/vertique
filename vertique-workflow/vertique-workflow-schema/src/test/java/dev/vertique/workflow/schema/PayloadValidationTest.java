// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.ViolationDetails;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.fixtures.ValidationPayloads;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies keyed validation of decision and start payloads against the schemas cached at registration. */
class PayloadValidationTest {

    private static final String ID = "orders";
    private static final String NO_START_ID = "no-start";
    private static final String SENTINEL = "ZZSENTINEL";

    private final PayloadSchemaCache cache = new PayloadSchemaCache();
    private final AwaitedSchemas schemas;

    PayloadValidationTest() {
        DefaultWorkflowRegistry registry = Registries.withSchemaValidator(cache);
        Map<String, Class<?>> decisions = new LinkedHashMap<>();
        decisions.put("approve", ValidationPayloads.RequiredComment.class);
        decisions.put("short", ValidationPayloads.ShortComment.class);
        decisions.put("priority", ValidationPayloads.PriorityDecision.class);
        decisions.put("closed", ValidationPayloads.Closed.class);
        decisions.put("coded", ValidationPayloads.Coded.class);
        decisions.put("codes", ValidationPayloads.CodedList.class);
        decisions.put("items", ValidationPayloads.ShortItems.class);
        decisions.put("labels", ValidationPayloads.Labelled.class);
        decisions.put("nested", ValidationPayloads.Nested.class);
        decisions.put("reject", void.class);
        registry.register(
                Definitions.withStartAndDecisions(ID, 1L, ValidationPayloads.RequiredComment.class, decisions));
        registry.register(Definitions.withDecisionUnderSecondContract(NO_START_ID, 1L, Void.class));
        schemas = AwaitedSchemas.of(registry, cache);
    }

    private PayloadValidationResult validateApprove(JsonObject payload) {
        return validate("approve", payload);
    }

    private PayloadValidationResult validate(String decision, JsonObject payload) {
        return schemas.validateDecision(new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), "review", decision, payload);
    }

    private static String repeated(char character, int length) {
        return String.valueOf(character).repeat(length);
    }

    @Test
    @DisplayName("a missing required property yields one required violation at the missing member without values")
    void missingRequiredPropertyYieldsOneRequiredViolationAtTheMissingMemberWithoutValues() {
        // Given: the registry of the orders definition, where the approve comment is required
        JsonObject payload = new JsonObject().put("other", SENTINEL);

        // When: a payload without the comment is validated
        PayloadValidationResult result = validateApprove(payload);

        // Then: it is invalid with exactly one required violation whose pointer names the missing property
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ViolationDetail violation = result.violations().get(0);
        assertThat(violation.type()).isEqualTo("required");
        assertThat(violation.path()).isEqualTo("/comment");
        assertThat(violation.message()).contains("comment");

        // And: neither the submitted value nor the undeclared name appears in any field
        Validations.assertNoValueIn(result, SENTINEL, "other");
    }

    @Test
    @DisplayName("a maxLength violation carries the property pointer and keyword")
    void maxLengthViolationCarriesThePropertyPointerAndKeyword() {
        // Given: a comment of at most three characters
        JsonObject payload = new JsonObject().put("comment", SENTINEL);

        // When: a longer comment is validated
        PayloadValidationResult result = validate("short", payload);

        // Then: one violation names the RFC 6901 pointer, the keyword, its argument and the canonical message
        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .containsExactly(ViolationDetail.atPointer(
                        "/comment", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)));
        Validations.assertNoValueIn(result, SENTINEL);
    }

    @Test
    @DisplayName("enum, type and additional-property violations never echo submitted values")
    void enumTypeAndAdditionalPropertyViolationsNeverEchoSubmittedValues() {
        // Given: an enum and a number property, and a record closed to undeclared properties
        JsonObject wrongKinds = new JsonObject().put("priority", SENTINEL).put("amount", "YYSENTINEL");
        JsonObject undeclared = new JsonObject().put("name", "ok").put("extraZZ", 1);

        // When: values of the wrong kind and an undeclared property are validated
        PayloadValidationResult kinds = validate("priority", wrongKinds);
        PayloadValidationResult closed = validate("closed", undeclared);

        // Then: the wrong kinds are reported at their pointers with the keyword
        assertThat(kinds.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactlyInAnyOrder(tuple("/priority", "enum"), tuple("/amount", "type"));
        Validations.assertNoValueIn(kinds, SENTINEL, "YYSENTINEL");

        // And: the undeclared property is one value-free violation at the root
        assertThat(closed.violations()).hasSize(1);
        ViolationDetail violation = closed.violations().get(0);
        assertThat(violation.path()).isEmpty();
        assertThat(violation.type()).isNull();
        assertThat(violation.args()).isNull();
        assertThat(violation)
                .isEqualTo(ViolationDetail.atPointer(
                        "", ViolationDetails.valueFree("").message(), null, null));
        Validations.assertNoValueIn(closed, "extraZZ");

        // And: control, the engine itself reports the undeclared name, so the assertion above can fail
        assertThat(Validations.rawEngineOutput(cache, ValidationPayloads.Closed.class, undeclared))
                .contains("extraZZ");
    }

    @Test
    @DisplayName("an undeclared map key never appears in a path")
    void anUndeclaredMapKeyNeverAppearsInAPath() {
        // Given: a map property whose values must be integers
        JsonObject payload = new JsonObject().put("labels", new JsonObject().put("ZZKEY", SENTINEL));

        // When: a string is submitted under a client-chosen key
        PayloadValidationResult result = validate("labels", payload);

        // Then: the pointer stops at the map property and neither the key nor the value appears
        assertThat(result.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("/labels", "type"));
        Validations.assertNoValueIn(result, "ZZKEY", SENTINEL);

        // And: control, the engine itself reports the key
        assertThat(Validations.rawEngineOutput(cache, ValidationPayloads.Labelled.class, payload))
                .contains("ZZKEY");
    }

    @Test
    @DisplayName("nested and array-item violations are reported at RFC 6901 pointers")
    void nestedAndArrayItemViolationsAreReportedAtRfc6901Pointers() {
        // Given: a nested record and a list of strings
        JsonObject payload =
                new JsonObject().put("inner", new JsonObject().put("label", 1)).put("tags", new JsonArray().add(5));

        // When: numbers are submitted where strings are declared
        PayloadValidationResult result = validate("nested", payload);

        // Then: the pointers address the property and the item with slashes and an index, never a fragment
        assertThat(result.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactlyInAnyOrder(tuple("/inner/label", "type"), tuple("/tags/0", "type"));
    }

    @Test
    @DisplayName("over-long pattern input is a bounded-input violation, not a match attempt")
    void overLongPatternInputIsABoundedInputViolationNotAMatchAttempt() {
        // Given: a code limited to lower case letters
        JsonObject payload = new JsonObject().put("code", repeated('a', 4097));

        // When: a code of 4,097 characters is validated
        PayloadValidationResult result = validate("coded", payload);

        // Then: one bounded-input violation and no pattern violation, so the expression did not run
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        ViolationDetail violation = result.violations().get(0);
        assertThat(violation.type()).isEqualTo("patternInputLength");
        assertThat(violation.path()).isEmpty();
        assertThat(violation.args()).isEqualTo(Map.of("limit", 4096));
        assertThat(result.violations()).extracting(ViolationDetail::type).doesNotContain("pattern");
    }

    @Test
    @DisplayName("a pattern input of exactly the limit is matched, not rejected as too long")
    void aPatternInputOfExactlyTheLimitIsMatchedNotRejectedAsTooLong() {
        // Given: a code limited to lower case letters
        // When: codes of exactly 4,096 characters are validated, one matching and one not
        PayloadValidationResult matching = validate("coded", new JsonObject().put("code", repeated('a', 4096)));
        PayloadValidationResult notMatching = validate("coded", new JsonObject().put("code", repeated('A', 4096)));

        // Then: the matching one is accepted
        assertThat(matching.valid()).isTrue();

        // And: the other one is a pattern violation, which proves the expression ran at the boundary
        assertThat(notMatching.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("/code", "pattern"));
    }

    @Test
    @DisplayName("many pattern-bearing items past the total limit yield one total-length violation")
    void manyPatternBearingItemsPastTheTotalLimitYieldOneTotalLengthViolation() {
        // Given: a list whose every item is matched against a pattern, each item within the per-string limit
        JsonArray atLimit = new JsonArray();
        for (int index = 0; index < 64; index++) {
            atLimit.add(repeated('a', 4096));
        }
        JsonArray overLimit = atLimit.copy().add(repeated('a', 4096));

        // When: 64 items (exactly 262,144 characters) and then 65 items are validated
        PayloadValidationResult accepted = validate("codes", new JsonObject().put("codes", atLimit));
        PayloadValidationResult rejected = validate("codes", new JsonObject().put("codes", overLimit));

        // Then: the total limit itself is accepted
        assertThat(accepted.valid()).isTrue();

        // And: one character past it is one total-length violation at the root
        assertThat(rejected.violations())
                .containsExactly(new ViolationDetail(
                        "",
                        "exceeds the maximum total length of 262144 characters for pattern validation",
                        "patternInputTotalLength",
                        Map.of("limit", 262_144)));
    }

    private static JsonObject itemsOfWhichTheFirstAreTooLong(int total, int tooLong) {
        JsonArray items = new JsonArray();
        for (int index = 0; index < total; index++) {
            items.add(index < tooLong ? SENTINEL : "ok");
        }
        return new JsonObject().put("items", items);
    }

    @Test
    @DisplayName("a payload with many violations reports every one, in order, with no truncation marker")
    void aPayloadWithManyViolationsReportsEveryOne() {
        // Given: a list of 150 items of which every one is longer than the three characters allowed
        JsonObject payload = itemsOfWhichTheFirstAreTooLong(150, 150);

        // When: the payload is validated
        PayloadValidationResult result = validate("items", payload);

        // Then: it is invalid with all 150 details
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(150);

        // And: each is a real maxLength violation of its own item, in item order
        assertThat(result.violations())
                .allSatisfy(detail -> assertThat(detail.type()).isEqualTo("maxLength"));
        assertThat(result.violations())
                .extracting(ViolationDetail::path)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 150)
                        .mapToObj(index -> "/items/" + index)
                        .toList());

        // And: no submitted value appears in any field
        Validations.assertNoValueIn(result, SENTINEL);
    }

    @Test
    @DisplayName("validating the largest accepted pattern payload twice in a row stays valid")
    void validatingTheLargestAcceptedPatternPayloadTwiceInARowStaysValid() {
        // Given: a list of 64 codes of 4,096 characters, which is exactly the accepted total
        JsonArray atLimit = new JsonArray();
        for (int index = 0; index < 64; index++) {
            atLimit.add(repeated('a', 4096));
        }
        JsonArray overLimit = atLimit.copy().add(repeated('a', 4096));
        ViolationDetail totalLength = new ViolationDetail(
                "",
                "exceeds the maximum total length of 262144 characters for pattern validation",
                "patternInputTotalLength",
                Map.of("limit", 262_144));

        // When: it is validated twice back to back on the same thread
        PayloadValidationResult first = validate("codes", new JsonObject().put("codes", atLimit));
        PayloadValidationResult second = validate("codes", new JsonObject().put("codes", atLimit));

        // Then: both are valid, so the first call left no accumulated length behind
        assertThat(first.valid()).isTrue();
        assertThat(second.valid()).isTrue();

        // And: a payload one item larger still yields the total-length violation exactly once
        PayloadValidationResult rejected = validate("codes", new JsonObject().put("codes", overLimit));
        assertThat(rejected.violations()).containsExactly(totalLength);

        // And: after the rejection the accepted payload is valid again
        assertThat(validate("codes", new JsonObject().put("codes", atLimit)).valid())
                .isTrue();
    }

    @Test
    @DisplayName("a definition without a start payload type accepts any start payload")
    void aDefinitionWithoutAStartTypeAcceptsAnyStartPayload() {
        // Given: a definition registered with no start payload type at all, as the engine passes any payload through
        PayloadSchemaCache nullStartCache = new PayloadSchemaCache();
        DefaultWorkflowRegistry nullStartRegistry = Registries.withSchemaValidator(nullStartCache);
        nullStartRegistry.register(Definitions.withStart("null-start", 1L, null));
        assertThat(nullStartRegistry
                        .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "null-start", 1L))
                        .startPayloadType())
                .isNull();
        AwaitedSchemas nullStartSchemas = AwaitedSchemas.of(nullStartRegistry, nullStartCache);

        // When: a missing, an empty and a non-empty start payload are validated
        PayloadValidationResult missing =
                nullStartSchemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, "null-start", 1L), null);
        PayloadValidationResult empty = nullStartSchemas.validateStart(
                new DefinitionKey(WorkflowScopes.DEFAULT, "null-start", 1L), new JsonObject());
        PayloadValidationResult filled = nullStartSchemas.validateStart(
                new DefinitionKey(WorkflowScopes.DEFAULT, "null-start", 1L), new JsonObject().put("anything", 1));

        // Then: all three are valid without a violation
        assertThat(missing.valid()).isTrue();
        assertThat(empty.valid()).isTrue();
        assertThat(empty.violations()).isEmpty();
        assertThat(filled.valid()).isTrue();
        assertThat(filled.violations()).isEmpty();
    }

    @Test
    @DisplayName("a violation inside a list names the item index and no value")
    void aViolationInsideAListNamesTheItemIndexAndNoValue() {
        // Given: a list of lower case codes
        JsonObject payload =
                new JsonObject().put("codes", new JsonArray().add("ok").add("ZZSENTINEL1"));

        // When: the second item does not match
        PayloadValidationResult result = validate("codes", payload);

        // Then: the pointer names the item and no field carries the item
        assertThat(result.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("/codes/1", "pattern"));
        Validations.assertNoValueIn(result, SENTINEL);
    }

    @Test
    @DisplayName("a valid payload is accepted and a start payload is validated against the start type")
    void aValidPayloadIsAcceptedAndAStartPayloadIsValidatedAgainstTheStartType() {
        // Given: the orders definition, whose start type requires a comment
        // When: valid decision and start payloads and an invalid start payload are validated
        PayloadValidationResult decision = validateApprove(new JsonObject().put("comment", "fine"));
        PayloadValidationResult start = schemas.validateStart(
                new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), new JsonObject().put("comment", "fine"));
        PayloadValidationResult invalidStart = schemas.validateStart(
                new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), new JsonObject().put("other", SENTINEL));

        // Then: the valid ones carry no violation
        assertThat(decision.valid()).isTrue();
        assertThat(decision.violations()).isEmpty();
        assertThat(start.valid()).isTrue();

        // And: the invalid start payload is one required violation at the missing member
        assertThat(invalidStart.violations())
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("/comment", "required"));
        Validations.assertNoValueIn(invalidStart, SENTINEL, "other");
    }

    @Test
    @DisplayName("unknown definition or decision yields one fixed-message violation")
    void unknownDefinitionOrDecisionYieldsOneFixedMessageViolation() {
        // Given: the orders definition with a void decision reject, and a second definition without a start payload
        JsonObject empty = new JsonObject();
        JsonObject extra = new JsonObject().put("x", 1);
        ViolationDetail unknownDefinition = ViolationDetail.unlocated("definition is not registered", "definition");
        ViolationDetail unknownDecision =
                ViolationDetail.unlocated("decision is not declared for this task step", "decision");
        ViolationDetail voidDecision =
                ViolationDetail.unlocated("payload is not accepted for a decision without a payload type", "payload");
        ViolationDetail voidStart =
                ViolationDetail.unlocated("payload is not accepted for a start type without a payload", "payload");

        // When / Then: an unknown definition, decision or step yields one fixed violation each
        PayloadValidationResult definition =
                schemas.validateDecision(new DefinitionKey(WorkflowScopes.DEFAULT, "ZZID", 1L), "s", "d", empty);
        assertThat(definition.violations()).containsExactly(unknownDefinition);
        PayloadValidationResult name =
                schemas.validateDecision(new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), "review", "ZZNAME", empty);
        assertThat(name.violations()).containsExactly(unknownDecision);
        PayloadValidationResult step =
                schemas.validateDecision(new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), "ZZSTEP", "approve", empty);
        assertThat(step.violations()).containsExactly(unknownDecision);
        PayloadValidationResult start =
                schemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, "ZZID", 1L), empty);
        assertThat(start.violations()).containsExactly(unknownDefinition);
        PayloadValidationResult version = schemas.validateDecision(
                new DefinitionKey(WorkflowScopes.DEFAULT, ID, 99L), "review", "approve", empty);
        assertThat(version.violations()).containsExactly(unknownDefinition);
        Validations.assertNoValueIn(definition, "ZZID");
        Validations.assertNoValueIn(name, "ZZNAME");
        Validations.assertNoValueIn(start, "ZZID");

        // And: a void decision accepts only null
        assertThat(validate("reject", null).valid()).isTrue();
        assertThat(validate("reject", empty).violations()).containsExactly(voidDecision);
        assertThat(validate("reject", extra).violations()).containsExactly(voidDecision);

        // And: a void start accepts only null
        assertThat(schemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, NO_START_ID, 1L), null)
                        .valid())
                .isTrue();
        assertThat(schemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, NO_START_ID, 1L), empty)
                        .violations())
                .containsExactly(voidStart);
        assertThat(schemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, NO_START_ID, 1L), extra)
                        .violations())
                .containsExactly(voidStart);

        // And: a missing payload for a typed decision or start is a required payload
        ViolationDetail required = ViolationDetail.unlocated("payload is required", "payload");
        assertThat(validateApprove(null).violations()).containsExactly(required);
        assertThat(schemas.validateStart(new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1L), null)
                        .violations())
                .containsExactly(required);
    }
}
