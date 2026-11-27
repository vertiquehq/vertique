// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonProfileRegistry;
import dev.vertique.json.Jackson2JsonProfileCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A member-level {@code @Schema(additionalProperties = FALSE)} on a
 * property whose value type carries its own any-setter reuses the <strong>existing</strong>
 * member-level inline path — the case-insensitive (CI) branch's override-first rule, {@code
 * requireNotDelegating} refusal, and scalar-creator check — with an added extras-suppressed flag,
 * rather than a new mechanism.
 *
 * <ul>
 *   <li>A member-level {@code FALSE} on an any-setter-carrying value type closes that member's own
 *       extras, described inline; the same value type referenced elsewhere without the annotation
 *       keeps its own standalone rendering, unaffected.
 *   <li>Composition and precedence: case-insensitive inline plus {@code FALSE} composes; a profile
 *       override plus {@code FALSE} refuses with the remedy; a member-level {@code TRUE} does not
 *       loosen a class-level {@code FALSE}; three self-referential shapes (re-entry through the inline
 *       path, through a plain {@code $ref}, and at the root) each fail with the single bounded
 *       diagnostic, never a {@code StackOverflowError} and never a silent standard {@code $defs}
 *       fallback; regression twins prove the pre-existing case-insensitive-inline path is bounded the
 *       same way, at the same depth.
 * </ul>
 *
 * <p>Every document is generated under the registry's built-in {@code vertique} profile, except
 * where a test declares its own profile (the override fixture).
 */
class MemberLevelAdditionalPropertiesClosureTest {

    // --- Generation helpers (fixture-construction isolation, so Given/When/Then stays visible below) ---

    private static JsonProfileCodec profile() {
        return new DefaultJsonProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    private static JsonNode inputDocument(Type type) {
        return inputDocument(type, profile());
    }

    private static JsonNode inputDocument(Type type, JsonProfileCodec customProfile) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(customProfile).generateCanonical(type));
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same
     * schema whether the generator inlined the value type or shared it under {@code $defs}.
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

    private static JsonProfileCodec overrideProfile(Class<?> overriddenClass, JsonSchemaFragment fragment) {
        List<JsonSchemaTypeOverride> overrides = List.of(JsonSchemaTypeOverride.input(overriddenClass, fragment));
        return new Jackson2JsonProfileCodec() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("member-false-override");
            }

