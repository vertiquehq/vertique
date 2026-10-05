// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import dev.vertique.input.processing.apt.ElementInvocationPolicies;
import dev.vertique.input.processing.apt.ElementInvocationPolicies.ElementPolicyChains;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
            "GeneratedSupport",
            "InvocationPolicySource",
            "PolicyAxis",
            "InvocationPolicyResolver",
            "InvocationPolicyConflictException",
            "ReflectiveInvocationPolicies",
            "apt.ElementInvocationPolicies");

    /** Types that implement the engine but are deliberately not part of the surface. */
    private static final Set<String> INTERNAL_TYPES = Set.of(
            "DefaultInputObjectProcessor",
            "InputPolicyMetadata",
            "InputPolicyMetadataResolver",
            "OwnerTypeWalk",
            "TypeClassifier");

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
         * Lists the dotted, package-relative names of all top-level classes compiled into this
         * package and its subpackages (e.g. {@code "apt.ElementInvocationPolicies"}), excluding
         * nested/anonymous classes ({@code $}) and any {@code package-info}.
         *
         * <p>Package-aware since T018 (issue #379): the {@code apt} subpackage must be inventoried
         * too, so the scan walks the whole compiled-classes subtree under {@code
         * dev/vertique/input/processing} rather than listing one directory.
         *
         * @return the dotted package-relative names found in the module's compiled main-classes tree
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

            Path root = packageDir.toPath();
            Set<String> names;
            try (Stream<Path> paths = Files.walk(root)) {
                names = paths.filter(p -> p.toString().endsWith(".class"))
                        .map(root::relativize)
                        .map(Path::toString)
                        .map(rel -> rel.substring(0, rel.length() - ".class".length()))
                        .map(rel -> rel.replace(File.separatorChar, '.'))
                        .filter(name -> !name.contains("$"))
                        .filter(name -> !lastSegment(name).equals("package-info"))
                        .collect(Collectors.toCollection(TreeSet::new));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to walk compiled classes directory " + packageDir, e);
            }
            assertTrue(!names.isEmpty(), "no compiled classes found in " + packageDir);
            return names;
        }

        /**
         * Returns the last dot-separated segment of a dotted package-relative class name.
         *
         * @param dottedName e.g. {@code "apt.ElementInvocationPolicies"} or {@code "ChainResolver"}
         * @return the simple class name, e.g. {@code "ElementInvocationPolicies"}
         */
        private static String lastSegment(String dottedName) {
            int idx = dottedName.lastIndexOf('.');
            return idx < 0 ? dottedName : dottedName.substring(idx + 1);
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
                    "precomputeFieldNameResolution(Type,InputFieldNameResolver)",
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
                    "descend(Class,String,List,List,boolean,boolean,List,List,boolean,boolean)",
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
                            + "GeneratedInputProcessorDispatcher,InputTraversalContext,String)",
                    "fieldNameOwnerTypes()");
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

    @Nested
    @DisplayName("InvocationPolicySource")
    class InvocationPolicySourceSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void invocationPolicySourceSurface() {
            assertMethods(
                    InvocationPolicySource.class,
                    "additive()",
                    "additiveDeclaredAt()",
                    "skip()",
                    "skipDeclaredAt()",
                    "describe()",
                    "none()");
            assertNoPublicFields(InvocationPolicySource.class);
            assertNoPublicConstructors(InvocationPolicySource.class);
        }
    }

    @Nested
    @DisplayName("PolicyAxis")
    class PolicyAxisSurface {

        @Test
        @DisplayName("public members match the frozen ledger (incl. compiler-generated values()/valueOf(String))")
        void policyAxisSurface() {
            assertMethods(PolicyAxis.class, "additiveAnnotation()", "skipAnnotation()", "values()", "valueOf(String)");
            assertFields(PolicyAxis.class, "CANONICALIZE", "SANITIZE");
            assertNoPublicConstructors(PolicyAxis.class);
        }
    }

    @Nested
    @DisplayName("InvocationPolicyResolver")
    class InvocationPolicyResolverSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void invocationPolicyResolverSurface() {
            assertMethods(
                    InvocationPolicyResolver.class,
                    "resolveRouteChain(InvocationPolicySource,InvocationPolicySource,PolicyAxis)",
                    "resolveParameterChain(InvocationPolicySource,List,PolicyAxis)");
            assertNoPublicFields(InvocationPolicyResolver.class);
            assertNoPublicConstructors(InvocationPolicyResolver.class);
        }
    }

    @Nested
    @DisplayName("InvocationPolicyConflictException")
    class InvocationPolicyConflictExceptionSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void invocationPolicyConflictExceptionSurface() {
            assertMethods(InvocationPolicyConflictException.class, "axis()", "elementDescription()");
            assertNoPublicFields(InvocationPolicyConflictException.class);
            assertConstructors(InvocationPolicyConflictException.class, "<init>(PolicyAxis,String,String,String)");
        }
    }

    @Nested
    @DisplayName("ReflectiveInvocationPolicies")
    class ReflectiveInvocationPoliciesSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void reflectiveInvocationPoliciesSurface() {
            assertMethods(
                    ReflectiveInvocationPolicies.class,
                    "resolveRoute(Method,Class)",
                    "resolveParameter(Method,int,EffectiveInputPolicies)");
            assertNoPublicFields(ReflectiveInvocationPolicies.class);
            assertNoPublicConstructors(ReflectiveInvocationPolicies.class);
        }
    }

    @Nested
    @DisplayName("apt.ElementInvocationPolicies")
    class ElementInvocationPoliciesSurface {

        @Test
        @DisplayName("public members match the frozen ledger")
        void elementInvocationPoliciesSurface() {
            assertMethods(
                    ElementInvocationPolicies.class,
                    "resolveRoute(ExecutableElement,TypeElement)",
                    "resolveParameter(VariableElement,int,ExecutableElement,TypeElement,ElementPolicyChains)");
            assertNoPublicFields(ElementInvocationPolicies.class);
            assertConstructors(ElementInvocationPolicies.class, "<init>(Elements,Types)");
        }
    }

    @Nested
    @DisplayName("apt.ElementInvocationPolicies.ElementPolicyChains")
    class ElementPolicyChainsSurface {

        @Test
        @DisplayName("public members match the frozen ledger (components, NONE, record boilerplate)")
        void elementPolicyChainsSurface() {
            assertMethods(
                    ElementPolicyChains.class,
                    "canonicalizers()",
                    "sanitizers()",
                    "equals(Object)",
                    "hashCode()",
                    "toString()");
            assertFields(ElementPolicyChains.class, "NONE");
            assertConstructors(ElementPolicyChains.class, "<init>(List,List)");
        }
    }

    @Nested
    @DisplayName("apt package purity (AR-009)")
    class AptPackagePurity {

        /**
         * No {@code src/main/java} file outside the {@code apt} subpackage may reference the {@code
         * apt} subpackage or {@code javax.lang.model} — {@code dev.vertique.input.processing.apt} is
         * compile-time-only and never loaded by runtime code (contract "apt package in a runtime
         * artifact (AR-009)"; T018, issue #379).
         */
        @Test
        @DisplayName("no file outside apt/ references processing.apt or javax.lang.model")
        void noRuntimeFileReferencesTheAptPackage() throws IOException {
            Path srcMain = mainSourceRoot();
            try (Stream<Path> paths = Files.walk(srcMain)) {
                List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !isUnderAptPackage(srcMain, p))
                        .filter(AptPackagePurity::referencesAptOrLangModel)
                        .toList();
                assertTrue(
                        offenders.isEmpty(),
                        "dev.vertique.input.processing.apt / javax.lang.model must not be referenced "
                                + "outside the apt subpackage; found in: " + offenders);
            }
        }

        /**
         * Every import in {@code apt/*.java} is JDK-only ({@code java.}, {@code javax.lang.model.},
         * {@code javax.annotation.processing.}) or references this module's own base package — the
         * {@code apt} adapter requires only the JDK {@code java.compiler} module (contract, "apt
         * package in a runtime artifact (AR-009)"; T018, issue #379).
         */
        @Test
        @DisplayName("every import in apt/*.java is JDK-only or dev.vertique.input.processing")
        void aptImportsAreJdkOnlyOrOwnPackage() throws IOException {
            Path srcMain = mainSourceRoot();
            List<String> allowedPrefixes = List.of(
                    "java.", "javax.lang.model.", "javax.annotation.processing.", "dev.vertique.input.processing.");
            try (Stream<Path> paths = Files.walk(srcMain)) {
                List<String> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> isUnderAptPackage(srcMain, p))
                        .flatMap(p -> importsOf(p).stream()
                                .filter(imp -> allowedPrefixes.stream().noneMatch(imp::startsWith))
                                .map(imp -> p + " imports " + imp))
                        .toList();
                assertTrue(
                        offenders.isEmpty(),
                        "apt/*.java must import only JDK types or its own base package; " + "found: " + offenders);
            }
        }

        private Path mainSourceRoot() {
            Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
            assertTrue(Files.isDirectory(srcMain), "expected src/main/java to exist at " + srcMain);
            return srcMain;
        }

        private boolean isUnderAptPackage(Path srcMain, Path file) {
            Path relative = srcMain.relativize(file);
            return relative.getNameCount() > 1
                    && relative.getName(relative.getNameCount() - 2).toString().equals("apt");
        }

        private static boolean referencesAptOrLangModel(Path file) {
            String contents = readString(file);
            return contents.contains("processing.apt") || contents.contains("javax.lang.model");
        }

        private static List<String> importsOf(Path file) {
            return readString(file)
                    .lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length()))
                    .map(line -> line.endsWith(";") ? line.substring(0, line.length() - 1) : line)
                    .map(line -> line.startsWith("static ") ? line.substring("static ".length()) : line)
                    .toList();
        }

        private static String readString(Path file) {
            try {
                return Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read " + file, e);
            }
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
