// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins descriptor annotation parity between the two JAX-RS engines: for every resource method, the
 * generated {@code _JaxRsDescriptor} from {@link JaxRsPipelineProcessor} and the reflective
 * {@link JaxRsRouteRegistrar#scanResource(Object)} expose equal
 * {@link ResourceMethodMeta#methodAnnotations()} and {@link ResourceMethodMeta#classAnnotations()},
 * compared by annotation type, member values and order.
 *
 * <p>The fixture is one type hierarchy, represented twice, following
 * {@link GeneratedSecurityPolicyParityTest}: as nested static types scanned by
 * {@link JaxRsRouteRegistrar#scanResource(Object)} (no annotation processor runs on this module's
 * test sources, so that side is reflective), and as public top-level sources compiled once through
 * {@link JaxRsPipelineProcessor}, whose generated {@code describe()} is called directly. Each nested
 * type sits directly above its source so the mirror is reviewable. Metas are paired by method name,
 * since neither engine's method order is a contract.
 *
 * <p>{@link TestParityMarker} labels every placement with a distinct value: a resource method, an
 * overriding method and the superclass method it overrides, an interface method, an inherited
 * interface {@code default} method, and the resource class, its superclass and its interface. The
 * values are distinct because both engines resolve through {@code AnnotationResolver}, which
 * deduplicates equal annotations. The marker is loaded by the test class loader on both sides, so
 * {@link Annotation#equals} can hold across engines.
 *
 * <p>Equality alone would let two engines that lost the same placement agree vacuously, so each
 * list's marker values are also pinned in {@code AnnotationResolver}'s documented traversal order:
 * the element's own annotations, then the superclass chain bottom-up, then the interfaces.
 */
class GeneratedDescriptorAnnotationParityTest {

    private static final String PKG = "dev.vertique.test.annparity";

    private static final String RESOURCE_FQN = PKG + ".MarkedResource";

    /** Expected {@link TestParityMarker} values of each resource method's {@code methodAnnotations()}. */
    private static final Map<String, List<String>> EXPECTED_METHOD_MARKERS = Map.of(
            "own", List.of("method"),
            "overridden", List.of("override", "superclass-method"),
            "fromInterface", List.of("interface-method"),
            "defaulted", List.of("interface-default"));

    /** Expected {@link TestParityMarker} values of every resource method's {@code classAnnotations()}. */
    private static final List<String> EXPECTED_CLASS_MARKERS = List.of("class", "superclass", "interface");

    private static List<ResourceMethodMeta> reflectiveMetas;

    private static List<ResourceMethodMeta> generatedMetas;

    // --- Fixture: each reflective nested type sits directly above its generated-side source ---

    private static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import dev.vertique.codegen.jaxrs.TestParityMarker;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                """ + body);
    }

    @TestParityMarker("interface")
    interface MarkedApi {
        @GET
        @Path("/from-interface")
        @TestParityMarker("interface-method")
        String fromInterface();

        @GET
        @Path("/defaulted")
        @TestParityMarker("interface-default")
        default String defaulted() {
            return "defaulted";
        }
    }

    private static JavaFileObject markedApiSource() {
        return src("MarkedApi", """
                @TestParityMarker("interface")
                public interface MarkedApi {
                    @GET
                    @Path("/from-interface")
                    @TestParityMarker("interface-method")
                    String fromInterface();

                    @GET
                    @Path("/defaulted")
                    @TestParityMarker("interface-default")
                    default String defaulted() {
                        return "defaulted";
                    }
                }
                """);
    }

    @TestParityMarker("superclass")
    abstract static class MarkedBase {
        @TestParityMarker("superclass-method")
        public String overridden() {
            return "superclass-method";
        }
    }

    private static JavaFileObject markedBaseSource() {
        return src("MarkedBase", """
                @TestParityMarker("superclass")
                public abstract class MarkedBase {
                    @TestParityMarker("superclass-method")
                    public String overridden() {
                        return "superclass-method";
                    }
                }
                """);
    }

    @Path("/annotation-parity")
    @TestParityMarker("class")
    static class MarkedResource extends MarkedBase implements MarkedApi {
        public MarkedResource() {}

        @GET
        @Path("/own")
        @TestParityMarker("method")
        public String own() {
            return "own";
        }

        @Override
        @GET
        @Path("/overridden")
        @TestParityMarker("override")
        public String overridden() {
            return "override";
        }

        @Override
        public String fromInterface() {
            return "fromInterface";
        }
    }

    private static JavaFileObject markedResourceSource() {
        return src("MarkedResource", """
                @Path("/annotation-parity")
                @TestParityMarker("class")
                public class MarkedResource extends MarkedBase implements MarkedApi {
                    public MarkedResource() {}

                    @GET
                    @Path("/own")
                    @TestParityMarker("method")
                    public String own() {
                        return "own";
                    }

                    @Override
                    @GET
                    @Path("/overridden")
                    @TestParityMarker("override")
                    public String overridden() {
                        return "override";
                    }

                    @Override
                    public String fromInterface() {
                        return "fromInterface";
                    }
                }
                """);
    }

    // --- Both engines describe the fixture once ---

    @BeforeAll
    static void describeTheFixtureWithBothEngines() throws Exception {
        reflectiveMetas = new JaxRsRouteRegistrar().scanResource(new MarkedResource());
        for (ResourceMethodMeta meta : reflectiveMetas) {
            assertNull(meta.executionPlan(), "the nested fixture must take the reflective path: " + meta);
        }

        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(), markedApiSource(), markedBaseSource(), markedResourceSource());
        result.assertSuccess();
        Object generatedInstance = result.generatedClassLoader()
                .loadClass(RESOURCE_FQN)
                .getDeclaredConstructor()
                .newInstance();
        generatedMetas = callDescribe(result, generatedInstance, RESOURCE_FQN + "_JaxRsDescriptor");
    }

    // --- Tests ---

    static Stream<String> resourceMethods() {
        return Stream.of("own", "overridden", "fromInterface", "defaulted");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceMethods")
    @DisplayName("generated methodAnnotations() equal the reflective engine's for every method placement")
    void methodAnnotationsMatchTheReflectiveEngine(String methodName) {
        Map<String, ResourceMethodMeta> reflective = byMethodName(reflectiveMetas);
        Map<String, ResourceMethodMeta> generated = byMethodName(generatedMetas);
        assertEquals(EXPECTED_METHOD_MARKERS.keySet(), reflective.keySet(), "methods the reflective engine describes");
        assertEquals(EXPECTED_METHOD_MARKERS.keySet(), generated.keySet(), "methods the generated engine describes");

        List<Annotation> reflectiveAnnotations = reflective.get(methodName).methodAnnotations();
        List<Annotation> generatedAnnotations = generated.get(methodName).methodAnnotations();

        assertEquals(
                reflectiveAnnotations,
                generatedAnnotations,
                methodName + "(): generated methodAnnotations() must equal the reflective ones");
        assertEquals(
                EXPECTED_METHOD_MARKERS.get(methodName),
                markerValues(generatedAnnotations),
                () -> methodName + "(): marker values, in order, of " + generatedAnnotations);
    }

    @Test
    @DisplayName(
            "generated classAnnotations() equal the reflective engine's for class, superclass and interface placements")
    void classAnnotationsMatchTheReflectiveEngine() {
        Map<String, ResourceMethodMeta> reflective = byMethodName(reflectiveMetas);
        Map<String, ResourceMethodMeta> generated = byMethodName(generatedMetas);
        assertEquals(EXPECTED_METHOD_MARKERS.keySet(), reflective.keySet(), "methods the reflective engine describes");
        assertEquals(EXPECTED_METHOD_MARKERS.keySet(), generated.keySet(), "methods the generated engine describes");

        for (Map.Entry<String, ResourceMethodMeta> pair : reflective.entrySet()) {
            String methodName = pair.getKey();
            List<Annotation> reflectiveAnnotations = pair.getValue().classAnnotations();
            List<Annotation> generatedAnnotations = generated.get(methodName).classAnnotations();

            assertEquals(
                    reflectiveAnnotations,
                    generatedAnnotations,
                    methodName + "(): generated classAnnotations() must equal the reflective ones");
            assertEquals(
                    EXPECTED_CLASS_MARKERS,
                    markerValues(generatedAnnotations),
                    () -> methodName + "(): class marker values, in order, of " + generatedAnnotations);
        }
    }

    // --- Helpers ---

    /**
     * Indexes one engine's metas by {@code method().getName()}, failing if two metas share a name,
     * so that pairing never depends on list order.
     */
    private static Map<String, ResourceMethodMeta> byMethodName(List<ResourceMethodMeta> metas) {
        Map<String, ResourceMethodMeta> byName = new LinkedHashMap<>();
        for (ResourceMethodMeta meta : metas) {
            String name = meta.method().getName();
            assertNull(byName.put(name, meta), () -> "more than one meta describes " + name + "(): " + metas);
        }
        return byName;
    }

    /** Extracts the {@link TestParityMarker} values of an annotation list, in list order. */
    private static List<String> markerValues(List<Annotation> annotations) {
        return annotations.stream()
                .filter(TestParityMarker.class::isInstance)
                .map(TestParityMarker.class::cast)
                .map(TestParityMarker::value)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, Object resourceInstance, String descriptorFqn) throws Exception {
        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method describe =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describe.invoke(descriptor, resourceInstance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());
    }
}
