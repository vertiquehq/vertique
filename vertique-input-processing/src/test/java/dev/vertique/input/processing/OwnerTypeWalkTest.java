// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the owner set {@link OwnerTypeWalk} hands to an {@link InputFieldNameResolver}.
 *
 * <p>The set must be exactly what {@link DefaultInputObjectProcessor} can pass to
 * {@code InputTraversalContext#logicalFieldName(Class, String)} while processing the declared type
 * — no wider (the over-warm defects) and no narrower (the under-warm defects). Each test below
 * pins one shape where a re-derived, Jackson-side guess disagreed with the engine's own descent.
 *
 * <p>The walk is driven by a recording resolver that captures every {@code precompute} call, so the
 * assertions are about the classes the engine <em>declares</em> it may consult, not about any
 * particular payload.
 */
class OwnerTypeWalkTest {

    @Test
    @DisplayName("a single-argument non-container generic does not expose its type argument as an owner")
    void wrapperTypeArgumentIsNotPrepared() {
        Set<Class<?>> prepared = prepare(WrapperHolder.class);

        assertTrue(
                prepared.contains(WrapperHolder.class),
                "the entry point's 'w' field is a nested-object field and is recorded unconditionally, so "
                        + "WrapperHolder's own metadata is non-empty");
        assertFalse(
                prepared.contains(Wrapper.class),
                "Wrapper<T>'s own field erases to Object and carries no annotation, so Wrapper's own "
                        + "metadata declares no fields — DefaultInputObjectProcessor's schema-free branch "
                        + "can never call logicalFieldName against it, so it is not prepared either, even "
                        + "though the engine does reach it as a nested-object dispatch target");
        assertFalse(
                prepared.contains(Dto.class),
                "Wrapper<T>'s own field erases to Object, so the engine never reaches Dto and it must "
                        + "not be prepared");
    }

    @Test
    @DisplayName("an Optional payload is unwrapped and prepared")
    void optionalPayloadIsPrepared() {
        Set<Class<?>> prepared = prepare(OptionalHolder.class);

        assertTrue(
                prepared.contains(Dto.class),
                "Optional is transparent to the classifier, so the engine descends into the payload");
    }

    @Test
    @DisplayName("a collection subtype with two type arguments exposes the element its supertype binds")
    void multiParameterCollectionElementIsPreparedFromTheSupertypeBinding() {
        Set<Class<?>> prepared = prepare(PairHolder.class);

        assertTrue(prepared.contains(PairHolder.class), "the entry-point class is always an owner");
        assertTrue(
                prepared.contains(Dto.class),
                "Pair<A,B> extends ArrayList<A>, so a Pair<Dto,Other> field binds Dto elements and the "
                        + "engine dispatches each of them against Dto");
        assertFalse(
                prepared.contains(Other.class), "the second argument is not the element type, so it is not an owner");
    }

    @Test
    @DisplayName("a nested container element is not prepared, and neither is the schema-free entry point")
    void nestedContainerElementIsNotPrepared() {
        Set<Class<?>> prepared = prepare(NestedListHolder.class);

        assertFalse(
                prepared.contains(NestedListHolder.class),
                "the entry point's only field is an unannotated nested container with no determinable "
                        + "element schema, so it is not recorded and NestedListHolder's own metadata "
                        + "declares no fields — it is itself a schema-free dispatch target");
        assertFalse(
                prepared.contains(Dto.class),
                "a container element carries no element schema, so List<List<Dto>> never reaches Dto");
    }

    @Test
    @DisplayName("a field with no accessor still contributes its declared type as an owner")
    void accessorLessFieldTargetIsPrepared() {
        Set<Class<?>> prepared = prepare(AccessorLessHolder.class);

        assertTrue(
                prepared.contains(Nested.class),
                "the engine walks declared fields, so a field no bean introspector exposes is still "
                        + "descended and its target must be prepared");
    }

    @Test
    @DisplayName("the raw container class is not an owner when the element type is not determinable")
    void rawContainerOwnerIsNotPrepared() {
        Set<Class<?>> prepared = prepare(declaredTypeOf(RawContainerHolder.class, "items"));

        assertFalse(
                prepared.contains(List.class),
                "with no element schema the raw container's own metadata declares no fields, so the "
                        + "engine never projects a key against it and it has no field-name owner");
        assertFalse(prepared.contains(Dto.class), "a Map element carries no property set to descend into");
    }

    @Test
    @DisplayName("a String field's raw declared class is not an owner")
    void rawDeclaredClassOfAStringFieldIsNotPrepared() {
        Set<Class<?>> prepared = prepare(StringFieldHolder.class);

        assertFalse(
                prepared.contains(String.class),
                "a wire/declared shape mismatch dispatches against String.class, but String's own "
                        + "metadata declares no fields, so that dispatch is schema-free and String is not "
                        + "a field-name owner");
    }

    @Test
    @DisplayName("a platform class is prepared but never descended into")
    void platformClassIsPreparedButNotDescended() {
        Set<Class<?>> prepared = prepare(PlatformHolder.class);

        assertTrue(prepared.contains(Throwable.class), "the field's declared type is an owner like any other");
        assertFalse(
                prepared.contains(StackTraceElement.class),
                "Throwable is descendable and would contribute StackTraceElement through its "
                        + "stackTrace field, so only the platform guard can keep it out of the owner set");
    }

    /*
     * Attempting to prove the class-graph-level shape directly — a real class whose binary name
     * starts with `jakarta.` and which declares a field targeting an application DTO — would require
     * either adding a real jakarta.* source directory (not allowed for this fixture set) or
     * fabricating one at runtime via in-memory compilation or hand-patched class bytes. Both are
     * disproportionate machinery for pinning a one-line prefix check, and every jakarta.* class
     * already reachable from this module's classpath (e.g. jakarta.annotation.Nullable) is an
     * annotation with no DTO-shaped declared fields, so it cannot stand in for the walk-level
     * scenario either. The three tests below pin the predicate OwnerTypeWalk.prepare actually
     * consults, which is an honest and sufficient proof of the same defect.
     */

    @Test
    @DisplayName("a jakarta.-prefixed binary name is not a platform type")
    void jakartaPrefixedBinaryNameIsNotAPlatformType() {
        assertFalse(
                OwnerTypeWalk.isPlatformType("jakarta.example.SomeDto"),
                "jakarta.* holds ordinary third-party and application-owned types with declared "
                        + "fields, so it must not be treated as a platform namespace");
    }

    @Test
    @DisplayName("a javax.-prefixed binary name is not a platform type")
    void javaxPrefixedBinaryNameIsNotAPlatformType() {
        assertFalse(
                OwnerTypeWalk.isPlatformType("javax.example.SomeDto"),
                "javax.* holds ordinary third-party and application-owned types with declared "
                        + "fields, so it must not be treated as a platform namespace");
    }

    @Test
    @DisplayName("a java.-prefixed binary name is a platform type")
    void javaPrefixedBinaryNameIsAPlatformType() {
        assertTrue(
                OwnerTypeWalk.isPlatformType("java.lang.String"),
                "java.* is the JDK itself, whose generic containers erase and so cannot yield an "
                        + "application type through a declared field");
    }

    @Test
    @DisplayName("an annotated schema-free field's raw declared class is not prepared")
    void annotatedSchemaFreeFieldTypeIsNotPrepared() {
        Set<Class<?>> prepared = prepare(AttributesHolder.class);

        assertTrue(
                prepared.contains(AttributesHolder.class),
                "the field declares policy, so AttributesHolder's own metadata records it and the entry "
                        + "point itself remains an owner");
        assertFalse(
                prepared.contains(Map.class),
                "Map's own metadata declares no fields of its own — an interface has no declared "
                        + "instance fields — so DefaultInputObjectProcessor's schema-free branch can never "
                        + "call logicalFieldName against Map.class even though the annotated field records "
                        + "it as a dispatch target; precomputing it would only ever fail, never help");
    }

    @Test
    @DisplayName("an unannotated scalar field contributes no owner, and the schema-free entry point is not "
            + "prepared either")
    void unannotatedSchemaFreeFieldContributesNothing() {
        Set<Class<?>> prepared = prepare(ScalarHolder.class);

        assertEquals(
                Set.of(),
                prepared,
                "an unannotated scalar field is not recorded at all, so ScalarHolder's own metadata "
                        + "declares no fields either — it is itself a schema-free dispatch target, exactly "
                        + "like any other reachable class whose own metadata is empty");
    }

    @Test
    @DisplayName("a reachable schema-free type is walked but never passed to precompute")
    void schemaFreeOwnerIsNotPrecomputed() {
        Set<Class<?>> prepared = prepare(SchemaFreeNestedHolder.class);

        assertTrue(
                prepared.contains(SchemaFreeNestedHolder.class),
                "the entry point's 'nested' field is a nested-object field and is recorded "
                        + "unconditionally, so SchemaFreeNestedHolder's own metadata is non-empty");
        assertFalse(
                prepared.contains(SchemaFreeNested.class),
                "SchemaFreeNested is reachable — the walk resolves its metadata and, being descendable "
                        + "and non-platform, would follow its own fields if it had any — but its only field "
                        + "is an unannotated scalar, so its own metadata declares no fields; "
                        + "DefaultInputObjectProcessor's schema-free branch can therefore never call "
                        + "logicalFieldName against it, and precomputing it would only ever fail, never "
                        + "help — a genuine startup collision on this class is not possible");
    }

    @Test
    @DisplayName("a collection subtype that declares its own field is still precomputed")
    void fieldDeclaringCollectionSubtypeIsStillPrecomputed() {
        Set<Class<?>> prepared = prepare(FieldDeclaringCollectionHolder.class);

        assertTrue(
                prepared.contains(FieldDeclaringCollection.class),
                "FieldDeclaringCollection declares its own 'label' field alongside extending "
                        + "ArrayList<Dto>, so its own metadata is genuinely non-empty and it remains a "
                        + "legitimate owner — a blanket collection-subtype exclusion would wrongly skip it "
                        + "even though a wire/declared shape mismatch can dispatch a key against it");
        assertTrue(prepared.contains(Dto.class), "the collection's element type is still an owner too");
    }

    @Test
    @DisplayName("a null declared type prepares nothing")
    void nullDeclaredTypePreparesNothing() {
        Set<Class<?>> prepared = prepare(null);

        assertEquals(Set.of(), prepared, "a null declared type names no owner, so the resolver is never called");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("a self-referential type graph terminates")
    void cyclicGraphTerminates() {
        Set<Class<?>> prepared = prepare(Node.class);

        assertEquals(Set.of(Node.class), prepared, "the visited set closes the cycle after one visit");
    }

    @Test
    @DisplayName("a generated processor's declared owner types replace the reflective contributions")
    void generatedProcessorOwnerTypesArePreferred() {
        GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
        dispatcher.register(
                GeneratedOwner.class,
                new DeclaringProcessor<>(GeneratedOwner.class, Set.of(GeneratedOwner.class, Declared.class)));

        Set<Class<?>> prepared = prepare(GeneratedOwner.class, dispatcher);

        assertTrue(prepared.contains(GeneratedOwner.class), "the entry-point class is always an owner");
        assertTrue(
                prepared.contains(Declared.class),
                "a class the generated processor declares is an owner even though no declared field names it");
        assertFalse(
                prepared.contains(Other.class),
                "the generated owner set replaces the reflective contributions rather than adding to them, "
                        + "so a reflective field target the processor does not declare is not prepared");
    }

    @Test
    @DisplayName("an empty generated owner set falls back to the reflective walk")
    void emptyOwnerTypesFallsBackToTheReflectiveWalk() {
        GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
        dispatcher.register(GeneratedOwner.class, new DeclaringProcessor<>(GeneratedOwner.class, Set.of()));

        Set<Class<?>> prepared = prepare(GeneratedOwner.class, dispatcher);

        assertEquals(
                prepare(GeneratedOwner.class),
                prepared,
                "an empty set is the sentinel for \"declares no owner set\", so the walk prepares exactly "
                        + "what it would with no processor at all");
        assertTrue(prepared.contains(Other.class), "the reflective field target is prepared by the fallback");
    }

    @Test
    @DisplayName("a generated owner set is closed transitively by the engine")
    void generatedOwnerTypesAreClosedTransitively() {
        GeneratedInputProcessorDispatcher dispatcher = GeneratedInputProcessorDispatcher.withoutContinuation();
        dispatcher.register(
                GeneratedOwner.class,
                new DeclaringProcessor<>(GeneratedOwner.class, Set.of(GeneratedOwner.class, GeneratedNested.class)));

        Set<Class<?>> prepared = prepare(GeneratedOwner.class, dispatcher);

        assertTrue(prepared.contains(GeneratedNested.class), "a declared owner is prepared");
        assertTrue(
                prepared.contains(Deep.class),
                "the generated set is flat, so the engine must descend a declared owner and prepare its "
                        + "own field targets");
    }

    // --- Harness ---

    /**
     * Runs the walk for {@code declaredType} with no generated processor registered and returns every
     * class it prepared.
     *
     * @param declaredType the body or message type
     * @return the prepared owner types, in the order the walk emitted them
     */
    private static Set<Class<?>> prepare(Type declaredType) {
        return prepare(declaredType, GeneratedInputProcessorDispatcher.withoutContinuation());
    }

    /**
     * Runs the walk for {@code declaredType} against {@code dispatcher} and returns every class it
     * prepared.
     *
     * @param declaredType the body or message type
     * @param dispatcher   the dispatcher the walk consults for generated owner sets
     * @return the prepared owner types, in the order the walk emitted them
     */
    private static Set<Class<?>> prepare(Type declaredType, GeneratedInputProcessorDispatcher dispatcher) {
        RecordingResolver resolver = new RecordingResolver();
        OwnerTypeWalk.prepare(declaredType, resolver, new InputPolicyMetadataResolver(), dispatcher);
        return resolver.owners();
    }

    /**
     * Reads a fixture field's full generic type, so a shape such as {@code List<Map<String, Dto>>}
     * can be handed to the walk as a declared type without a type-token helper.
     *
     * @param owner     the fixture class declaring the field
     * @param fieldName the field's name
     * @return the field's generic type
     */
    private static Type declaredTypeOf(Class<?> owner, String fieldName) {
        try {
            return owner.getDeclaredField(fieldName).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("fixture field " + owner.getSimpleName() + "#" + fieldName + " is missing", e);
        }
    }

    /** Captures every owner type the walk prepares. */
    private static final class RecordingResolver implements InputFieldNameResolver {

        private final List<Class<?>> precomputed = new ArrayList<>();

        @Override
        public String logicalName(Class<?> ownerType, String wireName) {
            return wireName;
        }

        @Override
        public void precompute(Class<?> ownerType) {
            precomputed.add(ownerType);
        }

        /**
         * Returns the prepared owner types in emission order.
         *
         * @return the distinct prepared classes
         */
        Set<Class<?>> owners() {
            return new LinkedHashSet<>(precomputed);
        }
    }

    /**
     * A generated-processor stand-in that declares a fixed owner set. Registered through the
     * dispatcher's public {@code register} entry so the walk resolves it exactly as it would a class
     * emitted by the annotation processor.
     *
     * @param <T> the target DTO type
     */
    private record DeclaringProcessor<T>(Class<T> target, Set<Class<?>> ownerTypes)
            implements GeneratedInputProcessor<T> {

        @Override
        public Class<T> targetType() {
            return target;
        }

        @Override
        public Set<Class<?>> fieldNameOwnerTypes() {
            return ownerTypes;
        }

        @Override
        public Object process(
                Object intermediate,
                EffectiveInputPolicies policies,
                InputLocation location,
                ChainResolver resolver,
                GeneratedInputProcessorDispatcher dispatcher,
                InputTraversalContext parent,
                String parentPath) {
            throw new UnsupportedOperationException("the warm-up walk never processes input");
        }
    }

    // --- Fixtures ---

    /** A nested DTO that must be prepared only when the engine can actually reach it. */
    static class Dto {
        String value;
    }

    /** A second DTO used to give {@link Pair} a distinct second type argument. */
    static class Other {
        String value;
    }

    /** A single-argument generic that is not a container — its field erases to {@link Object}. */
    static class Wrapper<T> {
        T value;
    }

    /** Entry point for the reported issue: the type argument of a non-container generic. */
    static class WrapperHolder {
        Wrapper<Dto> w;
    }

    /** Entry point for the {@code Optional} unwrap. */
    static class OptionalHolder {
        Optional<Dto> o;
    }

    /** A collection subtype carrying two type arguments. */
    static class Pair<A, B> extends ArrayList<A> {
        private static final long serialVersionUID = 1L;
    }

    /** Entry point for the two-type-argument collection subtype. */
    static class PairHolder {
        Pair<Dto, Other> pair;
    }

    /** Entry point for the nested-container element. */
    static class NestedListHolder {
        List<List<Dto>> nested;
    }

    /** The target of a field no bean introspector exposes. */
    static class Nested {
        String value;
    }

    /** Entry point for a field with neither getter nor setter. */
    static class AccessorLessHolder {
        private Nested n;
    }

    /** Declares the {@code List<Map<String, Dto>>} shape handed to the walk directly. */
    static class RawContainerHolder {
        List<Map<String, Dto>> items;
    }

    /** Entry point for the mismatch family: a plain {@code String} field. */
    static class StringFieldHolder {
        String name;
    }

    /**
     * Entry point for the platform bound. {@link Throwable} is descendable — not a scalar leaf, not
     * {@link Object}, not an array, not a {@link Map} — so nothing but the platform guard stops the
     * walk from descending it and picking up {@link StackTraceElement} off its {@code stackTrace}
     * field.
     */
    static class PlatformHolder {
        Throwable failure;
    }

    /** Entry point for an annotated field whose declared type carries no property set. */
    static class AttributesHolder {

        @Sanitize(TestSanitizer.class)
        Map<String, String> attrs;
    }

    /** Entry point for an unannotated scalar field. */
    static class ScalarHolder {
        int count;
    }

    /**
     * A descendable nested-object owner whose only field is an unannotated scalar, so its own
     * metadata declares no fields at all — the schema-free-dispatch-target shape the walk must not
     * precompute even though the type itself is genuinely reachable.
     */
    static class SchemaFreeNested {
        int code;
    }

    /** Entry point whose only field targets {@link SchemaFreeNested}. */
    static class SchemaFreeNestedHolder {
        SchemaFreeNested nested;
    }

    /**
     * A collection subtype that also declares its own {@code String} field — unlike {@link Pair},
     * which contributes no field of its own and is therefore genuinely schema-free, this type's own
     * metadata is non-empty, so it remains a legitimate owner even though it is also a collection
     * subtype. This is the fixture that makes a blanket collection-subtype exclusion wrong: the gate
     * must be "has declared fields", not "is a collection".
     */
    static class FieldDeclaringCollection extends ArrayList<Dto> {
        private static final long serialVersionUID = 1L;
        String label;
    }

    /** Entry point for {@link FieldDeclaringCollection}. */
    static class FieldDeclaringCollectionHolder {
        FieldDeclaringCollection items;
    }

    /** A self-referential type graph. */
    static class Node {
        Node child;
    }

    /** Entry point for the generated path: its only declared field targets {@link Other}. */
    static class GeneratedOwner {
        Other other;
    }

    /** A DTO reachable only through a generated processor's declared owner set. */
    static class Declared {
        String value;
    }

    /** A declared owner whose own field target the engine must close over. */
    static class GeneratedNested {
        Deep deep;
    }

    /** The transitively reachable target of {@link GeneratedNested}. */
    static class Deep {
        String value;
    }

    /** Sanitizer referenced by {@link AttributesHolder}; never invoked by these tests. */
    static final class TestSanitizer implements Sanitizer {

        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }
}
