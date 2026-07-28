// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArrayFqns} — the shared resolver for codegen-emitted type names, including
 * array types in Java <em>source</em> form.
 *
 * <p>Two properties are pinned here. First, the source-form array resolution itself (single, multi
 * dimensional, primitive-component, and nested-component arrays), which {@code Class.forName} alone
 * cannot do. Second, the {@code initialize} flag, which exists because the two runtime callers
 * differ: {@link GeneratedJaxRsReflectiveAnnotations} must not run a user type's static initializer
 * while merely naming parameter types for a method lookup, whereas
 * {@link GeneratedJaxRsDescriptorSupport} has always resolved with initialization enabled. An
 * extraction that unified the flag would silently change one of them.
 */
class ArrayFqnsTest {

    private final ClassLoader cl = getClass().getClassLoader();

    // --- Scalar and primitive resolution ---

    @Nested
    @DisplayName("scalar resolution")
    class ScalarResolution {

        @Test
        @DisplayName("resolves a scalar reference type by binary FQN")
        void resolvesScalarReference() throws ClassNotFoundException {
            assertEquals(String.class, ArrayFqns.resolve("java.lang.String", cl, true));
        }

        @Test
        @DisplayName("resolves a primitive type name to its Class constant")
        void resolvesPrimitiveName() throws ClassNotFoundException {
            assertEquals(int.class, ArrayFqns.resolve("int", cl, true));
            assertEquals(boolean.class, ArrayFqns.resolve("boolean", cl, false));
            assertEquals(byte.class, ArrayFqns.resolve("byte", cl, true));
            assertEquals(char.class, ArrayFqns.resolve("char", cl, true));
            assertEquals(short.class, ArrayFqns.resolve("short", cl, true));
            assertEquals(long.class, ArrayFqns.resolve("long", cl, true));
            assertEquals(float.class, ArrayFqns.resolve("float", cl, true));
            assertEquals(double.class, ArrayFqns.resolve("double", cl, true));
            assertEquals(void.class, ArrayFqns.resolve("void", cl, true));
        }

        @Test
        @DisplayName("resolves a nested reference type by its binary (Outer$Inner) name")
        void resolvesNestedReference() throws ClassNotFoundException {
            assertEquals(NestedProbe.class, ArrayFqns.resolve(NestedProbe.class.getName(), cl, true));
        }

