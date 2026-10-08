// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.TestStripControlsSanitizer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A declared policy the engine provably cannot run fails registration instead of being skipped in
 * silence. Two shapes are covered: a policy-declaring type reachable only as the value of a
 * {@code Map}, and a polymorphic base whose subtype carries a policy the base does not.
 *
 * <p>Every refusal has a paired negative case. A rule that rejected a {@code Map<String, String>}
 * or a subtype that merely inherits the base's own policy would turn working applications into
 * startup failures, so the accepted shapes are as much the contract as the refused ones.
 */
class StrandedPolicyShapesTest {

    private DefaultInputObjectProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DefaultInputObjectProcessor(
                new InputPolicyMetadataResolver(),
                cls -> {
                    throw new IllegalArgumentException("Unknown canonicalizer: " + cls);
                },
                cls -> new TestStripControlsSanitizer());
    }

    // --- Fixtures: value types ---

    /** Declares a chain on a field. */
    static class Governed {
        @Sanitize(TestStripControlsSanitizer.class)
        String name;
    }

    /** Declares nothing. */
    static class Plain {
        String name;
    }

    /** Reaches {@link Governed} one hop down, so the policy is not declared on the value itself. */
    static class Wrapper {
        Governed inner;
    }

    // --- Fixtures: Map shapes that strand a policy ---

    static class MapHolder {
        Map<String, Governed> values;
    }

    static class ListOfMaps {
        List<Map<String, Governed>> values;
    }

    static class MapOfLists {
        Map<String, List<Governed>> values;
    }

    static class MapOfMaps {
        Map<String, Map<String, Governed>> values;
    }

    static class OptionalMap {
        Optional<Map<String, Governed>> values;
    }

    static class BoundedWildcardMap {
        Map<String, ? extends Governed> values;
    }

    static class WrappedValue {
        Map<String, Wrapper> values;
    }

    /** A Map subtype that fixes its value type. */
    static class GovernedMap extends LinkedHashMap<String, Governed> {}

    static class FixedValueSubtype {
        GovernedMap values;
    }

    static class MapArray {
        Map<String, Governed>[] values;
    }

    static class RecordHolderHost {
        record Holder(Map<String, Governed> values) {}
    }

    /** Inherits the Map-typed property from a superclass. */
    static class InheritedMap extends MapHolder {
        String extra;
    }

    /** Reaches the stranded shape only through a nested owner. */
    static class Outer {
        MapHolder inner;
    }

    /** The value type declares a policy of its own and also recurses through the map. */
    static class Tree {
        @Sanitize(TestStripControlsSanitizer.class)
        String label;

        Map<String, Tree> children;
    }

    // --- Fixtures: Map shapes that lose nothing ---

    static class StringMap {
        Map<String, String> values;
    }

    static class ObjectMap {
        Map<String, Object> values;
    }

    @SuppressWarnings("rawtypes")
    static class RawMap {
        Map values;
    }

    static class UnboundedWildcardMap {
        Map<String, ?> values;
    }

    static class PlainValueMap {
        Map<String, Plain> values;
    }

    static class AnnotatedStringMap {
        @Sanitize(TestStripControlsSanitizer.class)
        Map<String, String> values;
    }

    static class IntegerMap {
        Map<String, Integer> values;
    }

    /** Recurses through a map but declares no policy anywhere. */
    static class PlainTree {
        Map<String, PlainTree> children;
    }

    /** A self-referential Map subtype; the walk must terminate on it. */
    static class SelfMap extends HashMap<String, SelfMap> {}

    static class SelfMapHost {
        SelfMap values;
    }

    /** A transient Map the codec never binds. */
    static class TransientMapHolder {
        transient Map<String, Governed> cache;
        String name;
    }

    static class StaticMapIgnored {
        static Map<String, Governed> shared;
        String name;
    }

    private static Type fieldType(Class<?> owner, String name) throws NoSuchFieldException {
        return owner.getDeclaredField(name).getGenericType();
    }

    @Nested
    @DisplayName("a policy-declaring type behind a Map value")
    class MapValues {

        private ConfigurationException refused(Type declared) {
            return assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(declared, InputFieldNameResolver.IDENTITY));
        }

        @Test
        @DisplayName("is refused, naming the property, the owner and the value type")
        void refusalNamesTheDeclaration() {
            String message = refused(MapHolder.class).getMessage();

            assertTrue(message.contains("'values'"), message);
            assertTrue(message.contains(MapHolder.class.getName()), message);
            assertTrue(message.contains(Governed.class.getName()), message);
            assertTrue(message.contains("silently never run"), message);
        }

        @Test
        @DisplayName("is refused at every container nesting and for every way of writing the value type")
        void refusedAcrossShapes() {
            for (Class<?> holder : List.of(
                    ListOfMaps.class,
                    MapOfLists.class,
                    MapOfMaps.class,
                    OptionalMap.class,
                    BoundedWildcardMap.class,
                    WrappedValue.class,
                    FixedValueSubtype.class,
                    MapArray.class,
                    RecordHolderHost.Holder.class,
                    InheritedMap.class,
                    Outer.class,
                    Tree.class)) {
                ConfigurationException ex = refused(holder);
                assertTrue(ex.getMessage().contains("Map value"), holder.getSimpleName() + ": " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("is refused when the Map is the declared entry-point type itself")
        void entryPointMapIsRefused() throws NoSuchFieldException {
            String message = refused(fieldType(MapHolder.class, "values")).getMessage();

            assertTrue(message.contains(Governed.class.getName()), message);
        }

        @Test
        @DisplayName("is accepted when the value type declares nothing, or is not a class with properties")
        void acceptedWhenNothingIsStranded() {
            for (Class<?> holder : List.of(
                    StringMap.class,
                    ObjectMap.class,
                    RawMap.class,
                    UnboundedWildcardMap.class,
                    PlainValueMap.class,
                    AnnotatedStringMap.class,
                    IntegerMap.class,
                    PlainTree.class,
                    SelfMapHost.class,
                    StaticMapIgnored.class)) {
                assertDoesNotThrow(
                        () -> processor.precomputeFieldNameResolution(holder, InputFieldNameResolver.IDENTITY),
                        holder.getSimpleName());
            }
        }

        @Test
        @DisplayName("is accepted as an entry point when the value type declares nothing")
        void plainEntryPointMapIsAccepted() throws NoSuchFieldException {
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(
                    fieldType(StringMap.class, "values"), InputFieldNameResolver.IDENTITY));
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(
                    fieldType(PlainValueMap.class, "values"), InputFieldNameResolver.IDENTITY));
        }

        private InputFieldNameResolver binding(@jakarta.annotation.Nullable Set<String> bound) {
            return new InputFieldNameResolver() {
                @Override
                public String logicalName(Class<?> ownerType, String wireName) {
                    return wireName;
                }

                @Override
                public Set<String> boundJavaNames(Class<?> ownerType) {
                    return bound;
                }
            };
        }

        @Test
        @DisplayName("is skipped only for a transient property the codec binds no key into")
        void onlyAProvablyUnboundTransientPropertyIsSkipped() {
            assertDoesNotThrow(
                    () -> processor.precomputeFieldNameResolution(TransientMapHolder.class, binding(Set.of("name"))));
            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(
                            TransientMapHolder.class, binding(Set.of("name", "cache"))),
                    "a transient field with an accessor of that name is bound after all");
            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(TransientMapHolder.class, binding(null)),
                    "a resolver that cannot enumerate what it binds is trusted as a whole");
            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(MapHolder.class, binding(Set.of("other"))),
                    "an unbound property that is not transient may still be filled through an accessor of"
                            + " another name");
        }

        @Test
        @DisplayName("is reported by the startup gate, so a transport cannot boot engine-less over it")
        void startupGateSeesThePolicy() {
            for (Class<?> holder : List.of(MapHolder.class, ListOfMaps.class, WrappedValue.class, Outer.class)) {
                assertTrue(InputObjectProcessor.declaresPolicies(holder), holder.getSimpleName());
            }
            for (Class<?> holder : List.of(StringMap.class, PlainValueMap.class, PlainTree.class)) {
                assertFalse(InputObjectProcessor.declaresPolicies(holder), holder.getSimpleName());
            }
        }

        @Test
        @DisplayName("is reported by the startup gate for a Map entry-point type")
        void startupGateSeesAMapEntryPoint() throws NoSuchFieldException {
            assertTrue(InputObjectProcessor.declaresPolicies(fieldType(MapHolder.class, "values")));
            assertFalse(InputObjectProcessor.declaresPolicies(fieldType(StringMap.class, "values")));
        }
    }

    // --- Fixtures: polymorphic bases ---

    static class Base {
        @Sanitize(TestStripControlsSanitizer.class)
        String shared;
    }

    /** Adds a chain the base does not carry. */
    static class AddsChain extends Base {
        @Sanitize(TestStripControlsSanitizer.class)
        String extra;
    }

    /** Adds only an undeclared field and inherits the base's own chain. */
    static class AddsNothing extends Base {
        String other;
        int count;
    }

    static class AddsSkip extends Base {
        @SkipSanitization
        String hidden;
    }

    @Sanitize(TestStripControlsSanitizer.class)
    static class AddsTypeLevel extends Base {
        String other;
    }

    /** Holds a type whose graph declares policy. */
    static class AddsGovernedField extends Base {
        Governed nested;
    }

    /** Holds a type whose graph declares nothing. */
    static class AddsPlainField extends Base {
        Plain nested;
    }

    /** Adds a Map property whose value type declares policy. */
    static class AddsStrandedMap extends Base {
        Map<String, Governed> extra;
    }

    /** Adds a Map property whose value type declares nothing. */
    static class AddsPlainMap extends Base {
        Map<String, Plain> extra;
    }

    /** Second polymorphic level, reachable only through {@link LevelOneSub}. */
    abstract static class LevelTwoBase {}

    static class LevelTwoCircle extends LevelTwoBase {
        @Sanitize(TestStripControlsSanitizer.class)
        String label;
    }

    abstract static class LevelOneBase {}

    static class LevelOneSub extends LevelOneBase {
        LevelTwoBase shape;
    }

    /** A base with no fields of its own, so the emptiness gate never selects it. */
    abstract static class Shape {}

    static class Circle extends Shape {
        @Sanitize(TestStripControlsSanitizer.class)
        String label;
    }

    static class PlainSquare extends Shape {
        String label;
    }

    static class HoldsBase {
        Base base;
    }

    static class HoldsShapes {
        List<Shape> shapes;
    }

    private static InputFieldNameResolver subtypes(Map<Class<?>, Set<Class<?>>> bySubtypes) {
        return new InputFieldNameResolver() {
            @Override
            public String logicalName(Class<?> ownerType, String wireName) {
                return wireName;
            }

            @Override
            public Set<Class<?>> polymorphicSubtypes(Class<?> ownerType) {
                return bySubtypes.getOrDefault(ownerType, Set.of());
            }
        };
    }

    @Nested
    @DisplayName("a policy a polymorphic subtype adds")
    class PolymorphicSubtypes {

        @Test
        @DisplayName("is refused, naming the base, the subtype and what it adds")
        void refusalNamesTheDeclaration() {
            InputFieldNameResolver resolver = subtypes(Map.of(Base.class, Set.of(Base.class, AddsChain.class)));

            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> processor.precomputeFieldNameResolution(Base.class, resolver));

            String message = ex.getMessage();
            assertTrue(message.contains(Base.class.getName()), message);
            assertTrue(message.contains(AddsChain.class.getName()), message);
            assertTrue(message.contains("'extra'"), message);
            assertTrue(message.contains("silently never run"), message);
        }

        @Test
        @DisplayName("is refused for a skip, a type-level chain and a nested governed type")
        void refusedForEveryKindOfDeclaration() {
            for (Class<?> subtype : List.of(AddsSkip.class, AddsTypeLevel.class, AddsGovernedField.class)) {
                InputFieldNameResolver resolver = subtypes(Map.of(Base.class, Set.of(subtype)));

                assertThrows(
                        ConfigurationException.class,
                        () -> processor.precomputeFieldNameResolution(Base.class, resolver),
                        subtype.getSimpleName());
            }
        }

        @Test
        @DisplayName("is refused for a base with no fields, which the field-name gate never selects")
        void fieldlessBaseIsStillChecked() {
            InputFieldNameResolver resolver = subtypes(Map.of(Shape.class, Set.of(Circle.class)));

            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> processor.precomputeFieldNameResolution(Shape.class, resolver));

            assertTrue(ex.getMessage().contains(Circle.class.getName()), ex.getMessage());
        }

        @Test
        @DisplayName("is refused when the base is reached through a field or a collection")
        void baseReachedThroughTheWalk() {
            InputFieldNameResolver viaField = subtypes(Map.of(Base.class, Set.of(AddsChain.class)));
            InputFieldNameResolver viaList = subtypes(Map.of(Shape.class, Set.of(Circle.class)));

            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(HoldsBase.class, viaField));
            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(HoldsShapes.class, viaList));
        }

        @Test
        @DisplayName("is accepted when the subtype only inherits what the base already declares")
        void inheritedPolicyIsAccepted() {
            InputFieldNameResolver resolver = subtypes(Map.of(Base.class, Set.of(AddsNothing.class)));

            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(Base.class, resolver));
        }

        @Test
        @DisplayName("is accepted when the subtype adds only structure that declares no policy")
        void policyFreeAdditionsAreAccepted() {
            InputFieldNameResolver resolver =
                    subtypes(Map.of(Base.class, Set.of(AddsNothing.class, AddsPlainField.class)));
            InputFieldNameResolver plainShape = subtypes(Map.of(Shape.class, Set.of(PlainSquare.class)));

            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(Base.class, resolver));
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(Shape.class, plainShape));
        }

        @Test
        @DisplayName("is refused for a Map property only the subtype declares, and accepted when the value is plain")
        void subtypeMapPropertyIsChecked() {
            assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(
                            Base.class, subtypes(Map.of(Base.class, Set.of(AddsStrandedMap.class)))));
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(
                    Base.class, subtypes(Map.of(Base.class, Set.of(AddsPlainMap.class)))));
        }

        @Test
        @DisplayName("is refused one polymorphic level down, behind a subtype that declares nothing itself")
        void nestedPolymorphicBaseBehindASubtypeIsChecked() {
            InputFieldNameResolver resolver = subtypes(Map.of(
                    LevelOneBase.class, Set.of(LevelOneSub.class),
                    LevelTwoBase.class, Set.of(LevelTwoCircle.class)));

            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> processor.precomputeFieldNameResolution(LevelOneBase.class, resolver));

            assertTrue(ex.getMessage().contains(LevelTwoCircle.class.getName()), ex.getMessage());
        }

        @Test
        @DisplayName("is ignored when the resolver reports no subtypes, the base itself, or an unrelated class")
        void nothingToCheck() {
            assertDoesNotThrow(
                    () -> processor.precomputeFieldNameResolution(Base.class, InputFieldNameResolver.IDENTITY));
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(
                    Base.class, subtypes(Map.of(Base.class, Set.of(Base.class)))));
            assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(
                    Base.class, subtypes(Map.of(Base.class, Set.of(Circle.class)))));
        }
    }
}
