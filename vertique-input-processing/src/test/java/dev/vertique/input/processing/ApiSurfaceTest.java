// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Freezes the published surface of {@code dev.vertique.input.processing}.
 *
 * <p>The module is a standalone consumable artifact, so everything {@code public} in it is a
 * compatibility commitment. This test is <em>exact and negative</em>: for each published type it
 * asserts the full set of public declared methods, fields and constructors matches a hand-written
 * ledger — so an accidentally-widened member fails the build just as loudly as a removed one — and
 * it asserts the internal types stay package-private.
 *
 * <p>A directory scan of the compiled output backs the ledger up: any top-level class in the
 * package that is not in the expected inventory fails, so a new type cannot be added to the
 * module's surface without being classified here first.
 *
 * <p>Signatures are compared as {@code name(SimpleParamType,...)} strings; the parameter list is
 * part of the ledger, so an overload added or a parameter type changed is a failure.
 */
class ApiSurfaceTest {

    /**
     * Types published to consumers of this module.
     *
     * <p>{@code InputFieldNameResolver} is deliberately absent: the contract is declared in
     * {@code dev.vertique.core.sanitization} beside its vocabulary siblings ({@code InputLocation},
     * {@code Canonicalizer}, {@code Sanitizer}), so its surface is frozen by {@code vertique-core}'s
     * {@code SanitizationContractsTest} rather than here. This module consumes it — every ledgered
     * signature below that names it still pins the parameter type.
     */
    private static final Set<String> PUBLIC_TYPES = Set.of(
            "InputObjectProcessor",
            "EffectiveInputPolicies",
            "InputTraversalContext",
            "ChainResolver",
            "GeneratedInputProcessor",
            "GeneratedInputProcessorDispatcher",
            "GeneratedSupport");

    /** Types that implement the engine but are deliberately not part of the surface. */
    private static final Set<String> INTERNAL_TYPES = Set.of(
            "DefaultInputObjectProcessor", "InputPolicyMetadata", "InputPolicyMetadataResolver", "TypeClassifier");

    @Nested
    @DisplayName("package inventory")
    class PackageInventory {

        @Test
        @DisplayName("published types are public")
        void publishedTypesArePublic() throws ClassNotFoundException {
            for (String name : new TreeSet<>(PUBLIC_TYPES)) {
                Class<?> type = forName(name);
                assertTrue(Modifier.isPublic(type.getModifiers()), name + " must be public");
            }
        }

        @Test
        @DisplayName("internal types are not public")
        void internalTypesAreNotPublic() throws ClassNotFoundException {
            for (String name : new TreeSet<>(INTERNAL_TYPES)) {
                Class<?> type = forName(name);
                assertFalse(
                        Modifier.isPublic(type.getModifiers()),
                        name + " must stay package-private — it is not part of the module surface");
            }
        }

        @Test
        @DisplayName("compiled output contains no top-level type outside the classified inventory")
        void noUnclassifiedTopLevelTypes() {
            Set<String> expected = new TreeSet<>(PUBLIC_TYPES);
            expected.addAll(INTERNAL_TYPES);

            assertEquals(
                    expected,
                    compiledTopLevelClassNames(),
                    "every top-level type in dev.vertique.input.processing must be classified as "
                            + "published or internal by this test");
        }

        /**
         * Lists the simple names of all top-level classes compiled into this package, excluding
         * nested/anonymous classes ({@code $}) and {@code package-info}.
         *
         * @return the simple names found in the module's compiled main-classes directory
         */
        private Set<String> compiledTopLevelClassNames() {
            File packageDir = new File(
                    InputObjectProcessor.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .getPath(),
                    InputObjectProcessor.class.getPackageName().replace('.', '/'));
            assertTrue(packageDir.isDirectory(), "expected compiled classes directory at " + packageDir);

            File[] files = packageDir.listFiles((dir, name) -> name.endsWith(".class"));
            assertTrue(files != null && files.length > 0, "no compiled classes found in " + packageDir);

            return Arrays.stream(files)
                    .map(File::getName)
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> !name.contains("$"))
                    .filter(name -> !name.equals("package-info"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        private Class<?> forName(String simpleName) throws ClassNotFoundException {
            return Class.forName(InputObjectProcessor.class.getPackageName() + "." + simpleName);
        }
    }

    @Nested
    @DisplayName("InputObjectProcessor")
    class InputObjectProcessorSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void inputObjectProcessorSurface() {
            assertMethods(
                    InputObjectProcessor.class,
                    "createDefault(Function,Function)",
                    "declaresPolicies(Type)",
                    "processInput(Object,Type,EffectiveInputPolicies,InputLocation,InputFieldNameResolver)");
            assertNoPublicFields(InputObjectProcessor.class);
            assertNoPublicConstructors(InputObjectProcessor.class);
        }
    }