        @Test
        @DisplayName("propagates ClassNotFoundException for an unknown reference type")
        void propagatesClassNotFound() {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> ArrayFqns.resolve("dev.vertique.nonexistent.DoesNotExist", cl, true));
        }
    }

    // --- Array resolution (the defect this class fixes) ---

    @Nested
    @DisplayName("array resolution")
    class ArrayResolution {

        @Test
        @DisplayName("resolves a one-dimensional object array from source form")
        void resolvesObjectArray() throws ClassNotFoundException {
            assertEquals(String[].class, ArrayFqns.resolve("java.lang.String[]", cl, true));
        }

        @Test
        @DisplayName("resolves a primitive array (byte[]) from source form")
        void resolvesPrimitiveArray() throws ClassNotFoundException {
            assertEquals(byte[].class, ArrayFqns.resolve("byte[]", cl, true));
            assertEquals(int[].class, ArrayFqns.resolve("int[]", cl, false));
            assertEquals(char[].class, ArrayFqns.resolve("char[]", cl, true));
        }

        @Test
        @DisplayName("resolves a multi-dimensional array from source form")
        void resolvesMultiDimensionalArray() throws ClassNotFoundException {
            assertEquals(String[][].class, ArrayFqns.resolve("java.lang.String[][]", cl, true));
            assertEquals(String[][][].class, ArrayFqns.resolve("java.lang.String[][][]", cl, true));
            assertEquals(int[][].class, ArrayFqns.resolve("int[][]", cl, true));
        }

        @Test
        @DisplayName("resolves an array whose component is a nested type named in binary form")
        void resolvesNestedComponentArray() throws ClassNotFoundException {
            assertEquals(NestedProbe[].class, ArrayFqns.resolve(NestedProbe.class.getName() + "[]", cl, true));
            assertEquals(NestedProbe[][].class, ArrayFqns.resolve(NestedProbe.class.getName() + "[][]", cl, true));
        }

        @Test
        @DisplayName("propagates ClassNotFoundException when an array's component type is unknown")
        void propagatesClassNotFoundForComponent() {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> ArrayFqns.resolve("dev.vertique.nonexistent.DoesNotExist[]", cl, true));
        }
    }

    // --- Class-initialization semantics (plan finding F3) ---

    @Nested
    @DisplayName("initialization semantics")
    class InitializationSemantics {

        @Test
        @DisplayName("initialize=false does not run the base type's static initializer; initialize=true does")
        void arrayFqns_referenceBase_initializationSemanticsPreserved() throws ClassNotFoundException {
            // The flag lives on a separate holder so that observing it does not itself initialize
            // the probe. Class literals (ScalarInitProbe.class) do not trigger initialization
            // either, so the only thing that can flip the flag is ArrayFqns.resolve.
            assertFalse(InitFlags.scalarProbeInitialized, "probe must not be initialized before the test resolves it");

            Class<?> withoutInit = ArrayFqns.resolve(ScalarInitProbe.class.getName(), cl, false);

            assertEquals(ScalarInitProbe.class, withoutInit);
            assertFalse(
                    InitFlags.scalarProbeInitialized,
                    "initialize=false must resolve the class without running its static initializer — "
                            + "GeneratedJaxRsReflectiveAnnotations relies on this");

            Class<?> withInit = ArrayFqns.resolve(ScalarInitProbe.class.getName(), cl, true);

            assertEquals(ScalarInitProbe.class, withInit);
            assertTrue(
                    InitFlags.scalarProbeInitialized,
                    "initialize=true must run the static initializer — GeneratedJaxRsDescriptorSupport "
                            + "has always resolved with initialization enabled");
        }

        @Test
        @DisplayName("array resolution with initialize=false leaves the component type uninitialized")
        void arrayComponent_withoutInit_notInitialized() throws ClassNotFoundException {
            assertFalse(InitFlags.arrayProbeInitialized, "probe must not be initialized before the test resolves it");

            Class<?> resolved = ArrayFqns.resolve(ArrayInitProbe.class.getName() + "[]", cl, false);

            assertEquals(ArrayInitProbe[].class, resolved);
            assertFalse(
                    InitFlags.arrayProbeInitialized,
                    "initialize=false must not run the array component type's static initializer");
        }

        @Test
        @DisplayName("array resolution with initialize=true DOES initialize the component type")
        void arrayComponent_withInit_initializesComponent() throws ClassNotFoundException {
            // Observed behavior, documented rather than legislated: Array.newInstance itself never
            // initializes a component type, but ArrayFqns resolves the base FIRST via
            // Class.forName(base, initialize, cl). With initialize=true that base resolution runs
            // the component's static initializer, so the array's component type IS initialized.
            // A dedicated probe is used so this test is independent of execution order.
            assertFalse(
                    InitFlags.initTrueArrayProbeInitialized,
                    "probe must not be initialized before the test resolves it");

            Class<?> resolved = ArrayFqns.resolve(InitTrueArrayProbe.class.getName() + "[]", cl, true);

            assertEquals(InitTrueArrayProbe[].class, resolved);
            assertTrue(
                    InitFlags.initTrueArrayProbeInitialized,
                    "initialize=true resolves the array's base component type with Class.forName(.., true, ..), "
                            + "which runs the component's static initializer");
        }
    }

    // --- Test fixtures ---

    /**
     * Holder for the probes' initialization flags. Kept separate from the probes so that reading a
     * flag initializes only this holder, never the probe whose initialization is being observed.
     */
    static final class InitFlags {

        /** Set by {@link ScalarInitProbe}'s static initializer. */
        static boolean scalarProbeInitialized;

        /** Set by {@link ArrayInitProbe}'s static initializer. */
        static boolean arrayProbeInitialized;

        /** Set by {@link InitTrueArrayProbe}'s static initializer. */
        static boolean initTrueArrayProbeInitialized;

        private InitFlags() {}
    }

    /** Probe whose static initializer is observable via {@link InitFlags#scalarProbeInitialized}. */
    static final class ScalarInitProbe {

        static {
            InitFlags.scalarProbeInitialized = true;
        }

        private ScalarInitProbe() {}
    }

    /** Probe whose static initializer is observable via {@link InitFlags#arrayProbeInitialized}. */
    static final class ArrayInitProbe {

        static {
            InitFlags.arrayProbeInitialized = true;
        }

        private ArrayInitProbe() {}
    }

    /**
     * Probe whose static initializer is observable via
     * {@link InitFlags#initTrueArrayProbeInitialized}.
     */
    static final class InitTrueArrayProbe {

        static {
            InitFlags.initTrueArrayProbeInitialized = true;
        }

        private InitTrueArrayProbe() {}
    }

    /** Nested type used to prove binary-name ({@code Outer$Inner}) base resolution. */
    static final class NestedProbe {

        private NestedProbe() {}
    }
}
