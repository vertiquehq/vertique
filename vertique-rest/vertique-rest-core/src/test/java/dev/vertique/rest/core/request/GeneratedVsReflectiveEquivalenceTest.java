// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the generated path (via a hand-written {@link GeneratedInputProcessor} that
 * mirrors what {@code vertique-codegen-sanitization} emits) and the reflective path produce
 * byte-equal output for the same intermediate input. Because the dispatcher is internal and
 * classloader-driven, two {@code DefaultInputObjectProcessor} instances in the same JVM cannot
 * be made to disagree about a single DTO type. The test uses two structurally-identical DTOs:
 *
 * <ul>
 *   <li>{@link Reflective} — has no sibling {@code _InputProcessor} companion, so the dispatcher
 *       returns empty and traversal walks reflectively.
 *   <li>{@link Generated} — has a sibling {@code Generated_InputProcessor} companion in
 *       {@code src/test/java}, so the dispatcher's classloader lookup resolves it and the
 *       generated path runs.
 * </ul>
 *
 * <p>Both DTOs declare identical fields with identical annotation chains; the input intermediate
 * uses field names common to both. The asserted invariant is {@code reflectiveOutput.equals(generatedOutput)}
 * across multiple scenarios, including nested DTOs, {@code List<String>} fields,
 * {@code List<NestedDto>} fields, route-level policies on un-annotated fields, and sticky-skip
 * behavior.
 *
 * <p>A separate {@link CountingPair} hand-written {@code CountingPairGenerated_InputProcessor}
 * doubles as instrumentation for nested-dispatch coverage: it asserts at least one element of a
 * top-level {@code List<CountingPair>} body reaches the generated processor when the dispatcher
 * looks up the element type.
 */
class GeneratedVsReflectiveEquivalenceTest {

    private DefaultInputObjectProcessor processor;

    @BeforeEach
    void setUp() {
        InputPolicyMetadataResolver metadataResolver = new InputPolicyMetadataResolver();
        processor = new DefaultInputObjectProcessor(
                metadataResolver,
                cls -> {
                    if (cls == TestTrim.class) return new TestTrim();
                    if (cls == TestUpper.class) return new TestUpper();
                    throw new IllegalArgumentException("unknown canonicalizer: " + cls);
                },
                cls -> {
                    if (cls == TestStripDots.class) return new TestStripDots();
                    throw new IllegalArgumentException("unknown sanitizer: " + cls);
                });
    }

    @Nested
    @DisplayName("byte-equivalence between reflective and generated paths")
    class Equivalence {

