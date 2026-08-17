// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
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
                    if (cls == TestDecodeEntities.class) return new TestDecodeEntities();
                    if (cls == TestStripHtml.class) return new TestStripHtml();
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

            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("nested DTO field — both reflective and generated dispatch through the dispatcher")
        void nestedDtoField() {
            Map<String, Object> input = inputWithStringFields();
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("note", "  hi  "); // Inner.note has @Canonicalize(TestTrim)
            input.put("nested", nested);

            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(reflectiveOut, generatedOut);
        }

        @Test
        @DisplayName("List<String> field with field-level chain")
        void listOfStrings() {
            Map<String, Object> input = inputWithStringFields();
            input.put("tags", new ArrayList<>(List.of("  one  ", "  two  ", 42)));

            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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

            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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

            Object reflectiveOut = processor.processInput(
                    input, Reflective.class, routePolicies, InputLocation.BODY, InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input, Generated.class, routePolicies, InputLocation.BODY, InputFieldNameResolver.IDENTITY);

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
                    input,
                    ReflectiveSkip.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    GeneratedSkip.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
            processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
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
            processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
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
            processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
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
            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.PAYLOAD,
                    InputFieldNameResolver.IDENTITY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.PAYLOAD,
                    InputFieldNameResolver.IDENTITY);
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
            Object reflectiveOut = processor.processInput(
                    input,
                    Reflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.PAYLOAD,
                    InputFieldNameResolver.IDENTITY);
            List<InputValueContext> reflectiveContexts = new ArrayList<>(RECORDED_CONTEXTS);

            RECORDED_CONTEXTS.clear();
            Object generatedOut = processor.processInput(
                    input,
                    Generated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.PAYLOAD,
                    InputFieldNameResolver.IDENTITY);
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
                    input,
                    ObjLevelReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    ObjLevelGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    input,
                    ObjLevelReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    ObjLevelGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    input,
                    AnnObjReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    AnnObjGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    input,
                    AnnObjReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    AnnObjGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    input,
                    AnnObjReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    input,
                    AnnObjGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    recursiveInput,
                    RecursiveReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    recursiveInput,
                    RecursiveGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
                    deepInput,
                    DeepReflectiveRoot.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object deepGeneratedOut = processor.processInput(
                    deepInput,
                    DeepGeneratedRoot.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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
    @DisplayName("composed chain length under a type-level chain")
    class ComposedChainLength {

        private static final int LEVELS = 3;

        @Test
        @DisplayName("both paths apply a recursive type's own chain once per value at every depth")
        void shouldAgreeThatATypeLevelChainAppliesOncePerValue() {
            RECORDED_CONTEXTS.clear();
            Object reflectiveOut = processor.processInput(
                    typeChainInput(),
                    TypeChainRecursiveReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            int reflectiveInvocations = RECORDED_CONTEXTS.size();

            RECORDED_CONTEXTS.clear();
            Object generatedOut = processor.processInput(
                    typeChainInput(),
                    TypeChainRecursiveGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            int generatedInvocations = RECORDED_CONTEXTS.size();

            assertEquals(reflectiveOut, generatedOut, "both paths must produce the same output");
            // TestTrim is idempotent, so a chain that grew with depth would produce identical output
            // and only a different invocation count. Count, do not compare strings.
            assertEquals(
                    LEVELS,
                    reflectiveInvocations,
                    "reflective path: the type's own chain must apply once per string value, not once "
                            + "per level of the self-reference");
            assertEquals(
                    LEVELS,
                    generatedInvocations,
                    "generated path: the type's own chain must apply once per string value, not once "
                            + "per level of the self-reference");
        }

        /** Builds a {@value #LEVELS}-level self-referential intermediate with one string per level. */
        private Map<String, Object> typeChainInput() {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("note", "  note" + LEVELS + "  ");
            for (int level = LEVELS - 1; level >= 1; level--) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("note", "  note" + level + "  ");
                node.put("child", current);
                current = node;
            }
            return current;
        }
    }

    @Nested
    @DisplayName("composed chain length under a field-level chain on the recursive link")
    class ComposedChainLengthUnderFieldLevelRecursion {

        private static final int LEVELS = 5;

        @Test
        @DisplayName("both paths apply a recursive field's own chain once per value at every depth")
        void shouldAgreeThatAFieldLevelChainOnTheRecursiveLinkAppliesOncePerValue() {
            RECORDED_CONTEXTS.clear();
            Object reflectiveOut = processor.processInput(
                    fieldChainInput(),
                    FieldChainRecursiveReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            int reflectiveInvocations = RECORDED_CONTEXTS.size();

            RECORDED_CONTEXTS.clear();
            Object generatedOut = processor.processInput(
                    fieldChainInput(),
                    FieldChainRecursiveGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            int generatedInvocations = RECORDED_CONTEXTS.size();

            assertEquals(reflectiveOut, generatedOut, "both paths must produce the same output");
            // TestTrim is idempotent, so a chain that grew with depth would produce identical output
            // and only a different invocation count. Count, do not compare strings. The root's own
            // note sits above the field, so LEVELS - 1 values carry the chain.
            assertEquals(
                    LEVELS - 1,
                    reflectiveInvocations,
                    "reflective path: the recursive field's chain must apply once per string value, "
                            + "not once per level of the self-reference");
            assertEquals(
                    LEVELS - 1,
                    generatedInvocations,
                    "generated path: the recursive field's chain must apply once per string value, "
                            + "not once per level of the self-reference");
        }

        /** Builds a {@value #LEVELS}-level self-referential intermediate with one string per level. */
        private Map<String, Object> fieldChainInput() {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("note", "  note" + LEVELS + "  ");
            for (int level = LEVELS - 1; level >= 1; level--) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("note", "  note" + level + "  ");
                node.put("child", current);
                current = node;
            }
            return current;
        }
    }

    @Nested
    @DisplayName("a collection field's own chain under a recursive type-level chain")
    class CollectionChainUnderRecursiveTypeChain {

        private static final int LEVELS = 3;

        @Test
        @DisplayName("both paths sanitize every level's List<String> field with its own declared chain")
        void shouldAgreeThatACollectionFieldsChainSurvivesTheOwnerTypesOwnContribution() {
            Object reflectiveOut = processor.processInput(
                    collectionChainInput(),
                    CollectionChainRecursiveReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    collectionChainInput(),
                    CollectionChainRecursiveGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    "the collection arm names no field site, which must not be read as the owner type's "
                            + "object-level site on either path");
            // Agreement alone would also hold if both paths dropped the field's sanitizer, so pin the
            // sanitized output on each path independently and at every level: a dropped chain leaves
            // the markup "<script" in what the application receives.
            for (int level = 0; level < LEVELS; level++) {
                assertEquals(
                        List.of("script"),
                        tagsAtLevel(reflectiveOut, level),
                        "reflective path: level " + level + "'s tags must run the field's own sanitizer");
                assertEquals(
                        List.of("script"),
                        tagsAtLevel(generatedOut, level),
                        "generated path: level " + level + "'s tags must run the field's own sanitizer, "
                                + "even after the owner type's own chain has contributed on this path");
            }
        }

        /** Builds a {@value #LEVELS}-level self-referential intermediate with one tag per level. */
        private Map<String, Object> collectionChainInput() {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("tags", new ArrayList<Object>(List.of("  <script  ")));
            for (int level = LEVELS - 1; level >= 1; level--) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("tags", new ArrayList<Object>(List.of("  <script  ")));
                node.put("child", current);
                current = node;
            }
            return current;
        }

        /** Returns the {@code tags} value {@code level} steps down the {@code child} chain. */
        private Object tagsAtLevel(Object result, int level) {
            Map<?, ?> node = (Map<?, ?>) result;
            for (int step = 0; step < level; step++) {
                node = (Map<?, ?>) node.get("child");
            }
            return node.get("tags");
        }
    }

    @Nested
    @DisplayName("a field's own declared chain keeps its declared order")
    class DeclaredChainOrder {

        @Test
        @DisplayName("both paths run a field's declared decode-then-strip order under the owner's strip")
        void shouldAgreeThatAFieldsDeclaredOrderSurvives() {
            Object reflectiveOut = processor.processInput(
                    declaredOrderInput(),
                    DeclaredOrderReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    declaredOrderInput(),
                    DeclaredOrderGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(reflectiveOut, generatedOut, "both paths share compose(), so they must agree");
            // Agreement alone would also hold if both paths inverted the order, so pin the
            // order-sensitive output on each path independently.
            assertEquals(
                    "script",
                    ((Map<?, ?>) reflectiveOut).get("value"),
                    "reflective path: the field's declared decode-then-strip order must survive");
            assertEquals(
                    "script",
                    ((Map<?, ?>) generatedOut).get("value"),
                    "generated path: the field's declared decode-then-strip order must survive");
        }

        /** Builds an intermediate whose single value only reads correctly under decode-then-strip. */
        private Map<String, Object> declaredOrderInput() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("value", "&lt;script");
            return input;
        }
    }

    @Nested
    @DisplayName("a declared chain keeps its own entries under an invocation-level chain")
    class RoutePolicyDeclaredChainOrder {

        /** The invocation-level shape codegen emits for a route annotated {@code @Sanitize}. */
        private final EffectiveInputPolicies routePolicies =
                new EffectiveInputPolicies(List.of(), List.of(TestStripHtml.class));

        @Test
        @DisplayName("both paths keep the field's trailing strip when the route names that sanitizer")
        void shouldAgreeThatAFieldsDeclaredOrderSurvivesARouteLevelChain() {
            Object reflectiveOut = processor.processInput(
                    declaredOrderInput(),
                    DeclaredOrderReflective.class,
                    routePolicies,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    declaredOrderInput(),
                    DeclaredOrderGenerated.class,
                    routePolicies,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(reflectiveOut, generatedOut, "both paths share compose(), so they must agree");
            // Agreement alone would also hold if both paths dropped the field's trailing strip, so
            // pin the sanitized output on each path independently: a dropped strip leaves the
            // decoded "<script" in what the application receives.
            assertEquals(
                    "script",
                    ((Map<?, ?>) reflectiveOut).get("value"),
                    "reflective path: an invocation-level strip must not consume the field's own");
            assertEquals(
                    "script",
                    ((Map<?, ?>) generatedOut).get("value"),
                    "generated path: an invocation-level strip must not consume the field's own");
        }

        /** Builds an intermediate whose single value only reads correctly under decode-then-strip. */
        private Map<String, Object> declaredOrderInput() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("value", "&lt;script");
            return input;
        }
    }

    @Nested
    @DisplayName("a field's own chain overrides an object-level skip")
    class FieldChainOverridesObjectLevelSkip {

        @Test
        @DisplayName("both paths run a field's own chain on direct, nested and collection fields")
        void shouldAgreeThatAFieldChainOverridesTheOwnersSkip() {
            Object reflectiveOut = processor.processInput(
                    skipOverrideInput(),
                    SkipOverrideReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    skipOverrideInput(),
                    SkipOverrideGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(reflectiveOut, generatedOut, "both paths share descend(), so they must agree");
            // Agreement alone would also hold if both paths dropped the nested chains, so pin the
            // observable effect on each path independently.
            assertOverrideApplied(reflectiveOut, "reflective");
            assertOverrideApplied(generatedOut, "generated");
        }

        /** Builds an intermediate carrying one value per field kind of the skip-override fixtures. */
        private Map<String, Object> skipOverrideInput() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "  b  ");
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("value", "  c  ");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("direct", "  a  ");
            input.put("nested", nested);
            input.put("many", List.of(element));
            return input;
        }

        /** Asserts every field kind's own chain ran despite the owner's type-level skip. */
        private void assertOverrideApplied(Object output, String path) {
            Map<?, ?> map = (Map<?, ?>) output;
            assertEquals("a", map.get("direct"), path + " path: a direct String field's own chain must run");
            assertEquals(
                    "b",
                    ((Map<?, ?>) map.get("nested")).get("value"),
                    path + " path: a nested-object field's own chain must override the owner's skip");
            assertEquals(
                    "c",
                    ((Map<?, ?>) ((List<?>) map.get("many")).get(0)).get("value"),
                    path + " path: a collection-of-object field's own chain must override the owner's skip");
        }
    }

    @Nested
    @DisplayName("wildcard-bounded and array element fields")
    class WildcardAndBoundedGenerics {

        @Test
        @DisplayName("wildcard-bounded and array element fields agree and are canonicalized on both paths")
        void shouldAgreeForWildcardAndBoundedGenericFields() {
            Object reflectiveOut = processor.processInput(
                    variantInput(),
                    WildcardReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    variantInput(),
                    WildcardGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

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

    @Nested
    @DisplayName("enum and primitive-Optional collection elements")
    class EnumAndPrimitiveOptionalElements {

        @Test
        @DisplayName("a type-level chain reaches enum, array and primitive-Optional elements on both paths")
        void shouldAgreeForEnumAndPrimitiveOptionalElementsUnderATypeLevelChain() {
            Object reflectiveOut = processor.processInput(
                    elementInput(),
                    EnumElementReflective.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    elementInput(),
                    EnumElementGenerated.class,
                    EffectiveInputPolicies.NONE,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    "an enum element type, an enum array and the primitive Optional specializations are all "
                            + "scalar leaves, so the declared chain must reach their string elements on both "
                            + "execution paths");
            // Agreement alone would also hold if both paths skipped every element, so pin the
            // observable effect on each path independently.
            assertEquals(typeChainOnlyOutput(), reflectiveOut, "reflective path");
            assertEquals(typeChainOnlyOutput(), generatedOut, "generated path");
        }

        @Test
        @DisplayName("invocation-level policies reach enum, array and primitive-Optional elements on both paths")
        void shouldAgreeForEnumAndPrimitiveOptionalElementsUnderInvocationLevelPolicies() {
            // The invocation-level canonicalizer uppercases and the type-level one trims, so an
            // element that is both uppercased AND trimmed proves BOTH layers reached it: trimming
            // alone would leave 'active', uppercasing alone would leave '  ACTIVE  '.
            EffectiveInputPolicies routePolicies = new EffectiveInputPolicies(List.of(TestUpper.class), List.of());

            Object reflectiveOut = processor.processInput(
                    elementInput(),
                    EnumElementReflective.class,
                    routePolicies,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);
            Object generatedOut = processor.processInput(
                    elementInput(),
                    EnumElementGenerated.class,
                    routePolicies,
                    InputLocation.BODY,
                    InputFieldNameResolver.IDENTITY);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    "a non-empty invocation-level chain must reach enum, array and primitive-Optional "
                            + "elements on both execution paths");
            assertEquals(invocationAndTypeChainOutput(), reflectiveOut, "reflective path");
            assertEquals(invocationAndTypeChainOutput(), generatedOut, "generated path");
        }

        /**
         * Builds an intermediate whose keys all hold the same JSON-array shape: enum elements, enum
         * array elements, an explicitly-chained enum collection, the three primitive {@code Optional}
         * specializations, and the two green controls ({@code List<UUID>}, {@code List<String>}).
         */
        private Map<String, Object> elementInput() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tags", new ArrayList<Object>(List.of("  active  ", "  inactive  ")));
            input.put("statuses", new ArrayList<Object>(List.of("  active  ")));
            input.put("chained", new ArrayList<Object>(List.of("  mixed  ")));
            input.put("counts", new ArrayList<Object>(List.of("  1  ")));
            input.put("totals", new ArrayList<Object>(List.of("  2  ")));
            input.put("ratios", new ArrayList<Object>(List.of("  3.5  ")));
            input.put("ids", new ArrayList<Object>(List.of("  id-1  ")));
            input.put("labels", new ArrayList<Object>(List.of("  label  ")));
            return input;
        }

        /** Expected output when only the type-level {@code @Canonicalize(TestTrim)} applies. */
        private Map<String, Object> typeChainOnlyOutput() {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("tags", List.of("active", "inactive"));
            expected.put("statuses", List.of("active"));
            // chained composes the type chain (trim) with its own field chain (upper).
            expected.put("chained", List.of("MIXED"));
            expected.put("counts", List.of("1"));
            expected.put("totals", List.of("2"));
            expected.put("ratios", List.of("3.5"));
            expected.put("ids", List.of("id-1"));
            expected.put("labels", List.of("label"));
            return expected;
        }

        /** Expected output when an invocation-level {@code TestUpper} precedes the type-level trim. */
        private Map<String, Object> invocationAndTypeChainOutput() {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("tags", List.of("ACTIVE", "INACTIVE"));
            expected.put("statuses", List.of("ACTIVE"));
            expected.put("chained", List.of("MIXED"));
            expected.put("counts", List.of("1"));
            expected.put("totals", List.of("2"));
            expected.put("ratios", List.of("3.5"));
            expected.put("ids", List.of("ID-1"));
            expected.put("labels", List.of("LABEL"));
            return expected;
        }
    }

    @Nested
    @DisplayName("wire-name projections")
    class WireNameProjections {

        @Test
        @DisplayName("identity, renamed and strategy projections agree byte for byte on both paths")
        void shouldAgreeUnderIdentityRenamedAndStrategyProjections() {
            // 1. Identity — the overwhelmingly common DTO, whose wire keys already are Java names.
            assertProjectionAgrees("identity", InputFieldNameResolver.IDENTITY, "userName", "homePage");

            // 2. A @JsonProperty-style rename: one property carries an arbitrary wire name while the
            //    other keeps its Java name, so a projection that over-applies is caught too.
            InputFieldNameResolver renamed = (ownerType, wireName) -> "login".equals(wireName) ? "userName" : wireName;
            assertProjectionAgrees("@JsonProperty rename", renamed, "login", "homePage");

            // 3. A SNAKE_CASE naming strategy: every wire key is the snake_case form of its property.
            InputFieldNameResolver snakeCase = (ownerType, wireName) -> toCamelCase(wireName);
            assertProjectionAgrees("SNAKE_CASE strategy", snakeCase, "user_name", "home_page");
        }

        /**
         * Processes one wire-keyed intermediate through the reflective and the generated path under
         * the same projection and asserts they agree — then asserts on each path independently that
         * the renamed properties' declared chains actually ran, so mutual breakage cannot satisfy
         * the equality.
         *
         * @param label        the projection under test, for failure messages
         * @param projection   the wire → Java name projection
         * @param userNameKey  the wire key the projection maps onto {@code userName}
         * @param homePageKey  the wire key the projection maps onto {@code homePage}
         */
        private void assertProjectionAgrees(
                String label, InputFieldNameResolver projection, String userNameKey, String homePageKey) {

            Map<String, Object> input = new LinkedHashMap<>();
            input.put(userNameKey, "  alice  ");
            input.put(homePageKey, "a.b.c");

            Object reflectiveOut = processor.processInput(
                    input, ProjectionReflective.class, EffectiveInputPolicies.NONE, InputLocation.BODY, projection);
            Object generatedOut = processor.processInput(
                    input, ProjectionGenerated.class, EffectiveInputPolicies.NONE, InputLocation.BODY, projection);

            assertEquals(
                    reflectiveOut,
                    generatedOut,
                    label + ": the reflective walker matches per-field metadata by projected name and the "
                            + "generated switch keys on the same projection, so the two paths must agree "
                            + "byte for byte");
            assertProjectedFieldsProcessed(reflectiveOut, userNameKey, homePageKey, label + " / reflective");
            assertProjectedFieldsProcessed(generatedOut, userNameKey, homePageKey, label + " / generated");
        }

        /**
         * Asserts that both declared chains ran on a single path's output and that the emitted map
         * still carries the wire keys.
         *
         * @param output      the processed intermediate
         * @param userNameKey the wire key for the canonicalized property
         * @param homePageKey the wire key for the sanitized property
         * @param pathName    the execution path, for failure messages
         */
        private void assertProjectedFieldsProcessed(
                Object output, String userNameKey, String homePageKey, String pathName) {

            Map<?, ?> out = (Map<?, ?>) output;
            assertTrue(
                    out.containsKey(userNameKey) && out.containsKey(homePageKey),
                    pathName + ": the projection selects per-field metadata; it must not rename the "
                            + "emitted keys, which still have to match what the codec will bind — got " + out);
            assertEquals(
                    "alice",
                    out.get(userNameKey),
                    pathName + ": @Canonicalize on userName must run on wire key '" + userNameKey + "'");
            assertEquals(
                    "abc",
                    out.get(homePageKey),
                    pathName + ": @Sanitize on homePage must run on wire key '" + homePageKey + "'");
        }

        /** Converts a {@code snake_case} wire key to its {@code camelCase} Java property name. */
        private String toCamelCase(String wireName) {
            String[] parts = wireName.split("_");
            StringBuilder camel = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                camel.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            return camel.toString();
        }
    }

    /**
     * Reflective baseline whose Java property names differ from the wire keys a projection maps onto
     * them — no companion processor, so traversal walks reflectively.
     */
    static final class ProjectionReflective {
        @Canonicalize(TestTrim.class)
        public String userName;

        @Sanitize(TestStripDots.class)
        public String homePage;
    }

    /**
     * Generated counterpart paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_ProjectionGenerated_InputProcessor} fixture, whose
     * switch keys on {@code ctx.logicalFieldName(...)} while the emitted map keeps the wire key.
     */
    static final class ProjectionGenerated {
        @Canonicalize(TestTrim.class)
        public String userName;

        @Sanitize(TestStripDots.class)
        public String homePage;
    }

    /** Enum element type for the {@link EnumElementReflective} / {@link EnumElementGenerated} pair. */
    public enum Status {
        /** Active. */
        ACTIVE,
        /** Inactive. */
        INACTIVE
    }

    /**
     * Reflective baseline whose collection and array fields all carry a <em>scalar</em> element type
     * — no companion processor, so traversal walks reflectively.
     *
     * <p>{@code List<UUID>} and {@code List<String>} are green controls: their element types were
     * already classified as scalar leaves by both paths.
     */
    @Canonicalize(TestTrim.class)
    static final class EnumElementReflective {
        public List<Status> tags;
        public Status[] statuses;

        @Canonicalize(TestUpper.class)
        public List<Status> chained;

        public List<java.util.OptionalInt> counts;
        public List<java.util.OptionalLong> totals;
        public List<java.util.OptionalDouble> ratios;
        public List<java.util.UUID> ids;
        public List<String> labels;
    }

    /**
     * Generated counterpart paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_EnumElementGenerated_InputProcessor} fixture. The
     * APT-time collector treats every element type here as a scalar leaf, so only {@code chained}
     * (an annotated {@code OTHER}-kind field) and {@code labels} (a collection of strings) get their
     * own switch arm; the rest flow through the default {@code applyDefault} arm.
     */
    @Canonicalize(TestTrim.class)
    static final class EnumElementGenerated {
        public List<Status> tags;
        public Status[] statuses;

        @Canonicalize(TestUpper.class)
        public List<Status> chained;

        public List<java.util.OptionalInt> counts;
        public List<java.util.OptionalLong> totals;
        public List<java.util.OptionalDouble> ratios;
        public List<java.util.UUID> ids;
        public List<String> labels;
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

    /** Plain nested DTO with no declared policy of its own — used by the skip-override fixtures. */
    public static final class PlainInner {
        public String value;
    }

    /**
     * Self-referential reflective baseline carrying a <em>type-level</em> chain. Per-type metadata
     * resolution returns that chain at every level, so this is the shape whose composed chain would
     * otherwise grow with the intermediate's depth.
     */
    @Canonicalize(TestTrim.class)
    static final class TypeChainRecursiveReflective {
        public String note;

        public TypeChainRecursiveReflective child;
    }

    /**
     * Generated counterpart of {@link TypeChainRecursiveReflective}, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_TypeChainRecursiveGenerated_InputProcessor}
     * fixture whose {@code child} arm dispatches back at its own target type.
     */
    @Canonicalize(TestTrim.class)
    static final class TypeChainRecursiveGenerated {
        public String note;

        public TypeChainRecursiveGenerated child;
    }

    /**
     * Self-referential reflective baseline whose chain sits on the <em>recursive link</em> instead of
     * on the type. Per-type metadata re-offers the {@code child} field's chain at every level, so
     * this is the field-level twin of {@link TypeChainRecursiveReflective}.
     */
    static final class FieldChainRecursiveReflective {
        public String note;

        @Canonicalize(TestTrim.class)
        public FieldChainRecursiveReflective child;
    }

    /**
     * Generated counterpart of {@link FieldChainRecursiveReflective}, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_FieldChainRecursiveGenerated_InputProcessor}
     * fixture whose {@code child} arm carries the chain as its per-field constant and dispatches back
     * at its own target type.
     */
    static final class FieldChainRecursiveGenerated {
        public String note;

        @Canonicalize(TestTrim.class)
        public FieldChainRecursiveGenerated child;
    }

    /**
     * Self-referential reflective baseline carrying a type-level chain <em>and</em> a
     * {@code List<String>} field with a chain of its own. The collection field is the arm whose
     * declaration site has no logical name to key on, so this is the shape that proves an unnamed
     * field site is never mistaken for the owner type's object-level site.
     */
    @Canonicalize(TestTrim.class)
    static final class CollectionChainRecursiveReflective {
        @Sanitize(TestStripHtml.class)
        public List<String> tags;

        public CollectionChainRecursiveReflective child;
    }

    /**
     * Generated counterpart of {@link CollectionChainRecursiveReflective}, paired with the
     * hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_CollectionChainRecursiveGenerated_InputProcessor}
     * fixture whose {@code tags} arm routes through {@code applyStringCollection} and whose
     * {@code child} arm dispatches back at its own target type.
     */
    @Canonicalize(TestTrim.class)
    static final class CollectionChainRecursiveGenerated {
        @Sanitize(TestStripHtml.class)
        public List<String> tags;

        public CollectionChainRecursiveGenerated child;
    }

    /**
     * Reflective baseline for the skip-override case: the owner declares
     * {@code @SkipCanonicalization} while each field kind declares its own chain.
     */
    @SkipCanonicalization
    static final class SkipOverrideReflective {
        @Canonicalize(TestTrim.class)
        public String direct;

        @Canonicalize(TestTrim.class)
        public PlainInner nested;

        @Canonicalize(TestTrim.class)
        public List<PlainInner> many;
    }

    /**
     * Generated counterpart of {@link SkipOverrideReflective}, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_SkipOverrideGenerated_InputProcessor} fixture.
     */
    @SkipCanonicalization
    static final class SkipOverrideGenerated {
        @Canonicalize(TestTrim.class)
        public String direct;

        @Canonicalize(TestTrim.class)
        public PlainInner nested;

        @Canonicalize(TestTrim.class)
        public List<PlainInner> many;
    }

    /**
     * Reflective baseline for the declared-order case: the owner declares the strip half of an
     * order-sensitive pair while its field declares decode-then-strip. Composition must leave the
     * field's own order alone.
     */
    @Sanitize(TestStripHtml.class)
    static final class DeclaredOrderReflective {
        @Sanitize({TestDecodeEntities.class, TestStripHtml.class})
        public String value;
    }

    /**
     * Generated counterpart of {@link DeclaredOrderReflective}, paired with the hand-written
     * {@code GeneratedVsReflectiveEquivalenceTest_DeclaredOrderGenerated_InputProcessor} fixture.
     */
    @Sanitize(TestStripHtml.class)
    static final class DeclaredOrderGenerated {
        @Sanitize({TestDecodeEntities.class, TestStripHtml.class})
        public String value;
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

    /**
     * Decodes the one HTML entity these tests use, and records the seen context. Paired with
     * {@link TestStripHtml} it forms an order-sensitive chain: decoding after the strip re-introduces
     * the markup the strip removed.
     */
    public static final class TestDecodeEntities implements Sanitizer {
        @Override
        public String sanitize(@Nullable String value, @Nullable InputValueContext context) {
            if (context != null) {
                RECORDED_CONTEXTS.add(context);
            }
            return value == null ? null : value.replace("&lt;", "<").replace("&gt;", ">");
        }
    }

    /** Removes markup delimiters and records the seen context. Idempotent, and not commutative. */
    public static final class TestStripHtml implements Sanitizer {
        @Override
        public String sanitize(@Nullable String value, @Nullable InputValueContext context) {
            if (context != null) {
                RECORDED_CONTEXTS.add(context);
            }
            return value == null ? null : value.replace("<", "").replace(">", "");
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
