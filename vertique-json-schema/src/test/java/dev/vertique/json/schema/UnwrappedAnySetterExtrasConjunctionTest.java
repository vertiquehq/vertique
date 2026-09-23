// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rest-023 T004 ({@code D004}). Several any-setters (a parent's own and at least one {@code
 * @JsonUnwrapped} child's own) that Jackson feeds the same wire key into now describe that key's shared
 * {@code additionalProperties} schema as the conjunction of every any-setter's own value schema — {@code
 * {"allOf": [...]}}, composed directly by the shared value-position renderer (T001), replacing the
 * previous single-slot rule under which only one any-setter's own value schema was described.
 *
 * <ul>
 *   <li><strong>TP-001</strong> — the shared key's {@code additionalProperties} schema is the
 *       conjunction of both any-setters' own value schemas, including a type-use constraint; a
 *       parent-only control stays unaffected, a three-any-setter set extends the conjunction to every
 *       member, {@code maxProperties} takes the stricter value rather than composing an {@code allOf} of
 *       {@code maxProperties} values, and a child's own named property is described by the child's own
 *       schema alone, never conjoined with the parent's any-setter.
 *   <li><strong>TP-003</strong> — the unconditional inline rule (architecture round-2 condition C5,
 *       corrected round-3 R4): a conjunction subschema whose own value type is a bean without a profile
 *       override is always rendered inline, never {@code $ref}'d; a profile-overridden value type keeps
 *       its override fragment; a self-referential value type fails with the single bounded diagnostic,
 *       never a {@code StackOverflowError} and never a silent standard {@code $defs} {@code $ref}.
 * </ul>
 *
 * <p>Every document is generated under the registry's built-in {@code vertique} profile, except where a
 * test declares its own profile (TP-003(b)'s override companion).
 */
class UnwrappedAnySetterExtrasConjunctionTest {

    // --- Generation helpers (fixture-construction isolation, so Given/When/Then stays visible below) ---

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static JsonNode inputDocument(Type type) {
        return inputDocument(type, profile());
    }

    private static JsonNode inputDocument(Type type, JsonMapperProfile profile) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(type));
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same schema
     * whether the generator inlined the value type or shared it under {@code $defs}.
     *
     * @param document the whole document, which owns the definitions
     * @param node     the node that may be a reference
     * @return the referenced schema, or {@code node} when it is not a local reference
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    private static JsonMapperProfile overrideProfile(Class<?> overriddenClass, JsonSchemaFragment fragment) {
        List<JsonSchemaTypeOverride> overrides = List.of(JsonSchemaTypeOverride.input(overriddenClass, fragment));
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("t004-tp003b-override");
            }

            @Override
            public com.fasterxml.jackson.databind.ObjectMapper mapper() {
                return new com.fasterxml.jackson.databind.ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    // ==================================================================================================
    // TP-001 — the shared key's additionalProperties schema is the conjunction of both any-setters'
    // own constraints, including a type-use constraint (D004; UW-2AS,
    // evidence/probe-report-327531b4.md).
    // ==================================================================================================

    /**
     * Given: UW-2AS — a parent declaring its own, type-use-constrained any-setter ({@code
     * Map<String,@Size(min=2) String>}) and an {@code @JsonUnwrapped} child declaring its own,
     * differently-type-use-constrained any-setter ({@code Map<String,@Size(max=3) String>}).
     *
     * <p>When: the input-direction schema is generated for the parent type.
     *
     * <p>Then (coordinator ruling, binding — the observable outcome is the folded document): the
     * describer's own {@link ValuePositionRenderer#renderConjunction} composes the shared wire key's
     * {@code additionalProperties} schema as {@code {"allOf": [<parent-value-schema>,
     * <child-value-schema>]}}, and the generator's {@code AllOfFold} post-pass (D004/C5;
     * {@code AnnotationJsonSchemaGenerator.java:774}) folds that {@code allOf} of two plain parts into
     * one object before the document is final — so the literal {@code allOf} node is never visible here;
     * what must hold is that both any-setters' own constraints survive into the folded value schema. The
     * parent's own {@code minLength: 2} — a bound that cannot be produced by the child's own {@code
     * @Size(max=3)} alone — is what proves the parent's own part was actually conjoined, not silently
     * dropped in favor of the child's.
     *
     * <p>Base ({@code 06404b58}, T003 merge, pre-T004 production): red — the pre-change generator
     * describes only one any-setter's own value schema for the shared key, never the conjunction (L02,
     * {@code evidence/T004.md}): the unwrapped child's own schema alone wins ({@code
     * {"maxLength":3,"type":"string"}}), carrying no {@code minLength} at all — confirmed by reasoning
     * against that measured baseline in {@code scratchpad/t004-l02/}, not by reverting today's production
     * to re-observe.
     */
    @Test
    @DisplayName("TP-001: a shared any-setter key's folded value schema carries both any-setters' own"
            + " constraints, including a type-use constraint")
    void sharedKeyDescribesBothAnySettersOwnConstraintAsAllOf() {
        JsonNode document = inputDocument(UwTwoAnySettersParent.class);
        JsonNode additionalProperties = document.path("additionalProperties");

        assertEquals(
                "{\"maxLength\":3,\"minLength\":2,\"type\":\"string\"}",
                additionalProperties.toString(),
                "UwTwoAnySettersParent's shared wire key's folded additionalProperties schema must carry"
                        + " both the parent's own type-use-constrained any-setter value schema"
                        + " (minLength: 2) and the unwrapped child's own type-use-constrained any-setter"
                        + " value schema (maxLength: 3) — composed as allOf by the renderer and folded by"
                        + " the generator (AllOfFold) into one object; the parent's own minLength proves"
                        + " the parent's own part was conjoined, not dropped; document: " + document);
    }

    /**
     * Sensitivity proof (i): a second, any-setter-free-child control — the parent's own any-setter alone
     * — must stay unaffected: no {@code allOf}, the parent's own plain value schema.
     */
    @Test
    @DisplayName("TP-001 sensitivity (i): a parent-only any-setter, with no child any-setter, is"
            + " unaffected — no allOf, the plain value schema")
    void parentOnlyAnySetterIsUnaffectedByTheConjunctionRule() {
        JsonNode document = inputDocument(UwParentOnlyParent.class);
        JsonNode additionalProperties = document.path("additionalProperties");

        assertFalse(
                additionalProperties.has("allOf"),
                "a parent-only any-setter (no child declares its own) must never gain an allOf composed"
                        + " of only one member; document: " + document);
        assertEquals(
                "{}",
                additionalProperties.toString(),
                "a parent-only any-setter's own extras schema must stay exactly the parent's own plain"
                        + " value schema — unaffected by the conjunction rule; document: " + document);
    }

    /**
     * Sensitivity proof (ii): a same-level, non-nested, non-polymorphic three-any-setter set (the
     * parent's own and two unwrapped children's own) extends the conjunction to every any-setter in the
     * set, not only two — closing the coverage gap round-1 review found.
     *
     * <p>Coordinator ruling, binding: the folded document, not a literal {@code allOf}, is the
     * observable outcome (see {@link #sharedKeyDescribesBothAnySettersOwnConstraintAsAllOf()}'s own
     * Javadoc). The parent's own bound is {@code @Size(min=2)} rather than {@code @Size(max=5)}
     * specifically because {@code minLength} cannot be produced by either child's own constraint, so its
     * presence in the folded schema — alongside both children's own — proves all three members were
     * conjoined.
     */
    @Test
    @DisplayName("TP-001 sensitivity (ii): the folded value schema carries every any-setter's own"
            + " constraint in a three-any-setter, same-level set, not only two")
    void conjunctionExtendsToEveryAnySetterInAThreeAnySetterSet() {
        JsonNode document = inputDocument(UwThreeAnySettersParent.class);
        JsonNode additionalProperties = document.path("additionalProperties");

        assertEquals(
                "{\"maxLength\":3,\"minLength\":2,\"pattern\":\"^[a-z]+$\",\"type\":\"string\"}",
                additionalProperties.toString(),
                "the folded shared key's additionalProperties schema must carry every any-setter's own"
                        + " constraint in the three-any-setter set — the parent's own @Size(min=2), the"
                        + " first unwrapped child's own @Size(max=3), and the second unwrapped child's own"
                        + " @Pattern — composed as allOf by the renderer and folded by the generator"
                        + " (AllOfFold), not only two of the three; document: " + document);
    }

    /**
     * Sensitivity proof (iii): when more than one any-setter in conjunction declares its own {@code
     * @Size} on the map itself (translated to {@code maxProperties}), the stricter value wins across the
     * conjunction — proven as a control, not composed as an {@code allOf} of {@code maxProperties}
     * values, which JSON Schema semantics would make meaningless for a single, shared extras map.
     *
     * <p>This fixture makes the <strong>parent</strong> the stricter side (@Size(max=4)) and the child
     * the looser side (@Size(max=10)); a mechanism that always took the child's own bound (the previous
     * single-slot behavior) would produce 10 here, not 4, so this is discriminating. See {@link
     * #stricterMaxPropertiesWinsAcrossTheConjunctionMirrored()} for the reversed-roles companion.
     */
    @Test
    @DisplayName("TP-001 sensitivity (iii): the stricter maxProperties wins across the conjunction when"
            + " the parent is the stricter side, never composed as an allOf of maxProperties values")
    void stricterMaxPropertiesWinsAcrossTheConjunctionRatherThanComposingAnAllOf() {
        JsonNode document = inputDocument(UwStricterWinsParent.class);

        assertEquals(
                4,
                document.path("maxProperties").asInt(-1),
                "the stricter of the parent's own @Size(max=4) and the child's own @Size(max=10) must win"
                        + " across the conjunction — 4, not 10; document: " + document);
        JsonNode allOf = document.path("additionalProperties").path("allOf");
        if (allOf.isArray()) {
            for (JsonNode part : allOf) {
                assertFalse(
                        part.has("maxProperties"),
                        "maxProperties is a control on the shared extras map itself, taken as the"
                                + " stricter of the two — it must never appear inside a conjunction allOf"
                                + " branch, which would compose it (meaninglessly) instead of taking the"
                                + " stricter value; document: " + document);
            }
        }
    }

    /**
     * Sensitivity proof (iii) mirror: the roles reversed — the <strong>child</strong> is the stricter
     * side (@Size(max=4)), the parent the looser (@Size(max=10)). Together with the primary proof above,
     * this rules out a mechanism that always favors one side (parent or child) over the other rather than
     * genuinely comparing bounds.
     */
    @Test
    @DisplayName("TP-001 sensitivity (iii) mirror: the stricter maxProperties still wins when the child,"
            + " not the parent, is the stricter side")
    void stricterMaxPropertiesWinsAcrossTheConjunctionMirrored() {
        JsonNode document = inputDocument(UwStricterWinsMirrorParent.class);

        assertEquals(
                4,
                document.path("maxProperties").asInt(-1),
                "the stricter of the parent's own @Size(max=10) and the child's own @Size(max=4) must win"
                        + " across the conjunction — 4, not 10; document: " + document);
    }

    /**
     * Sensitivity proof (iv): a key that is a named property of the child (not fed to the child's own
     * any-setter) is described by the child's own named-property schema only, not conjoined with the
     * parent's any-setter's own schema.
     */
    @Test
    @DisplayName("TP-001 sensitivity (iv): a child's own named key is described by the child's own"
            + " schema alone, never conjoined with the parent's any-setter")
    void childsOwnNamedKeyIsDescribedByTheChildsOwnSchemaAloneNeverConjoined() {
        JsonNode document = inputDocument(UwNamedKeyParent.class);
        JsonNode nameSchema = document.path("properties").path("name");

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                nameSchema.toString(),
                "the child's own named property \"name\" must be described by the child's own"
                        + " named-property schema alone; document: " + document);
        assertFalse(
                nameSchema.has("allOf"),
                "a named property of the child must never be conjoined with the parent's any-setter's"
                        + " own schema, even though Jackson's own unwrapped-composition semantics still"
                        + " feed that key to the parent's any-setter too (D004's disclosed residual);"
                        + " document: " + document);
    }

    // ==================================================================================================
    // TP-003 — the unconditional inline rule (architecture round-2 condition C5, corrected round-3 R4):
    // a conjunction subschema whose own value type is a bean without a profile override is always
    // inlined; an overridden value type keeps its override fragment; a self-referential value type
    // fails with the single bounded diagnostic.
    // ==================================================================================================

    /**
     * Given: an object-valued two-any-setter conjunction fixture (the parent's own {@code Map<String,
     * ConjLeft>} any-setter, the unwrapped child's own {@code Map<String, ConjRight>} any-setter), where
     * neither value type carries a profile override nor is self-referential.
     *
     * <p>When: the input-direction schema is generated for the parent type.
     *
     * <p>Then (coordinator ruling, binding): both any-setters' own subschemas are inlined through the
     * describer's own composer (C5's unconditional inline rule) — never {@code $ref} — and then folded by
     * the generator's {@code AllOfFold} post-pass into one object schema carrying both beans' own
     * property sets with their own constraints, since neither part carries a {@code $ref} and the two
     * parts do not conflict. This is the rule's own proof, the non-overridden, non-self-referential
     * companion TP-003(b)/(c) are measured against.
     *
     * <p>Base ({@code 06404b58}, pre-T004 production): red — measured baseline (L02, {@code
     * evidence/T004.md}): the pre-change generator renders a single, ad hoc merged object schema carrying
     * both {@code label} and {@code code} directly under one {@code properties} map from an existing
     * mechanism unrelated to this task's own renderer-composed conjunction — an accidental partial merge,
     * not this rule's own inline-then-fold outcome. The folded shape this test now asserts is
     * byte-identical to that accidental baseline only because both fixture beans are plain and
     * non-conflicting; TP-003(b)'s own override companion is where the two mechanisms provably diverge.
     *
     * <p>Naming note: the contract's own TP-003 label covers cases (a)-(c) across this method and its two
     * sibling methods below ({@link #overriddenValueTypeKeepsItsOverrideFragmentNeverInlinedIntoTheConjunction()}
     * for (b), {@link #selfReferentialValueTypeFailsWithTheSingleBoundedDiagnostic()} for (c)).
     */
    @Test
    @DisplayName("TP-003(a): an object-valued two-any-setter conjunction inlines both subschemas and"
            + " folds them into one object, never $ref (C5)")
    void objectValuedPairInlinesBothSubschemasAndFolds() {
        JsonNode document = inputDocument(ConjObjectValuedParent.class);
        JsonNode additionalProperties = document.path("additionalProperties");

        assertEquals(
                "{\"properties\":{\"code\":{\"maxLength\":2,\"type\":\"string\"},\"label\":{\"maxLength\":3,"
                        + "\"type\":\"string\"}},\"type\":\"object\"}",
                additionalProperties.toString(),
                "the shared key's folded value schema must be one object carrying both beans' own"
                        + " property sets with their own constraints — the parent's own ConjLeft.label"
                        + " (@Size(max=3)) and the unwrapped child's own ConjRight.code (@Size(max=2)) —"
                        + " composed as allOf by the renderer's own inline composition (C5: every"
                        + " non-overridden bean-valued conjunction member is inlined, never $ref'd) and"
                        + " folded into one object by the generator (AllOfFold); document: " + document);
        assertFalse(
                additionalProperties.toString().contains("$ref"),
                "no part of the folded value schema may carry a $ref anywhere beneath it — both value"
                        + " types are inlined, per C5's unconditional rule; document: " + document);
    }

    /**
     * TP-003(b): a companion fixture where one any-setter's own value type carries a profile override
     * ({@code validatedProfile.fragmentFor(V) != null}) — asserts the overridden value type's own
     * subschema stays the override fragment (never inlined from its bean description, which would
     * silently drop the override fragment), while the non-overridden any-setter's own bean-valued
     * subschema still inlines, per C5's unconditional rule.
     *
     * <p>The override fragment is object-compatible ({@code type: object}), matching the non-overridden
     * {@code ConjLeft} part's own {@code type: object} — unlike a scalar override fragment, which
     * conjoins a disjoint explicit {@code type} against the inlined object part and is genuinely
     * unsatisfiable ({@link DisjointTypeDetector} correctly refuses it; that is a different, task-unrelated
     * fixture shape, not this one). Coordinator ruling, binding: because this part carries a {@code $ref}
     * (the override carve-out, F4), {@code AllOfFold} refuses to <em>dereference</em> it — the {@code $ref}
     * is never expanded or dropped, and its own content is never silently replaced by a reflection-built
     * bean description. Measured (not the ruling's own literal prediction of a two-element {@code allOf}
     * array): 2020-12 permits {@code $ref} as a sibling keyword, so the fold still merges the
     * non-overridden {@code ConjLeft} part's own {@code label} property directly alongside the {@code
     * $ref} into <strong>one</strong> object — {@code additionalProperties} itself carries {@code $ref},
     * {@code type: object}, and {@code properties.label}, with no {@code allOf} wrapper — while the {@code
     * $ref} still resolves to the override fragment's own content ({@code code.maxLength: 9}), never
     * {@code ConjOverriddenRight}'s own reflection-built bean description ({@code code.maxLength: 2}).
     *
     * <p>Base ({@code 06404b58}, pre-T004 production): red, as a startup error rather than a failed
     * assertion — measured baseline (L02, {@code evidence/T004.md}) with the original scalar override
     * fragment: generation itself throws {@code JsonSchemaGenerationException} ("conjoins disjoint
     * explicit \"type\" declarations, the last of which is [string]"), thrown by the pre-existing,
     * task-unrelated {@code DisjointTypeDetector} guard — the override fragment's own {@code type: string}
     * collided with the accidental partial-merge object schema TP-003(a)'s own baseline measured, before
     * this task's own conjunction rule (or its override carve-out) existed to keep the two apart.
     */
    @Test
    @DisplayName("TP-003(b): a conjunction subschema whose value type carries a profile override stays"
            + " the override fragment's own $ref, never inlined, and survives folding")
    void overriddenValueTypeKeepsItsOverrideFragmentNeverInlinedIntoTheConjunction() {
        JsonMapperProfile withOverride = overrideProfile(
                ConjOverriddenRight.class,
                JsonSchemaFragment.parse(
                        "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"maxLength\":9}},"
                                + "\"additionalProperties\":false}"));

        JsonNode document = inputDocument(ConjOverriddenParent.class, withOverride);
        JsonNode additionalProperties = document.path("additionalProperties");

        // AllOfFold refuses to dereference (expand or drop) a $ref part, but 2020-12 permits $ref as a
        // sibling keyword, so the fold still merges the non-overridden ConjLeft part's own "label"
        // directly alongside the $ref into one object — not a two-element allOf array.
        assertTrue(
                additionalProperties.has("$ref"),
                "the profile-overridden any-setter's own value type must keep its own $ref to the"
                        + " override fragment's own definition, never a reflection-built bean description;"
                        + " document: " + document);
        assertFalse(
                additionalProperties.has("allOf"),
                "AllOfFold still merges the non-overridden member's own plain part alongside a $ref"
                        + " sibling (2020-12 permits $ref as a sibling keyword) rather than leaving a"
                        + " separate allOf wrapper; document: " + document);
        assertTrue(
                additionalProperties.path("properties").has("label"),
                "the non-overridden any-setter's own bean-valued subschema (ConjLeft.label) must still be"
                        + " inlined and merged alongside the $ref, per C5's unconditional rule,"
                        + " distinguishing the override carve-out from a general retreat to $ref;"
                        + " document: " + document);

        JsonNode resolved = resolve(document, additionalProperties);
        assertEquals(
                "object",
                resolved.path("type").asText(null),
                "the $ref must resolve to the override fragment's own object type; document: " + document);
        assertEquals(
                9,
                resolved.path("properties").path("code").path("maxLength").asInt(-1),
                "the $ref must resolve to the override fragment's own content (code.maxLength: 9) — never"
                        + " ConjOverriddenRight's own reflection-built bean description (code.maxLength:"
                        + " 2); document: " + document);
        assertFalse(
                resolved.path("additionalProperties").asBoolean(true),
                "the $ref must resolve to the override fragment's own content in full, including"
                        + " additionalProperties: false; document: " + document);
    }

    /**
     * TP-003(c): a companion fixture where one any-setter's own value type is self-referential (a bean
     * whose own field is the same type) — asserts the single, bounded-diagnostic outcome (corrected,
     * round-3 security review MEDIUM): never a {@code StackOverflowError}, and never a silent {@code
     * $ref} to a standard, reflection-built {@code $defs} entry.
     *
     * <p>Expected initial result: red (this test's own {@code assertThrows} fails) — measured baseline
     * (L02, {@code evidence/T004.md}): generation succeeds today, throwing nothing — never a {@code
     * StackOverflowError}, but also never the bounded diagnostic this test asserts. The pre-change
     * generator's own accidental partial-merge mechanism (see TP-003(a)'s own measured baseline) renders a
     * document for this self-referential fixture without recursing into it at all, so main-equivalent
     * behavior here is silent success, not a diagnostic or an overflow.
     */
    @Test
    @DisplayName("TP-003(c): a self-referential conjunction value type fails with the single, bounded"
            + " diagnostic, never a StackOverflowError")
    void selfReferentialValueTypeFailsWithTheSingleBoundedDiagnostic() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(ConjSelfReferentialParent.class));

        assertTrue(
                failure.getMessage().contains("ConjSelfReferentialRight"),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("JsonSchemaTypeOverride"),
                "the diagnostic must name the remedy, JsonSchemaTypeOverride; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * TP-003(c) companion (security review recommendation): a mutually recursive pair — the parent's own
     * any-setter value type and the unwrapped child's own any-setter value type each hold a field of the
     * other type, rather than either holding a field of its own type — so the cycle only closes once both
     * conjunction members are on the inline/description stack together. Asserts the same single, bounded
     * diagnostic contract as the direct self-reference above: never a {@code StackOverflowError}, and
     * never a silent standard {@code $defs} {@code $ref}.
     *
     * <p>Two call sites in {@code InputPropertyDescriber} independently refuse a recursive re-entry: the
     * provider-side guard in {@code provideCustomSchemaDefinition} (fires when the ordinary,
     * library-driven field walk reaches a type still registered in {@code inlineInProgress} — message
     * contains "referenced by $ref while being described inline") and the inline helper's own guard in
     * {@code inlineBeanSchema} (fires when the conjunction's own inline composer itself is re-entered for
     * a type already on either recursion set — message contains "re-enters"). For this fixture's own
     * one-hop mutual cycle, the second member's inline population walks into the first member's own field
     * through the library's ordinary (non-inline) field-description path, so the provider-side guard is
     * the one expected to fire — but this assertion accepts either, since a JVM/library scheduling detail
     * neither this test nor the production code documents as guaranteed could route the re-entry through
     * the other call site instead; either is an equally bounded outcome.
     */
    @Test
    @DisplayName("TP-003(c) companion: a mutually recursive pair of conjunction value types fails with the"
            + " single, bounded diagnostic, never a StackOverflowError")
    void mutuallyRecursiveValueTypesFailWithTheSingleBoundedDiagnostic() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(ConjMutualRecursionParent.class));

        boolean providerSideGuardFired =
                failure.getMessage().contains("referenced by $ref while being described" + " inline");
        boolean inlineHelperGuardFired = failure.getMessage().contains("re-enters");
        assertTrue(
                providerSideGuardFired || inlineHelperGuardFired,
                "the diagnostic must come from one of the two recursion guards in InputPropertyDescriber —"
                        + " the provider-side guard (provideCustomSchemaDefinition, \"referenced by $ref"
                        + " while being described inline\") or the inline helper's own guard"
                        + " (inlineBeanSchema, \"re-enters\") — never neither; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("JsonSchemaTypeOverride"),
                "the diagnostic must name the remedy, JsonSchemaTypeOverride, regardless of which of the"
                        + " two guards fired ("
                        + (providerSideGuardFired ? "provider-side" : "inline helper's own")
                        + "); was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded regardless of which of the two guards fired ("
                        + (providerSideGuardFired ? "provider-side" : "inline helper's own")
                        + "); was: " + failure.getMessage());
    }

    // --- Fixtures: TP-001 primary (UW-2AS) ---

    /** UW-2AS child: a named property and a type-use-constrained any-setter. */
    static final class UwTwoAnySettersChild {

        /** A named property, folded onto the parent alongside the extras conjunction. */
        public String name;

        /** The child's own any-setter, type-use-constrained (N16 shape). */
        @JsonAnySetter
        public Map<String, @Size(max = 3) String> extras = new LinkedHashMap<>();
    }

    /** UW-2AS parent: its own type-use-constrained any-setter, plus the unwrapped child's own. */
    static final class UwTwoAnySettersParent {

        /** The unwrapped child, declaring its own any-setter. */
        @JsonUnwrapped
        public UwTwoAnySettersChild inner;

        /**
         * The parent's own any-setter, type-use-constrained with a bound the child's own {@code
         * @Size(max=3)} cannot itself produce, so its survival into the folded document proves the
         * parent's own part was conjoined.
         */
        @JsonAnySetter
        public Map<String, @Size(min = 2) String> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-001 sensitivity (i) — parent-only control ---

    /** A child with no any-setter of its own: the parent's own any-setter is the only one. */
    static final class UwParentOnlyChild {

        /** A named property only — no any-setter. */
        public String name;
    }

    /** A parent whose own any-setter is the only one in play. */
    static final class UwParentOnlyParent {

        /** The unwrapped child, with no any-setter of its own. */
        @JsonUnwrapped
        public UwParentOnlyChild inner;

        /** The parent's own any-setter, unconstrained. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-001 sensitivity (ii) — three same-level any-setters ---

    /** The first unwrapped sibling, declaring its own, differently-constrained any-setter. */
    static final class UwThreeAnySettersChildA {

        /** The sibling's own any-setter, constrained with @Size(max=3). */
        @JsonAnySetter
        public Map<String, @Size(max = 3) String> extras = new LinkedHashMap<>();
    }

    /** The second unwrapped sibling, declaring its own, differently-constrained any-setter. */
    static final class UwThreeAnySettersChildB {

        /** The sibling's own any-setter, constrained with @Pattern. */
        @JsonAnySetter
        public Map<String, @Pattern(regexp = "^[a-z]+$") String> extras = new LinkedHashMap<>();
    }

    /** A parent with its own any-setter, and two unwrapped children each with their own. */
    static final class UwThreeAnySettersParent {

        /** The first unwrapped sibling. */
        @JsonUnwrapped
        public UwThreeAnySettersChildA a;

        /** The second unwrapped sibling. */
        @JsonUnwrapped
        public UwThreeAnySettersChildB b;

        /**
         * The parent's own any-setter, constrained with @Size(min=2) — a bound neither child's own
         * constraint can itself produce, so its presence in the folded document proves all three
         * any-setters were conjoined.
         */
        @JsonAnySetter
        public Map<String, @Size(min = 2) String> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-001 sensitivity (iii) — stricter-wins maxProperties ---

    /** A child whose own any-setter's map is bound with a looser @Size than the parent's own. */
    static final class UwStricterWinsChild {

        /** The child's own any-setter map, bound with @Size(max=10) — the looser of the two. */
        @Size(max = 10)
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A parent whose own any-setter map is bound with a stricter @Size than the child's own. */
    static final class UwStricterWinsParent {

        /** The unwrapped child, whose own any-setter map carries the looser @Size. */
        @JsonUnwrapped
        public UwStricterWinsChild inner;

        /** The parent's own any-setter map, bound with @Size(max=4) — the stricter of the two. */
        @Size(max = 4)
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-001 sensitivity (iii) mirror — roles reversed (child stricter) ---

    /** A child whose own any-setter's map is bound with a stricter @Size than the parent's own. */
    static final class UwStricterWinsMirrorChild {

        /** The child's own any-setter map, bound with @Size(max=4) — the stricter of the two. */
        @Size(max = 4)
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A parent whose own any-setter map is bound with a looser @Size than the child's own. */
    static final class UwStricterWinsMirrorParent {

        /** The unwrapped child, whose own any-setter map carries the stricter @Size. */
        @JsonUnwrapped
        public UwStricterWinsMirrorChild inner;

        /** The parent's own any-setter map, bound with @Size(max=10) — the looser of the two. */
        @Size(max = 10)
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-001 sensitivity (iv) — child named key ---

    /** A child declaring a named, constrained property beside its own any-setter. */
    static final class UwNamedKeyChild {

        /** The child's own named property — not fed to any any-setter. */
        @Size(max = 3)
        public String name;

        /** The child's own any-setter, unconstrained. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A parent whose own any-setter must never be conjoined onto the child's own named key. */
    static final class UwNamedKeyParent {

        /** The unwrapped child, declaring its own named property and its own any-setter. */
        @JsonUnwrapped
        public UwNamedKeyChild inner;

        /** The parent's own any-setter, unconstrained. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-003(a) — object-valued two-any-setter pair ---

    /** The parent's own any-setter value type: a bean with its own constraint. */
    static final class ConjLeft {

        /** The constraint this value type's own subschema must carry once inlined. */
        @Size(max = 3)
        public String label;
    }

    /** The unwrapped child's own any-setter value type: a bean with its own, different constraint. */
    static final class ConjRight {

        /** The constraint this value type's own subschema must carry once inlined. */
        @Size(max = 2)
        public String code;
    }

    /** The unwrapped child, declaring its own bean-valued any-setter. */
    static final class ConjObjectValuedChild {

        /** The child's own any-setter, bean-valued. */
        @JsonAnySetter
        public Map<String, ConjRight> extras = new LinkedHashMap<>();
    }

    /** A parent whose own any-setter is also bean-valued, conjoined with the unwrapped child's own. */
    static final class ConjObjectValuedParent {

        /** The unwrapped child, declaring its own bean-valued any-setter. */
        @JsonUnwrapped
        public ConjObjectValuedChild inner;

        /** The parent's own any-setter, bean-valued. */
        @JsonAnySetter
        public Map<String, ConjLeft> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-003(b) — one value type carries a profile override ---

    /** The same shape as {@link ConjRight}, kept distinct so the override applies to this fixture alone. */
    static final class ConjOverriddenRight {

        /** Present so the type looks bean-like beside its own profile override. */
        @Size(max = 2)
        public String code;
    }

    /** The unwrapped child, declaring its own any-setter over the overridden value type. */
    static final class ConjOverriddenChild {

        /** The child's own any-setter, whose value type this test's profile overrides. */
        @JsonAnySetter
        public Map<String, ConjOverriddenRight> extras = new LinkedHashMap<>();
    }

    /** A parent pairing a plain bean-valued any-setter with the child's own overridden one. */
    static final class ConjOverriddenParent {

        /** The unwrapped child, declaring its own any-setter over the overridden value type. */
        @JsonUnwrapped
        public ConjOverriddenChild inner;

        /** The parent's own any-setter, bean-valued, never overridden. */
        @JsonAnySetter
        public Map<String, ConjLeft> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-003(c) — one value type is self-referential ---

    /** A bean value type whose own field is the same type — the self-referential shape under test. */
    static final class ConjSelfReferentialRight {

        /** An ordinary member, beside the self-referential one. */
        public String code;

        /** The self-referential field: the same type as its own declaring class. */
        public ConjSelfReferentialRight next;
    }

    /** The unwrapped child, declaring its own any-setter over the self-referential value type. */
    static final class ConjSelfReferentialChild {

        /** The child's own any-setter, whose value type is self-referential. */
        @JsonAnySetter
        public Map<String, ConjSelfReferentialRight> extras = new LinkedHashMap<>();
    }

    /** A parent pairing a plain bean-valued any-setter with the child's own self-referential one. */
    static final class ConjSelfReferentialParent {

        /** The unwrapped child, declaring its own any-setter over the self-referential value type. */
        @JsonUnwrapped
        public ConjSelfReferentialChild inner;

        /** The parent's own any-setter, bean-valued, never self-referential. */
        @JsonAnySetter
        public Map<String, ConjLeft> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: TP-003(c) companion — mutually recursive value types (security review) ---

    /** A bean value type whose own field is {@link MutualRight} — half of the mutual-recursion pair. */
    static final class MutualLeft {

        /** The mutually recursive field: the other half of the pair, never this class's own type. */
        public MutualRight partner;
    }

    /** A bean value type whose own field is {@link MutualLeft} — the other half of the mutual-recursion pair. */
    static final class MutualRight {

        /** The mutually recursive field: the other half of the pair, never this class's own type. */
        public MutualLeft partner;
    }

    /** The unwrapped child, declaring its own any-setter over one half of the mutually recursive pair. */
    static final class ConjMutualRecursionChild {

        /** The child's own any-setter, whose value type is one half of the mutually recursive pair. */
        @JsonAnySetter
        public Map<String, MutualRight> extras = new LinkedHashMap<>();
    }

    /** A parent pairing its own any-setter with the child's own, the two forming a mutually recursive pair. */
    static final class ConjMutualRecursionParent {

        /** The unwrapped child, declaring its own any-setter over the other half of the pair. */
        @JsonUnwrapped
        public ConjMutualRecursionChild inner;

        /** The parent's own any-setter, whose value type is the other half of the mutually recursive pair. */
        @JsonAnySetter
        public Map<String, MutualLeft> extras = new LinkedHashMap<>();
    }
}