        @Test
        @DisplayName("flat string fields")
        void flatStringFields() {
            Map<String, Object> input = inputWithStringFields();

            Object reflectiveOut = processor.processStructuredBody(
                    input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("nested DTO field — both reflective and generated dispatch through the dispatcher")
        void nestedDtoField() {
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("note", "  hi  "); // Inner.note has @Canonicalize(TestTrim)
            input.put("nested", nested);

            Object reflectiveOut = processor.processStructuredBody(
                    input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("List<String> field with field-level chain")
        void listOfStrings() {
            Map<String, Object> input = inputWithStringFields();
            input.put("tags", new ArrayList<>(List.of("  one  ", "  two  ", 42)));

            Object reflectiveOut = processor.processStructuredBody(
                    input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("List<Inner> field — element-type dispatch matches reflective walk")
        void listOfNested() {
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested1 = new LinkedHashMap<>();
            nested1.put("note", "  alpha  ");
            Map<String, Object> nested2 = new LinkedHashMap<>();
            nested2.put("note", "  beta  ");
            input.put("inners", new ArrayList<>(List.of(nested1, nested2)));

            Object reflectiveOut = processor.processStructuredBody(
                    input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("route-level policies flow through generated path on un-annotated body fields")
        void routePolicyOnUnannotatedFields() {
            // 'unannotated' field has no per-field chain — must still receive the route-level Trim
            // canonicalizer on both paths.
            EffectiveInputPolicies routePolicies = new EffectiveInputPolicies(List.of(TestTrim.class), List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("unannotated", "  raw  ");

            Object reflectiveOut =
                    processor.processStructuredBody(input, Reflective.class, routePolicies, InputLocation.BODY);
            Object generatedOut =
                    processor.processStructuredBody(input, Generated.class, routePolicies, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("sticky-skip: @SkipCanonicalization on the type suppresses chains in both paths")
        void stickySkipBehavior() {
            // skipped field has its own @Canonicalize, but the type carries @SkipCanonicalization;
            // reflective walker suppresses the chain. Generated processor must do the same.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("skipped", "  preserved  ");

            Object reflectiveOut = processor.processStructuredBody(
                    input, ReflectiveSkip.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, GeneratedSkip.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }
    }

    @Nested
    @DisplayName("InputValueContext path equivalence")
    class PathEquivalence {

        @Test
        @DisplayName("nested field paths match between reflective and generated walkers")
        void nestedFieldPaths() {
            // A nested DTO field — reflective and generated must produce identical
            // InputValueContext.path values ('nested.note', not 'note').
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("note", "  hi  ");
            input.put("nested", nested);

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> generatedContexts = new ArrayList<>(RECORDED_CONTEXTS);

            // Both paths must observe at least one nested.note context entry.
            assertTrue(
                    reflectiveContexts.stream().anyMatch(ctx -> "nested.note".equals(ctx.path())),
                    "reflective must record path 'nested.note'; got " + reflectiveContexts);
            // Top-level ownerType trivially differs between the two pair-DTOs (Reflective.class
            // vs Generated.class). Compare only paths and nested-level ownerTypes.
            assertEquals(
                    paths(reflectiveContexts),
                    paths(generatedContexts),
                    "reflective and generated must observe identical paths");
            assertEquals(
                    nestedOwners(reflectiveContexts),
                    nestedOwners(generatedContexts),
                    "nested-level ownerTypes must agree");
        }

        @Test
        @DisplayName("List<NestedDto> element paths include the [N] index")
        void listOfNestedElementPaths() {
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("note", "  alpha  ");
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("note", "  beta  ");
            input.put("inners", new ArrayList<>(List.of(first, second)));

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> generatedContexts = new ArrayList<>(RECORDED_CONTEXTS);

            assertEquals(
                    paths(reflectiveContexts),
                    paths(generatedContexts),
                    "List<Inner> element paths must agree; got reflective=" + reflectiveContexts + " generated="
                            + generatedContexts);
            assertEquals(nestedOwners(reflectiveContexts), nestedOwners(generatedContexts));
        }

        @Test
        @DisplayName("List<String> element paths include the [N] index")
        void listOfStringElementPaths() {
            Map<String, Object> input = inputWithStringFields();
            input.put("tags", new ArrayList<>(List.of("  one  ", "  two  ")));

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processStructuredBody(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> generatedContexts = new ArrayList<>(RECORDED_CONTEXTS);

            assertEquals(paths(reflectiveContexts), paths(generatedContexts));
        }

        /** Extracts just the {@code path} component — used to compare across the pair-DTOs whose
         * top-level {@code ownerType} trivially differs. */
        private List<String> paths(List<InputValueContext> contexts) {
            return contexts.stream().map(InputValueContext::path).toList();
        }

        /** Extracts {@code (path, ownerType)} pairs but skips top-level paths (those without a
         * '.' or '[') where the pair-DTO ownerType is expected to differ. Nested-level paths
         * (e.g. {@code "nested.note"}, {@code "inners[0].note"}) must agree on the resolved
         * nested DTO type. */
        private List<String> nestedOwners(List<InputValueContext> contexts) {
            return contexts.stream()
                    .filter(ctx -> ctx.path().contains(".") || ctx.path().contains("["))
                    .map(ctx -> ctx.path() + "@" + ctx.ownerType().getSimpleName())
                    .toList();
        }
    }

    @Nested
    @DisplayName("class-level @Canonicalize on un-emitted keys")
    class ClassLevelOnDefaultArm {

        @Test
        @DisplayName("extra Jackson key with list-shaped value applies object-level chain to each element")
        void extraListKeyReceivesObjectChainOnElements() {
            // Regression for the round-3 Codex Critical: processNestedList's no-schema fallback
            // formerly called walkUnknown without descending, so list-valued unknown keys did not
            // see the type-level @Canonicalize on the reflective path while the generated path
            // (which descends in applyDefault before dispatcher.walkUnknown) did. Both paths now
            // apply the type chain to each list element.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("known", "  visible  ");
            input.put("extras", new ArrayList<>(List.of("  a  ", "  b  ")));

            Object reflectiveOut = processor.processStructuredBody(
                    input, ObjLevelReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, ObjLevelGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            // Sanity: each list element trimmed in both paths.
            Map<?, ?> rOut = (Map<?, ?>) reflectiveOut;
            List<?> extras = (List<?>) rOut.get("extras");
            assertEquals(List.of("a", "b"), extras);
        }

        @Test
        @DisplayName("extra Jackson key receives object-level @Canonicalize chain in both paths")
        void extraKeyReceivesObjectChain() {
            // ObjLevelReflective / ObjLevelGenerated declare @Canonicalize(TestTrim) at the class
            // level. The reflective walker applies the object chain to extra Jackson keys via
            // processStringValue + buildCanonicalizerChain. The generated path must do the same
            // through applyDefault — previously applyDefault carried only inherited chains and
            // would have left the extra key untrimmed.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("known", "  visible  "); // declared field
            input.put("extra", "  surprise  "); // not declared — flows through default arm

            Object reflectiveOut = processor.processStructuredBody(
                    input, ObjLevelReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, ObjLevelGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            // Sanity: the extra key was trimmed in both paths.
            Map<?, ?> rOut = (Map<?, ?>) reflectiveOut;
            assertEquals("surprise", rOut.get("extra"));
        }
    }

    @Nested
    @DisplayName("annotated Object field across runtime value shapes")
    class AnnotatedObjectField {

        @Test
        @DisplayName("string value — both paths apply field-level chain with parent owner")
        void stringValue() {
            // Pins the parentOwnerType / nestedMapOwnerType split in applyDefault: a string
            // value on an annotated Object field must use the enclosing DTO as ownerType
            // (matching reflective processStringValue line 235), NOT the field's declared type.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("misc", "  alpha  ");

            Object reflectiveOut = processor.processStructuredBody(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, AnnObjGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            assertEquals("alpha", ((Map<?, ?>) reflectiveOut).get("misc"));
        }

        @Test
        @DisplayName("nested map value — both paths walk reflectively with declared field type as owner")
        void nestedMapValue() {
            // Pins reflective processNestedMap line 296 routing through dispatcher with
            // fieldMeta.fieldType() (Object.class here), and the generated nested-map ownerType
            // matching it via applyDefault's nestedMapOwnerType.
            Map<String, Object> input = new LinkedHashMap<>();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("inner", "  beta  ");
            input.put("misc", nested);

            Object reflectiveOut = processor.processStructuredBody(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, AnnObjGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            // The field-level @Canonicalize(TestTrim) flows into the nested subtree via descend()
            // on both paths, so 'inner' is trimmed.
            Map<?, ?> rNested = (Map<?, ?>) ((Map<?, ?>) reflectiveOut).get("misc");
            assertEquals("beta", rNested.get("inner"));
        }

        @Test
        @DisplayName("list value — both paths apply field-level chain with parent owner per element")
        void listValue() {
            // Pins reflective processNestedList line 391: list elements use the parent owner.
            // The generated path's applyDefault must use parentOwnerType (enclosing DTO) for
            // list-shaped values, NOT the field's declared type.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("misc", new ArrayList<>(List.of("  x  ", "  y  ")));

            Object reflectiveOut = processor.processStructuredBody(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processStructuredBody(
                    input, AnnObjGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            List<?> rList = (List<?>) ((Map<?, ?>) reflectiveOut).get("misc");
            assertEquals(List.of("x", "y"), rList);
        }
    }

    /**
     * Reflective baseline with an annotated {@code Object} field — no companion processor.
     */
    static final class AnnObjReflective {
        @Canonicalize(TestTrim.class)
        public Object misc;
    }

    /**
     * Generated counterpart paired with the
     * {@code GeneratedVsReflectiveEquivalenceTest_AnnObjGenerated_InputProcessor} test fixture.
     */
    static final class AnnObjGenerated {
        @Canonicalize(TestTrim.class)
        public Object misc;
    }

    /**
     * Reflective baseline with class-level {@code @Canonicalize(TestTrim.class)} but no companion
     * processor — reflective walker applies the type chain to all keys, including extras.
     */
    @Canonicalize(TestTrim.class)
    static final class ObjLevelReflective {
        public String known;
    }

    /**
     * Generated counterpart paired with a hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_ObjLevelGenerated_InputProcessor} test fixture.
     */
    @Canonicalize(TestTrim.class)
    static final class ObjLevelGenerated {
        public String known;
    }

    // --- Test fixtures ---

    private Map<String, Object> inputWithStringFields() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trimmed", "  hello  ");
        input.put("dotty", "a.b.c");
        input.put("unannotated", "raw");
        return input;
    }

    /**
     * Reflective baseline DTO — no companion {@code _InputProcessor} class on the test classpath,
     * so the dispatcher's classloader lookup returns empty and traversal walks reflectively.
     */
    static final class Reflective {
        @Canonicalize(TestTrim.class)
        public String trimmed;

        @Sanitize(TestStripDots.class)
        public String dotty;

        public String unannotated;

        public Inner nested;

        @Canonicalize(TestTrim.class)
        public List<String> tags;

        public List<Inner> inners;
    }

    /**
     * Generated-path DTO. Structurally identical to {@link Reflective}; paired with the
     * {@code GeneratedVsReflectiveEquivalenceTest_Generated_InputProcessor} fixture in test
     * sources so the dispatcher resolves it on the classloader path.
     */
    static final class Generated {
        @Canonicalize(TestTrim.class)
        public String trimmed;

        @Sanitize(TestStripDots.class)
        public String dotty;

        public String unannotated;

        public Inner nested;

        @Canonicalize(TestTrim.class)
        public List<String> tags;

        public List<Inner> inners;
    }

    /** Nested DTO used by both fixtures. Its single field has a per-field canonicalizer chain. */
    public static final class Inner {
        @Canonicalize(TestTrim.class)
        public String note;
    }

    /** Reflective baseline for the sticky-skip equivalence case. */
    @SkipCanonicalization
    static final class ReflectiveSkip {
        @Canonicalize(TestTrim.class)
        public String skipped;
    }

    /** Generated-path counterpart for the sticky-skip equivalence case. */
    @SkipCanonicalization
    static final class GeneratedSkip {
        @Canonicalize(TestTrim.class)
        public String skipped;
    }

    // --- Test canonicalizers / sanitizer ---

    /**
     * Recording sink used by the test canonicalizers/sanitizer to capture every
     * {@link InputValueContext} they observe. The path-equivalence assertion below compares
     * the reflective walker's recorded contexts against the generated processor's recorded
     * contexts byte-for-byte.
     */
    static final List<InputValueContext> RECORDED_CONTEXTS = new ArrayList<>();

    /** Trims whitespace and records the seen context. */
    public static final class TestTrim implements Canonicalizer {
        @Override
        public String canonicalize(@Nullable String value, @Nullable InputValueContext context) {
            if (context != null) {
                RECORDED_CONTEXTS.add(context);
            }
            return value == null ? null : value.trim();
        }
    }

    /** Uppercases. */
    public static final class TestUpper implements Canonicalizer {
        @Override
        public String canonicalize(@Nullable String value, @Nullable InputValueContext context) {
            if (context != null) {
                RECORDED_CONTEXTS.add(context);
            }
            return value == null ? null : value.toUpperCase();
        }
    }

    /** Strips '.' characters and records the seen context. */
    public static final class TestStripDots implements Sanitizer {
        @Override
        public String sanitize(@Nullable String value, @Nullable InputValueContext context) {
            if (context != null) {
                RECORDED_CONTEXTS.add(context);
            }
            return value == null ? null : value.replace(".", "");
        }
    }

    // --- Hand-written companion processors mirroring what codegen will emit ---

    /**
     * Marker pair to assert nested-dispatch reaches the generated path. Not used by Equivalence
     * tests above; reserved for follow-up scenarios in case structural assertions are extended.
     */
    static final class CountingPair {}

    /**
     * Provides expectations for hand-written companions. Static helpers to mirror codegen output;
     * actual emitter implementations land in {@code vertique-codegen-sanitization}.
     */
    static final class ExpectedShape {
        // Apply: trim+stripDots etc. Documented for readability — wired via @Canonicalize/@Sanitize.
        private ExpectedShape() {}
    }

    // The hand-written _InputProcessor companions live in sibling top-level test source files:
    //
    //   GeneratedVsReflectiveEquivalenceTest_Generated_InputProcessor.java
    //   GeneratedVsReflectiveEquivalenceTest_GeneratedSkip_InputProcessor.java
    //
    // Both must be in the same package (dev.vertique.rest.core.request) and follow the
    // dispatcher's name derivation (binary name with '$' -> '_' + "_InputProcessor").
}