    @Nested
    @DisplayName("EffectiveInputPolicies")
    class EffectiveInputPoliciesSurface {

        @Test
        @DisplayName("public members match the frozen ledger (accessors, isEmpty, record boilerplate)")
        void effectiveInputPoliciesSurface() {
            assertMethods(
                    EffectiveInputPolicies.class,
                    "canonicalizers()",
                    "sanitizers()",
                    "isEmpty()",
                    "equals(Object)",
                    "hashCode()",
                    "toString()");
            assertFields(EffectiveInputPolicies.class, "NONE");
            assertConstructors(EffectiveInputPolicies.class, "<init>(List,List)");
        }
    }

    @Nested
    @DisplayName("InputTraversalContext")
    class InputTraversalContextSurface {

        @Test
        @DisplayName("public members match the frozen ledger (raw-list descend only)")
        void inputTraversalContextSurface() {
            assertMethods(
                    InputTraversalContext.class,
                    "fromPolicies(EffectiveInputPolicies,InputFieldNameResolver)",
                    "logicalFieldName(Class,String)",
                    "descend(List,List,boolean,boolean,List,List,boolean,boolean)",
                    "inheritedCanonicalizerChain()",
                    "inheritedSanitizerChain()",
                    "inheritedSkipCanonicalization()",
                    "inheritedSkipSanitization()");
            assertNoPublicFields(InputTraversalContext.class);
            assertNoPublicConstructors(InputTraversalContext.class);
        }

        @Test
        @DisplayName("the metadata-bearing descend overload is not public")
        void metadataDescendOverloadIsNotPublic() throws NoSuchMethodException {
            Method descend = InputTraversalContext.class.getDeclaredMethod(
                    "descend", InputPolicyMetadata.class, FieldPolicyMetadata.class);
            assertFalse(
                    Modifier.isPublic(descend.getModifiers()),
                    "descend(InputPolicyMetadata, FieldPolicyMetadata) must stay package-private — "
                            + "its metadata carriers are internal");
        }
    }

    @Nested
    @DisplayName("ChainResolver")
    class ChainResolverSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void chainResolverSurface() {
            assertMethods(ChainResolver.class, "apply(String,List,List,InputValueContext)");
            assertNoPublicFields(ChainResolver.class);
            assertNoPublicConstructors(ChainResolver.class);
        }
    }

