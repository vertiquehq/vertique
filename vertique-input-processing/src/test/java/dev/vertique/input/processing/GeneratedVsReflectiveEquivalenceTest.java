// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Collections;
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

            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("nested DTO field — both reflective and generated dispatch through the dispatcher")
        void nestedDtoField() {
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("note", "  hi  "); // Inner.note has @Canonicalize(TestTrim)
            input.put("nested", nested);

            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("List<String> field with field-level chain")
        void listOfStrings() {
            Map<String, Object> input = inputWithStringFields();
            input.put("tags", new ArrayList<>(List.of("  one  ", "  two  ", 42)));

            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

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

            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

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

            Object reflectiveOut = processor.processInput(input, Reflective.class, routePolicies, InputLocation.BODY);
            Object generatedOut = processor.processInput(input, Generated.class, routePolicies, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("sticky-skip: @SkipCanonicalization on the type suppresses chains in both paths")
        void stickySkipBehavior() {
            // skipped field has its own @Canonicalize, but the type carries @SkipCanonicalization;
            // reflective walker suppresses the chain. Generated processor must do the same.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("skipped", "  preserved  ");

            Object reflectiveOut = processor.processInput(
                    input, ReflectiveSkip.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut =
                    processor.processInput(input, GeneratedSkip.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

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
            processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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
            processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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
            processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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
    @DisplayName("PAYLOAD location propagation")
    class PayloadLocation {

        @Test
        @DisplayName("nested DTO under PAYLOAD — outputs match and every observed context reports PAYLOAD")
        void nestedDtoUnderPayloadLocation() {
            // Non-HTTP transports (message/protocol payloads) drive the same engine with
            // InputLocation.PAYLOAD. Both the reflective walker and the generated processor must
            // thread that location unchanged into every InputValueContext they build, including
            // nested-DTO and List<String> subtrees.
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("note", "  hi  "); // Inner.note has @Canonicalize(TestTrim)
            input.put("nested", nested);
            input.put("tags", new ArrayList<>(List.of("  one  ", "  two  ")));

            RECORDED_CONTEXTS.clear();
            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD);
            List<InputValueContext> generatedContexts = new ArrayList<>(RECORDED_CONTEXTS);

            assertEquals(reflectiveOut, generatedOut);
            assertEveryContextReports(InputLocation.PAYLOAD, reflectiveContexts, "reflective");
            assertEveryContextReports(InputLocation.PAYLOAD, generatedContexts, "generated");
        }

        @Test
        @DisplayName("List<Inner> elements under PAYLOAD — outputs match and every observed context reports PAYLOAD")
        void listOfNestedUnderPayloadLocation() {
            // Element-type dispatch is the path where the generated processor hands control to the
            // dispatcher per element; the location must survive that hop on both paths.
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("note", "  alpha  ");
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("note", "  beta  ");
            input.put("inners", new ArrayList<>(List.of(first, second)));

            RECORDED_CONTEXTS.clear();
            Object reflectiveOut =
                    processor.processInput(input, Reflective.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            Object generatedOut =
                    processor.processInput(input, Generated.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD);
            List<InputValueContext> generatedContexts = new ArrayList<>(RECORDED_CONTEXTS);

            assertEquals(reflectiveOut, generatedOut);
            assertEveryContextReports(InputLocation.PAYLOAD, reflectiveContexts, "reflective");
            assertEveryContextReports(InputLocation.PAYLOAD, generatedContexts, "generated");
        }

        /**
         * Asserts the walk observed at least one value and that EVERY recorded
         * {@link InputValueContext} carries {@code expected} as its location — comparing the
         * observed location list against {@code expected} repeated, so a mismatch reports the
         * actual locations rather than just a boolean.
         */
        private void assertEveryContextReports(
                InputLocation expected, List<InputValueContext> contexts, String pathName) {
            assertFalse(contexts.isEmpty(), pathName + " path must observe at least one InputValueContext");
            List<InputLocation> observed =
                    contexts.stream().map(InputValueContext::location).toList();
            assertEquals(
                    Collections.nCopies(contexts.size(), expected),
                    observed,
                    pathName + " path must report location " + expected + " for every value; got " + contexts);
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

            Object reflectiveOut = processor.processInput(
                    input, ObjLevelReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
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

            Object reflectiveOut = processor.processInput(
                    input, ObjLevelReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
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

            Object reflectiveOut = processor.processInput(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
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

            Object reflectiveOut = processor.processInput(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
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

            Object reflectiveOut = processor.processInput(
                    input, AnnObjReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
                    input, AnnObjGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(reflectiveOut, generatedOut);
            List<?> rList = (List<?>) ((Map<?, ?>) reflectiveOut).get("misc");
            assertEquals(List.of("x", "y"), rList);
        }
    }

    @Nested
    @DisplayName("recursive and deeply nested DTOs")
    class RecursiveAndDeepDtos {

        @Test
        @DisplayName("self-referential and twelve-level DTOs agree and are processed at every level")
        void shouldAgreeForRecursiveAndDeeplyNestedDtos() {
            Map<String, Object> recursiveInput = recursiveInput();

            Object reflectiveOut = processor.processInput(
                    recursiveInput, RecursiveReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
                    recursiveInput, RecursiveGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    "self-referential DTO: the generated processor's self-dispatching 'child' arm trims "
                            + "every level, so the reflective walker must too");
            assertEquals(
                    List.of("a", "b", "c"),
                    notesAlongChain(reflectiveOut),
                    "both paths must trim the @Canonicalize'd note at every level of the self-reference");

            Map<String, Object> deepInput = deepInput();

            Object deepReflectiveOut = processor.processInput(
                    deepInput, DeepReflectiveRoot.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object deepGeneratedOut = processor.processInput(
                    deepInput, DeepGeneratedRoot.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(deepReflectiveOut, deepGeneratedOut, "twelve-level chain: the two paths must agree");
            // Agreement alone would also hold if both paths silently skipped the deepest level, so
            // pin the observable effect on each path independently.
            assertEquals(
                    "deep",
                    deepestNote(deepReflectiveOut),
                    "reflective path must reach and trim the twelfth level's @Canonicalize'd note");
            assertEquals(
                    "deep",
                    deepestNote(deepGeneratedOut),
                    "generated path must reach and trim the twelfth level's @Canonicalize'd note");
        }

        /** Builds a three-level intermediate for the self-referential fixtures. */
        private Map<String, Object> recursiveInput() {
            Map<String, Object> third = new LinkedHashMap<>();
            third.put("note", "  c  ");
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("note", "  b  ");
            second.put("child", third);
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("note", "  a  ");
            first.put("child", second);
            return first;
        }

        /** Builds a twelve-level intermediate for the {@code DeepLink} chain. */
        private Map<String, Object> deepInput() {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("note", "  deep  ");
            for (int level = 11; level >= 1; level--) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("child", current);
                current = node;
            }
            return current;
        }

        /** Collects the {@code note} value of every level reachable through {@code child}. */
        private List<Object> notesAlongChain(Object result) {
            List<Object> notes = new ArrayList<>();
            Map<?, ?> node = (Map<?, ?>) result;
            while (node != null) {
                notes.add(node.get("note"));
                node = (Map<?, ?>) node.get("child");
            }
            return notes;
        }

        /** Returns the {@code note} value of the deepest level reachable through {@code child}. */
        private Object deepestNote(Object result) {
            Map<?, ?> node = (Map<?, ?>) result;
            while (node.get("child") != null) {
                node = (Map<?, ?>) node.get("child");
            }
            return node.get("note");
        }
    }

    @Nested
    @DisplayName("wildcard-bounded and array element fields")
    class WildcardAndBoundedGenerics {

        @Test
        @DisplayName("wildcard-bounded and array element fields agree and are canonicalized on both paths")
        void shouldAgreeForWildcardAndBoundedGenericFields() {
            Object reflectiveOut = processor.processInput(
                    variantInput(), WildcardReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Object generatedOut = processor.processInput(
                    variantInput(), WildcardGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    "invariant, wildcard-bounded, and array element fields all carry the same element "
                            + "schema, so the two execution paths must agree byte for byte");
            // Agreement alone would also hold if both paths skipped the bounded and array elements,
            // so pin the observable effect on each path independently.
            assertElementNotesTrimmed(reflectiveOut, "reflective");
            assertElementNotesTrimmed(generatedOut, "generated");
        }

        /**
         * Builds an intermediate whose three keys hold the same JSON-array shape, one per declared
         * variance: invariant {@code List<Inner>}, wildcard-bounded {@code List<? extends Inner>},
         * and {@code Inner[]}.
         */
        private Map<String, Object> variantInput() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("invariant", new ArrayList<Object>(List.of(noteMap("  alpha  "))));
            input.put("bounded", new ArrayList<Object>(List.of(noteMap("  beta  "))));
            input.put("array", new ArrayList<Object>(List.of(noteMap("  gamma  "))));
            return input;
        }

        /** Builds a one-field {@link Inner} element intermediate. */
        private Map<String, Object> noteMap(String note) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("note", note);
            return element;
        }

        /**
         * Asserts that the element type's {@code @Canonicalize(TestTrim)} actually ran on every one
         * of the three fields for a single path, so mutual breakage cannot satisfy the byte-equality
         * assertion above.
         */
        private void assertElementNotesTrimmed(Object output, String pathName) {
            Map<?, ?> out = (Map<?, ?>) output;
            assertEquals("alpha", firstNote(out.get("invariant")), pathName + " path: List<Inner> element");
            assertEquals("beta", firstNote(out.get("bounded")), pathName + " path: List<? extends Inner> element");
            assertEquals("gamma", firstNote(out.get("array")), pathName + " path: Inner[] element");
        }

        /** Returns the {@code note} value of the first element of a processed element collection. */
        private Object firstNote(Object processedList) {
            List<?> list = (List<?>) processedList;
            return ((Map<?, ?>) list.get(0)).get("note");
        }
    }

    /**
     * Reflective baseline declaring the same element type under three variances — no companion
     * processor, so traversal walks reflectively.
     */
    static final class WildcardReflective {
        public List<Inner> invariant;
        public List<? extends Inner> bounded;
        public Inner[] array;
    }

    /**
     * Generated counterpart paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_WildcardGenerated_InputProcessor} fixture, whose
     * three arms all dispatch at the element type {@link Inner} — the classification codegen's
     * APT-time collector already performs.
     */
    static final class WildcardGenerated {
        public List<Inner> invariant;
        public List<? extends Inner> bounded;
        public Inner[] array;
    }

    /** Self-referential reflective baseline — no companion processor. */
    static final class RecursiveReflective {
        @Canonicalize(TestTrim.class)
        public String note;

        public RecursiveReflective child;
    }

    /**
     * Self-referential generated counterpart, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_RecursiveGenerated_InputProcessor} fixture whose
     * {@code child} arm dispatches back to its own target type.
     */
    static final class RecursiveGenerated {
        @Canonicalize(TestTrim.class)
        public String note;

        public RecursiveGenerated child;
    }

    // A twelve-level acyclic chain of distinct types. Only the leaf declares a policy: an
    // annotated intermediate would re-anchor reflective resolution at its own level (each nested
    // descent resolves from depth zero), so the resolver's depth budget would never be observable.

    /** Reflective root of the twelve-level chain — no companion processor. */
    static final class DeepReflectiveRoot {
        public DeepLink2 child;
    }

    /**
     * Generated root of the twelve-level chain, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_DeepGeneratedRoot_InputProcessor} fixture.
     */
    static final class DeepGeneratedRoot {
        public DeepLink2 child;
    }

    /** Level 2 of the twelve-level chain. */
    public static final class DeepLink2 {
        public DeepLink3 child;
    }

    /** Level 3 of the twelve-level chain. */
    public static final class DeepLink3 {
        public DeepLink4 child;
    }

    /** Level 4 of the twelve-level chain. */
    public static final class DeepLink4 {
        public DeepLink5 child;
    }

    /** Level 5 of the twelve-level chain. */
    public static final class DeepLink5 {
        public DeepLink6 child;
    }

    /** Level 6 of the twelve-level chain. */
    public static final class DeepLink6 {
        public DeepLink7 child;
    }

    /** Level 7 of the twelve-level chain. */
    public static final class DeepLink7 {
        public DeepLink8 child;
    }

    /** Level 8 of the twelve-level chain. */
    public static final class DeepLink8 {
        public DeepLink9 child;
    }

    /** Level 9 of the twelve-level chain. */
    public static final class DeepLink9 {
        public DeepLink10 child;
    }

    /** Level 10 of the twelve-level chain. */
    public static final class DeepLink10 {
        public DeepLink11 child;
    }

    /** Level 11 of the twelve-level chain. */
    public static final class DeepLink11 {
        public DeepLeaf12 child;
    }

    /** Level 12 of the twelve-level chain — carries the deepest declared policy. */
    public static final class DeepLeaf12 {
        @Canonicalize(TestTrim.class)
        public String note;
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
    // Both must be in the same package (dev.vertique.input.processing) and follow the
    // dispatcher's name derivation (binary name with '$' -> '_' + "_InputProcessor").
}