            @Override
            protected ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return overrides;
            }
        };
    }

    /**
     * Runs {@code generation}, asserting it fails with a bounded {@link JsonSchemaGenerationException}
     * rather than succeeding silently or overflowing the stack. A {@link StackOverflowError} is caught
     * here only to turn it into an actionable, distinguishing failure message — never to let the test
     * pass — since an unbounded recursion is exactly the defect the recursion bound exists to close, not
     * an acceptable substitute for the bounded diagnostic.
     *
     * @param generation the generation action under test
     * @return the thrown exception, once confirmed
     */
    private static JsonSchemaGenerationException assertBoundedRefusal(Supplier<JsonNode> generation) {
        try {
            JsonNode document = generation.get();
            fail("expected a bounded JsonSchemaGenerationException, but generation succeeded silently"
                    + " instead — a self-referential member must never fall back to a silent, unbounded"
                    + " description; document: " + document);
        } catch (StackOverflowError overflow) {
            fail("expected a bounded JsonSchemaGenerationException, but generation overflowed the stack"
                    + " (StackOverflowError) instead — an unbounded recursion, not the bounded diagnostic"
                    + " the recursion bound requires");
        } catch (JsonSchemaGenerationException expected) {
            return expected;
        }
        throw new AssertionError("unreachable");
    }

    /**
     * Runs {@code generation}, capturing every {@code WARNING}-level record published through {@link
     * InputPropertyDescriber}'s own {@code System.Logger}-backed JUL logger while it runs (mirrors
     * {@code MapValueDescriptionTest}'s own idiom).
     *
     * @param warnings   the accumulator, appended in publish order
     * @param generation the generation action, returning the generated document
     * @return the document {@code generation} returned
     */
    private static JsonNode captureWarnings(List<String> warnings, Supplier<JsonNode> generation) {
        Logger logger = Logger.getLogger(InputPropertyDescriber.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(String.valueOf(record.getMessage()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            return generation.get();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================================
    // A member-level FALSE on an any-setter-carrying value type closes that member's own extras,
    // reusing the existing inline path.
    // ==================================================================================================

    /**
     * Given: {@code ClosedMemberParent.child}, annotated {@code @Schema(additionalProperties =
     * FALSE)}, whose value type {@link AnySetterChild} carries its own any-setter; a companion property
     * {@code open}, the same value type, unannotated.
     *
     * <p>When: the input-direction schema is generated for {@link ClosedMemberParent}.
     *
     * <p>Then: {@code child}'s own inline description carries {@code properties.name} unchanged and
     * {@code additionalProperties: false}; {@code open}'s own rendering of the same value type stays
     * {@code additionalProperties: {"type":"string"}}, unaffected — the sensitivity proof that the
     * rule is scoped to the annotated member, not the value type globally.
     */
    @Test
    @DisplayName("a member-level FALSE on an any-setter-carrying value type closes that member's"
            + " own extras, reusing the existing inline path; an unannotated reference elsewhere is unaffected")
    void memberLevelFalseClosesExtrasViaTheExistingInlinePath() {
        JsonNode document = inputDocument(ClosedMemberParent.class);
        JsonNode childRaw = document.path("properties").path("child");
        JsonNode child = resolve(document, childRaw);

        assertFalse(
                childRaw.has("$ref"),
                "the FALSE-annotated member must reuse the existing inline path — a genuine inline"
                        + " description, never a $ref shared with the unannotated companion's own"
                        + " standalone schema (both members reference the same value type, so the"
                        + " ordinary path would otherwise share one $defs entry between them); document: "
                        + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "the member's own inline properties.name rendering must stay unchanged; document: " + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "the member-level @Schema(additionalProperties = FALSE) must close the member's own"
                        + " extras — additionalProperties: false — reusing the existing inline path;"
                        + " document: " + document);

        JsonNode open = resolve(document, document.path("properties").path("open"));
        assertEquals(
                "{\"type\":\"string\"}",
                open.path("additionalProperties").toString(),
                "the same value type referenced elsewhere, without the annotation, must keep its own"
                        + " standalone rendering — additionalProperties: {\"type\":\"string\"} — unaffected"
                        + " by the FALSE-annotated member's own inline closure; document: " + document);
    }

    // ==================================================================================================
    // Composition and precedence, plus the regression twins for the case-insensitive-inline path.
    // ==================================================================================================

    /**
     * A member both case-insensitive-inline and {@code FALSE} composes as one inline description:
     * case-insensitive handling kept ({@code patternProperties}), extras suppressed ({@code
     * additionalProperties: false}).
     */
    @Test
    @DisplayName("a member both case-insensitive-inline and FALSE composes as one inline"
            + " description — case-insensitive kept, extras suppressed")
    void caseInsensitiveInlinePlusFalseComposes() {
        JsonNode document = inputDocument(CiFalseHolder.class);
        JsonNode child = document.path("properties").path("child");

        assertFalse(
                child.has("$ref"),
                "a member bound case-insensitively only through its own contextual annotation must not"
                        + " share the type's ordinary (case-sensitive) definition; document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "the case-insensitive inline description's own properties.name rendering must be kept;" + " document: "
                        + document);
        JsonNode patternProperties = child.path("patternProperties");
        assertTrue(
                patternProperties.isObject() && !patternProperties.isEmpty(),
                "the case-insensitive inline description must keep its own patternProperties, composed"
                        + " alongside the FALSE-suppressed extras, not replaced by it; document: " + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "the member-level FALSE must still suppress extras on a case-insensitive-inline member —"
                        + " composed, not refused or ignored; document: " + document);
    }

    /**
     * A member {@code FALSE} whose value type carries a profile override refuses generation with a
     * bounded diagnostic naming the member and the override-fragment remedy — the override wins, so
     * {@code FALSE} cannot be silently honored nor silently dropped.
     */
    @Test
    @DisplayName("a member FALSE whose value type carries a profile override refuses with a"
            + " bounded diagnostic naming the member and the override remedy")
    void overridePlusFalseRefusesWithTheRemedy() {
        JsonProfileCodec withOverride = overrideProfile(
                OverriddenChild.class,
                JsonSchemaFragment.parse("{\"type\":\"object\",\"format\":\"member-false-override\",\"properties\":{},"
                        + "\"additionalProperties\":false}"));

        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(OverrideFalseHolder.class, withOverride));

        assertTrue(
                failure.getMessage().contains("child"),
                "the diagnostic must name the member \"child\"; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("override")
                        || failure.getMessage().contains("JsonSchemaTypeOverride"),
                "the diagnostic must name the remedy (the override fragment); was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * A member-level {@code TRUE} must not loosen a class-level {@code FALSE} already closing the
     * value type — a regression control. The class-level {@code FALSE} on {@link
     * ClassLevelClosedChild} already closes it.
     */
    @Test
    @DisplayName("a member-level TRUE does not loosen a class-level FALSE already closing the value type")
    void memberLevelTrueDoesNotLoosenAClassLevelFalse() {
        JsonNode document = inputDocument(MemberTrueOverClassFalseHolder.class);
        JsonNode child = resolve(document, document.path("properties").path("child"));

        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "a member-level TRUE must never loosen a class-level FALSE already closing the value"
                        + " type; document: " + document);
    }

    /**
     * A nested self-referential member, where the re-entry arrives through the inline path itself:
     * {@code Holder.node} is {@code FALSE}-annotated (registers {@link SelfReferentialInlineNode} in
     * {@code inlineInProgress}), and {@code Node.child} is itself {@code FALSE}-annotated, re-entering
     * the same inline population for {@link SelfReferentialInlineNode} while it is still in progress.
     */
    @Test
    @DisplayName("a nested self-referential member, re-entering through the inline"
            + " path itself, refuses with the single bounded diagnostic")
    void nestedSelfReferenceThroughTheInlinePathRefuses() {
        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(SelfReferentialInlineHolder.class));

        assertTrue(
                failure.getMessage().contains(SelfReferentialInlineNode.class.getSimpleName()),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * The same nesting as the inline case, but {@code Node.child} is unannotated: the re-entry reaches the
     * provider's own {@code provideCustomSchemaDefinition} for {@link SelfReferentialRefNode} while it
     * is still registered in {@code inlineInProgress} — exercising the provider-side refusal, never the
     * "return null on re-entry" branch's own silent fallback to a standard, reflection-built {@code
     * $defs} entry.
     */
    @Test
    @DisplayName("a nested self-referential member, re-entering through a plain $ref,"
            + " refuses with the single bounded diagnostic — never a silent standard $defs fallback")
    void nestedSelfReferenceThroughAPlainRefRefuses() {
        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(SelfReferentialRefHolder.class));

        assertTrue(
                failure.getMessage().contains(SelfReferentialRefNode.class.getSimpleName()),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * The simpler root-level self-referential shape: {@link SelfReferentialRootNode} declares its own
     * {@code FALSE}-annotated field of its own type, generated as the root. The root type is already
     * registered in {@code inProgress} from generation's own top-level registration, and the member-level
     * inline path must still produce the bounded diagnostic.
     */
    @Test
    @DisplayName("the simpler root-level self-referential shape also refuses with the single" + " bounded diagnostic")
    void rootLevelSelfReferenceRefuses() {
        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(SelfReferentialRootNode.class));

        assertTrue(
                failure.getMessage().contains(SelfReferentialRootNode.class.getSimpleName()),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * Regression twin (inline): the same {@code Holder -> Node -> Node} nesting as the inline case
     * above, but through the case-insensitive-inline path (member-level {@code @JsonFormat}), never the
     * {@code FALSE} rule — proving that branch is bounded the same way, at the same depth, through the
     * shared recursion-bound helper.
     */
    @Test
    @DisplayName("Regression twin (inline): the case-insensitive-inline path's own nested"
            + " self-reference, re-entering through the inline path itself, is bounded the same way")
    void caseInsensitiveNestedSelfReferenceThroughTheInlinePathIsBounded() {
        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(CiSelfReferentialInlineHolder.class));

        assertTrue(
                failure.getMessage().contains(CiSelfReferentialInlineNode.class.getSimpleName()),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * Regression twin (ref): the same nesting as the plain-{@code $ref} case above, but through the
     * case-insensitive-inline path — {@code Node.child} is unannotated, so the re-entry reaches the
     * provider's own {@code provideCustomSchemaDefinition} instead.
     */
    @Test
    @DisplayName("Regression twin (ref): the case-insensitive-inline path's own nested"
            + " self-reference, re-entering through a plain $ref, is bounded the same way")
    void caseInsensitiveNestedSelfReferenceThroughAPlainRefIsBounded() {
        JsonSchemaGenerationException failure =
                assertBoundedRefusal(() -> inputDocument(CiSelfReferentialRefHolder.class));

        assertTrue(
                failure.getMessage().contains(CiSelfReferentialRefNode.class.getSimpleName()),
                "the diagnostic must name the self-referential type; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the diagnostic must stay bounded; was: " + failure.getMessage());
    }

    /**
     * Control: a Map-typed member's own {@code @Schema(additionalProperties = FALSE)} stays ignored
     * (scoped to a Map-typed member specifically), with the value schema still rendered and exactly one
     * WARNING emitted — reusing {@code MapValueDescriptionTest}'s own warning-capture idiom, proving the
     * member-level closure rule does not reach a Map-typed member at all.
     */
    @Test
    @DisplayName("Control: a Map-typed member's own FALSE stays ignored — the value"
            + " schema is still rendered, with exactly one WARNING")
    void mapTypedMemberFalseStaysIgnoredWithOneWarning() {
        List<String> warnings = new ArrayList<>();
        JsonNode document = captureWarnings(warnings, () -> inputDocument(MapControlDto.class));
        JsonNode attributes = document.path("properties").path("attributes").path("additionalProperties");

        assertEquals(
                "{\"type\":\"string\"}",
                attributes.toString(),
                "a Map-typed member's own FALSE must stay ignored — the value schema is rendered exactly"
                        + " as the no-annotation control would render it; document: " + document);
        assertTrue(
                warnings.stream()
                        .anyMatch(message -> message.contains("attributes")
                                && message.contains("has no effect on the generated input schema")),
                "exactly one WARNING naming the member \"attributes\" must be emitted; captured: " + warnings);
    }

    // ==================================================================================================
    // propertySchema's extrasSuppressed trigger must also see an any-setter reachable only through the
    // value type's own @JsonUnwrapped child: a member-level FALSE on such a value type must not fall
    // through to the open shared $ref, silently dropping FALSE.
    // ==================================================================================================

    /**
     * Given: {@link UnwrappedChildFalseHolder}, whose {@code child} member is {@code
     * @Schema(additionalProperties = FALSE)}-annotated; the member's own value type, {@link Y}, carries no
     * any-setter of its own — the any-setter is reachable only through {@code Y}'s own {@code
     * @JsonUnwrapped} child, {@link Z}. A companion property {@code open}, the same value type,
     * unannotated.
     *
     * <p>When: the input-direction schema is generated for {@link UnwrappedChildFalseHolder}.
     *
     * <p>Then: {@code child}'s own inline description carries {@code properties.yname}, {@code
     * properties.zname} (the unwrapped child's own properties folded in), and {@code
     * additionalProperties: false}; {@code open}'s own rendering of the same value type keeps the shared
     * {@code $ref}/{@code $defs} entry, whose own {@code additionalProperties} is the described extras
     * ({@code {"type":"string"}}) — unaffected.
     */
    @Test
    @DisplayName("a member-level FALSE closes an any-setter reached only through" + " an unwrapped child")
    void memberLevelFalseClosesAnAnySetterReachedThroughAnUnwrappedChild() {
        JsonNode document = inputDocument(UnwrappedChildFalseHolder.class);
        JsonNode childRaw = document.path("properties").path("child");
        JsonNode child = resolve(document, childRaw);

        assertFalse(
                childRaw.has("$ref"),
                "the FALSE-annotated member must be described inline once its any-setter is reachable only"
                        + " through its own unwrapped child, reusing the existing inline path — never a"
                        + " bare $ref shared with the unannotated companion's own standalone schema;"
                        + " document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("zname").toString(),
                "the unwrapped child's own constrained property must still be folded onto the inline"
                        + " description, unchanged; document: " + document);
        assertTrue(
                child.path("properties").has("yname"),
                "the member's own direct property must still be folded onto the inline description;" + " document: "
                        + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "the member-level @Schema(additionalProperties = FALSE) must close the any-setter that is"
                        + " reachable only through the unwrapped child — additionalProperties: false;"
                        + " document: " + document);

        JsonNode open = resolve(document, document.path("properties").path("open"));
        assertEquals(
                "{\"type\":\"string\"}",
                open.path("additionalProperties").toString(),
                "the same value type referenced elsewhere, without the annotation, must keep its own"
                        + " standalone rendering — additionalProperties: {\"type\":\"string\"} — unaffected"
                        + " by the FALSE-annotated member's own inline closure; document: " + document);
    }

    /**
     * Given: {@link UnwrappedChildConjunctionFalseHolder}, whose {@code child} member is {@code
     * @Schema(additionalProperties = FALSE)}-annotated; the member's own value type, {@link W}, carries its
     * own any-setter <em>and</em> an unwrapped {@link Z} child carrying its own — a conjunction
     * candidate.
     *
     * <p>When: the input-direction schema is generated for {@link UnwrappedChildConjunctionFalseHolder}.
     *
     * <p>Then: {@code child}'s own inline description carries {@code additionalProperties: false} — the
     * member-level {@code FALSE} suppresses the parent-any-setter/unwrapped-child-any-setter conjunction
     * entirely, never the conjoined value schema. {@code W}'s own builder-level any-setter alone would
     * already be closable by the existing rule; what is under test here is that the closure is not
     * defeated by the presence of the conjunction.
     */
    @Test
    @DisplayName(
            "a member-level FALSE suppresses a parent-any-setter/unwrapped-" + "child-any-setter conjunction entirely")
    void memberLevelFalseOnAnUnwrappedChildWithItsOwnAnySetterAndAParentAnySetter() {
        JsonNode document = inputDocument(UnwrappedChildConjunctionFalseHolder.class);
        JsonNode childRaw = document.path("properties").path("child");
        JsonNode child = resolve(document, childRaw);

        assertFalse(
                childRaw.has("$ref"),
                "the FALSE-annotated member must be described inline, never a bare $ref; document: " + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "the member-level FALSE must suppress the parent-any-setter/unwrapped-child-any-setter"
                        + " conjunction entirely — additionalProperties: false, never the conjoined value"
                        + " schema; document: " + document);
    }

    /**
     * Control: {@link PlainMemberFalseHolder}, whose {@code child} member is {@code
     * @Schema(additionalProperties = FALSE)}-annotated; the member's own value type, {@link Plain}, carries
     * no any-setter anywhere — directly, nor through any unwrapped child (it has none). The closure
     * rule covers any-setter-carrying value types only, so the rendering stays unchanged.
     *
     * <p>Neither {@code caseInsensitiveInline} nor {@code extrasSuppressed} is true (Plain has no
     * any-setter anywhere, so {@code nestedBuilder.getAnySetter()} is {@code null}), so {@code
     * propertySchema} never enters the inline-description branch at all and falls through to the
     * ordinary field path; the generator's own library then inlines {@code Plain} directly (never {@code
     * $ref}/{@code $defs}) simply because it is referenced exactly once in this fixture — an unrelated,
     * single-reference optimization, not the inline path under test — carrying {@code properties.name}
     * and no {@code additionalProperties} keyword at all. Generation succeeds (proven implicitly: {@code
     * inputDocument} would have thrown otherwise), proving the closure rule never reaches a plain bean.
     */
    @Test
    @DisplayName("Control: a member-level FALSE on a bean without any any-setter is left to the type's own default")
    void memberLevelFalseOnABeanWithoutAnyAnySetterIsLeftToTheTypesOwnDefault() {
        JsonNode document = inputDocument(PlainMemberFalseHolder.class);
        JsonNode childRaw = document.path("properties").path("child");

        assertFalse(
                childRaw.has("$ref"),
                "Plain is referenced exactly once in this fixture, so the generator's"
                        + " own library inlines it directly regardless of the FALSE annotation — an"
                        + " unrelated, single-reference optimization, never the member-level inline path"
                        + " (neither caseInsensitiveInline nor extrasSuppressed is true, since Plain has no"
                        + " any-setter anywhere); document: " + document);
        assertTrue(
                childRaw.path("properties").has("name"),
                "the inlined schema must still carry the value type's own property; document: " + document);
        assertTrue(
                childRaw.path("additionalProperties").isMissingNode(),
                "the FALSE annotation has no effect whatsoever on a value type with no"
                        + " any-setter anywhere — no additionalProperties keyword is written at all, neither"
                        + " true nor false; document: " + document);
    }

    // ==================================================================================================
    // Class-level FALSE on the plain case-insensitive inline path.
    // ==================================================================================================

    /**
     * A value type closed at the
     * <strong>class</strong> level with {@code @Schema(additionalProperties = FALSE)}, reached only
     * through the plain case-insensitive inline path (member-level {@code @JsonFormat} only — no
     * member-level {@code FALSE}), must still publish {@code additionalProperties: false}. The hand-built
     * inline node never takes the standard {@code CustomDefinition}/{@code AttributeInclusion.YES} path
     * that applies the class-level closure elsewhere.
     */
    @Test
    @DisplayName("a class-level FALSE on a value type reached through the plain"
            + " case-insensitive inline path publishes additionalProperties: false")
    void classLevelFalseOnPlainCaseInsensitiveInlinePathIsPreserved() {
        JsonNode document = inputDocument(CiClassLevelFalseHolder.class);
        JsonNode child = document.path("properties").path("child");

        assertFalse(
                child.has("$ref"),
                "a member bound case-insensitively only through its own contextual annotation must be"
                        + " described inline, never a bare $ref; document: " + document);
        assertTrue(
                child.path("patternProperties").isObject()
                        && !child.path("patternProperties").isEmpty(),
                "the plain case-insensitive inline description must keep its own patternProperties;" + " document: "
                        + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                child.path("properties").path("name").toString(),
                "the value type's own properties must still render; document: " + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "the class-level @Schema(additionalProperties = FALSE) must survive the plain"
                        + " case-insensitive inline path — additionalProperties: false, never dropped;"
                        + " document: " + document);
    }

    /**
     * Same shape as {@link #classLevelFalseOnPlainCaseInsensitiveInlinePathIsPreserved()}, with the
     * class-level closure inherited rather than declared on the value type itself.
     */
    @Test
    @DisplayName("an inherited class-level FALSE is preserved on the plain case-insensitive inline path")
    void inheritedClassLevelFalseOnPlainCaseInsensitiveInlinePathIsPreserved() {
        JsonNode document = inputDocument(CiInheritedClassLevelFalseHolder.class);
        JsonNode child = document.path("properties").path("child");

        assertFalse(child.has("$ref"), "must be described inline; document: " + document);
        assertTrue(
                child.path("additionalProperties").isBoolean()
                        && !child.path("additionalProperties").asBoolean(),
                "an inherited class-level FALSE must stay closed on the plain CI inline path;" + " document: "
                        + document);
    }

    // --- Fixtures: member-level FALSE on an any-setter-carrying value type ---

    /** Value type that carries its own any-setter. */
    static final class AnySetterChild {

        /** The constraint the inline description's own properties.name must keep unchanged. */
        @Size(max = 3)
        public String name;

        /** The any-setter the member-level FALSE rule must suppress on the annotated member only. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** Parent: a FALSE-closed member, plus an unannotated companion referencing the same type. */
    static final class ClosedMemberParent {

        /** The FALSE-closed member under test. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public AnySetterChild child;

        /** The companion, unannotated reference to the same value type — must stay unaffected. */
        public AnySetterChild open;
    }

    // --- Fixtures: case-insensitive inline plus FALSE ---

    /** Value type: carries its own any-setter, like {@link AnySetterChild}. */
    static final class CiFalseChild {

        /** Kept unchanged by the composition. */
        @Size(max = 3)
        public String name;

        /** Suppressed by the member's own FALSE, composed alongside case-insensitivity. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** Holder: the member is both case-insensitive-inline (own @JsonFormat) and FALSE. */
    static final class CiFalseHolder {

        /** Bound case-insensitively only through this member's own contextual annotation, and FALSE. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public CiFalseChild child;
    }

    // --- Fixtures: override plus FALSE ---

    /** Value type: a profile override is declared for this class in the test's own profile. */
    static final class OverriddenChild {

        /** Present so the type looks bean-like beside its own profile override. */
        @Size(max = 3)
        public String name;

        /** An any-setter, so this type would otherwise be eligible for the member-level inline rule. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** Holder: the member is FALSE, and its value type carries a profile override. */
    static final class OverrideFalseHolder {

        /** FALSE, but the value type's own profile override must win — refusing, not silently dropping. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public OverriddenChild child;
    }

    // --- Fixtures: member-level TRUE over a class-level FALSE ---

    /** Value type: already closed at the class level. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static final class ClassLevelClosedChild {

        /** An ordinary property, beside the class-level closure. */
        @Size(max = 3)
        public String name;

        /** The any-setter the class-level FALSE already closes. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** Holder: the member is TRUE, over a value type the class level already closes. */
    static final class MemberTrueOverClassFalseHolder {

        /** TRUE must not loosen the value type's own class-level FALSE. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        public ClassLevelClosedChild child;
    }

    // --- Fixtures: self-reference through the member-level FALSE rule ---

    /** Self-referential type: its own field is the same type, FALSE-annotated. */
    static final class SelfReferentialInlineNode {

        /** The self-referential field, itself FALSE-annotated — the inline-registration re-entry. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public SelfReferentialInlineNode child;

        /** An any-setter, so this type is eligible for the member-level inline rule at every depth. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** Holder: one level above the root, so the node type itself is not pre-registered. */
    static final class SelfReferentialInlineHolder {

        /** FALSE, registering the node type in {@code inlineInProgress} for the duration of its own population. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public SelfReferentialInlineNode node;
    }

    // --- Fixtures: self-reference through a plain $ref ---

    /** Self-referential type: its own field is the same type, unannotated (plain $ref). */
    static final class SelfReferentialRefNode {

        /** The self-referential field, unannotated — reaches the provider's own re-entry guard. */
        public SelfReferentialRefNode child;

        /** An any-setter, so this type is eligible for the member-level inline rule at every depth. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** Holder: one level above the root, so the node type itself is not pre-registered. */
    static final class SelfReferentialRefHolder {

        /** FALSE, registering {@link SelfReferentialRefNode} in {@code inlineInProgress} for its own population. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public SelfReferentialRefNode node;
    }

    // --- Fixtures: the simpler root-level shape ---

    /** Self-referential type, generated directly as the root. */
    static final class SelfReferentialRootNode {

        /** The self-referential field, itself FALSE-annotated. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public SelfReferentialRootNode child;

        /** An any-setter, so this type is eligible for the member-level inline rule. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    // --- Fixtures: regression twins — the same nesting through the case-insensitive-inline path ---

    /** Twin (inline) self-referential type: its own field is the same type, CI-annotated. */
    static final class CiSelfReferentialInlineNode {

        /** The self-referential field, itself bound case-insensitively through its own @JsonFormat. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public CiSelfReferentialInlineNode child;

        /** An any-setter, so this type is eligible for the case-insensitive-inline path. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** Twin (inline) holder: one level above the root. */
    static final class CiSelfReferentialInlineHolder {

        /** Bound case-insensitively only through this member's own contextual annotation. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public CiSelfReferentialInlineNode node;
    }

    /** Twin (ref) self-referential type: its own field is the same type, unannotated. */
    static final class CiSelfReferentialRefNode {

        /** The self-referential field, unannotated — reaches the provider's own re-entry guard. */
        public CiSelfReferentialRefNode child;

        /** An any-setter, so this type is eligible for the case-insensitive-inline path. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** Twin (ref) holder: one level above the root. */
    static final class CiSelfReferentialRefHolder {

        /** Bound case-insensitively only through this member's own contextual annotation. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public CiSelfReferentialRefNode node;
    }

    // --- Fixtures: control — a Map-typed member's own FALSE stays ignored ---

    /** Control DTO: a Map-typed member carrying the member-level FALSE the closure rule must not reach. */
    static final class MapControlDto {

        /** A Map-typed member — any additionalProperties annotation is ignored here. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public Map<String, String> attributes = new LinkedHashMap<>();
    }

    // --- Fixtures: an any-setter reachable only through an unwrapped child ---

    /**
     * Value type reached only through {@link Y}'s own {@code @JsonUnwrapped} child: carries its own
     * any-setter (the shape {@code UnwrappedAnySetterExtrasConjunctionTest} also uses).
     */
    static final class Z {

        /** The constraint the inline description's own properties.zname must keep unchanged. */
        @Size(max = 3)
        public String zname;

        /** The any-setter reachable only through Z, never directly on Y. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** Y: no any-setter of its own — the any-setter is reachable only through its own unwrapped Z child. */
    static final class Y {

        /** Y's own direct property, folded onto the inline description alongside Z's own. */
        public String yname;

        /** The unwrapped child whose own any-setter is the one under test. */
        @JsonUnwrapped
        public Z z;
    }

    /** Holder: a FALSE-closed member whose any-setter is reachable only through an unwrapped child. */
    static final class UnwrappedChildFalseHolder {

        /** The FALSE-closed member under test — Y's own any-setter is reachable only through Z. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public Y child;

        /** The companion, unannotated reference to the same value type — must stay unaffected. */
        public Y open;
    }

    /** W: its own any-setter, plus an unwrapped Z child carrying its own — a conjunction candidate. */
    static final class W {

        /** W's own any-setter, conjoined with Z's own once both are reachable together. */
        @JsonAnySetter
        public Map<String, @Size(max = 3) String> extras = new LinkedHashMap<>();

        /** The unwrapped child, whose own any-setter conjoins with W's own. */
        @JsonUnwrapped
        public Z z;
    }

    /** Holder: a FALSE-closed member whose value type is a parent/unwrapped-child any-setter conjunction. */
    static final class UnwrappedChildConjunctionFalseHolder {

        /** The FALSE-closed member under test — W's own any-setter conjoins with Z's own, reached via z. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public W child;
    }

    // --- Fixtures: control — a bean without any any-setter, directly or through any unwrapped child ---

    /** Plain: no any-setter anywhere — outside the closure rule's scope. */
    static final class Plain {

        /** An ordinary property, with no any-setter beside it. */
        public String name;
    }

    /** Holder: a FALSE-closed member whose value type carries no any-setter at all. */
    static final class PlainMemberFalseHolder {

        /** FALSE, but Plain carries no any-setter anywhere — left to the type's own default rendering. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public Plain child;
    }

    // --- Fixtures: class-level FALSE on the plain CI inline path ---

    /** Value type closed at class level; no member-level FALSE, no any-setter. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static class ClassLevelFalseCiChild {

        /** Kept by the inline description beside the class-level closure. */
        @Size(max = 3)
        public String name;
    }

    /** Holder: plain case-insensitive inline only — no member-level {@code FALSE}. */
    static final class CiClassLevelFalseHolder {

        /** Bound case-insensitively only through this member's own contextual annotation. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public ClassLevelFalseCiChild child;
    }

    /** Subclass inheriting {@link ClassLevelFalseCiChild}'s class-level closure. */
    static final class InheritedClassLevelFalseCiChild extends ClassLevelFalseCiChild {}

    /** Holder for the inherited class-level FALSE shape. */
    static final class CiInheritedClassLevelFalseHolder {

        /** Bound case-insensitively only through this member's own contextual annotation. */
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        public InheritedClassLevelFalseCiChild child;
    }
}