    @Nested
    @DisplayName("GeneratedInputProcessor")
    class GeneratedInputProcessorSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void generatedInputProcessorSurface() {
            assertMethods(
                    GeneratedInputProcessor.class,
                    "targetType()",
                    "process(Object,EffectiveInputPolicies,InputLocation,ChainResolver,"
                            + "GeneratedInputProcessorDispatcher,InputTraversalContext,String)");
            assertNoPublicFields(GeneratedInputProcessor.class);
            assertNoPublicConstructors(GeneratedInputProcessor.class);
        }
    }

    @Nested
    @DisplayName("GeneratedInputProcessorDispatcher")
    class GeneratedInputProcessorDispatcherSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void generatedInputProcessorDispatcherSurface() {
            assertMethods(
                    GeneratedInputProcessorDispatcher.class,
                    "register(Class,GeneratedInputProcessor)",
                    "dispatchNested(Object,Class,EffectiveInputPolicies,InputLocation,ChainResolver,"
                            + "InputTraversalContext,String,Class)",
                    "withoutContinuation()");
            assertNoPublicFields(GeneratedInputProcessorDispatcher.class);
            assertConstructors(GeneratedInputProcessorDispatcher.class, "<init>(ReflectiveContinuation)");
        }

        @Test
        @DisplayName("resolve, walkUnknown and generatedClassName are not public")
        void internalSeamsAreNotPublic() throws NoSuchMethodException {
            assertNotPublic(GeneratedInputProcessorDispatcher.class.getDeclaredMethod("resolve", Class.class));
            assertNotPublic(GeneratedInputProcessorDispatcher.class.getDeclaredMethod(
                    "walkUnknown",
                    Object.class,
                    InputTraversalContext.class,
                    InputLocation.class,
                    String.class,
                    Class.class));
            assertNotPublic(
                    GeneratedInputProcessorDispatcher.class.getDeclaredMethod("generatedClassName", Class.class));
        }

        @Test
        @DisplayName("nested ReflectiveContinuation is public")
        void reflectiveContinuationIsPublic() {
            assertTrue(
                    Modifier.isPublic(GeneratedInputProcessorDispatcher.ReflectiveContinuation.class.getModifiers()),
                    "ReflectiveContinuation is the extension seam for reflective fallback and must be public");
        }

        private void assertNotPublic(Method method) {
            assertFalse(
                    Modifier.isPublic(method.getModifiers()),
                    method.getName() + " must stay package-private — it is an internal dispatcher seam");
        }
    }

    @Nested
    @DisplayName("GeneratedSupport")
    class GeneratedSupportSurface {

        @Test
        @DisplayName("public members match the frozen ledger (codegen call targets only)")
        void generatedSupportSurface() {
            assertMethods(
                    GeneratedSupport.class,
                    "applyString(Object,InputTraversalContext,List,List,boolean,boolean,List,List,boolean,boolean,"
                            + "ChainResolver,InputLocation,String,String,Class)",
                    "applyStringCollection(Object,InputTraversalContext,List,List,boolean,boolean,List,List,"
                            + "boolean,boolean,ChainResolver,InputLocation,String,Class)",
                    "dispatchObjectCollection(Object,Class,EffectiveInputPolicies,InputLocation,ChainResolver,"
                            + "GeneratedInputProcessorDispatcher,InputTraversalContext,String,Class)",
                    "childPath(String,String)",
                    "applyDefault(Object,InputTraversalContext,List,List,boolean,boolean,List,List,boolean,boolean,"
                            + "ChainResolver,InputLocation,String,String,Class,Class,"
                            + "GeneratedInputProcessorDispatcher)");
            assertNoPublicFields(GeneratedSupport.class);
            assertNoPublicConstructors(GeneratedSupport.class);
        }
    }

    // --- Ledger helpers ---

    /**
     * Asserts the public declared methods of {@code type} are exactly {@code expected}.
     *
     * @param type     the type to inspect
     * @param expected the frozen {@code name(SimpleParamType,...)} signatures
     */
    private static void assertMethods(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(method -> signature(method.getName(), method))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(
                new TreeSet<>(List.of(expected)),
                actual,
                "public method surface of " + type.getSimpleName() + " changed");
    }

    /**
     * Asserts the public declared fields of {@code type} are exactly {@code expected}.
     *
     * @param type     the type to inspect
     * @param expected the frozen field names
     */
    private static void assertFields(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(
                new TreeSet<>(List.of(expected)),
                actual,
                "public field surface of " + type.getSimpleName() + " changed");
    }

    /**
     * Asserts the public declared constructors of {@code type} are exactly {@code expected}.
     *
     * @param type     the type to inspect
     * @param expected the frozen {@code <init>(SimpleParamType,...)} signatures
     */
    private static void assertConstructors(Class<?> type, String... expected) {
        Set<String> actual = Arrays.stream(type.getDeclaredConstructors())
                .filter(ctor -> Modifier.isPublic(ctor.getModifiers()))
                .filter(ctor -> !ctor.isSynthetic())
                .map(ctor -> signature("<init>", ctor))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(
                new TreeSet<>(List.of(expected)),
                actual,
                "public constructor surface of " + type.getSimpleName() + " changed");
    }

    private static void assertNoPublicFields(Class<?> type) {
        assertFields(type);
    }

    private static void assertNoPublicConstructors(Class<?> type) {
        assertConstructors(type);
    }

    /**
     * Renders {@code name(SimpleParamType,...)} for a method or constructor.
     *
     * @param name       the member name ({@code <init>} for constructors)
     * @param executable the method or constructor
     * @return the rendered signature
     */
    private static String signature(String name, Executable executable) {
        return name + "("
                + Arrays.stream(executable.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(","))
                + ")";
    }
}
