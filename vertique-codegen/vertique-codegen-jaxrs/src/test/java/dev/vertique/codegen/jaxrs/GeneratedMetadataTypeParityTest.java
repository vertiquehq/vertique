// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compiles the same resource source twice, once without the JAX-RS processor (so the runtime
 * scanner reflects over it) and once with it (so the generated descriptor describes it), and
 * compares the type metadata of both paths. Each row pins the absolute expected metadata, so two
 * paths that are both wrong cannot agree.
 */
class GeneratedMetadataTypeParityTest {

    private static final String PKG = "dev.vertique.test.typarity";

    /** One parity shape: the sources of a resource and the metadata both paths must report. */
    record Row(String label, List<JavaFileObject> sources, List<String> expected) {
        @Override
        public String toString() {
            return label;
        }
    }

    static JavaFileObject src(String simpleName, String body) {
        return SourceFiles.inline(PKG + "." + simpleName, "package " + PKG + ";\n\n" + """
                import io.vertx.core.Future;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.HeaderParam;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.QueryParam;
                import java.util.List;
                import java.util.Map;

                """ + body);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                        new Row(
                                "inherited method-own variable bounded by a hierarchy variable",
                                List.of(src("Api", """
                                                public interface Api<T extends Number> {
                                                    @POST @Path("/body")
                                                    default <M extends T> String body(M value) { return ""; }
                                                    @GET @Path("/direct")
                                                    default <M extends T> M direct() { return null; }
                                                    @GET @Path("/fut")
                                                    default <M extends T> Future<M> fut() { return null; }
                                                    @GET @Path("/param")
                                                    default <M extends T> String param(@QueryParam("q") M q) { return ""; }
                                                }
                                                """), src("Leaf", """
                                                @Path("/x") public class Leaf implements Api<Integer> {}
                                                """)),
                                List.of(
                                        "body ret=java.lang.String future=false params=[BODY:java.lang.Integer/-]",
                                        "direct ret=java.lang.Integer future=false params=[]",
                                        "fut ret=java.lang.Integer future=true params=[]",
                                        "param ret=java.lang.String future=false params=[QUERY:java.lang.Integer/-]")),
                        new Row(
                                "own bounded method variable",
                                List.of(src("Leaf", """
                                                @Path("/x") public class Leaf {
                                                    @POST @Path("/body")
                                                    public <M extends Number> String body(M value) { return ""; }
                                                    @GET @Path("/direct")
                                                    public <M extends Number> M direct() { return null; }
                                                    @GET @Path("/fut")
                                                    public <M extends Number> Future<M> fut() { return null; }
                                                }
                                                """)),
                                List.of(
                                        "body ret=java.lang.String future=false params=[BODY:java.lang.Number/-]",
                                        "direct ret=java.lang.Number future=false params=[]",
                                        "fut ret=java.lang.Number future=true params=[]")),
                        new Row(
                                "own unbounded method variable stays unbound",
                                List.of(src("Leaf", """
                                                @Path("/x") public class Leaf {
                                                    @POST @Path("/body")
                                                    public <M> String body(M value) { return ""; }
                                                    @GET @Path("/direct")
                                                    public <M> M direct() { return null; }
                                                    @GET @Path("/fut")
                                                    public <M> Future<M> fut() { return null; }
                                                }
                                                """)),
                                List.of(
                                        "body ret=java.lang.String future=false params=[BODY:java.lang.Object/-]",
                                        "direct ret=java.lang.Object future=false params=[]",
                                        "fut ret=java.lang.Object future=true params=[]")))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("runtime scanning and generated code report the same types")
    void bothPathsReportTheExpectedTypes(Row row) throws Throwable {
        assertEquals(row.expected(), types(runtimeMetas(row.sources())), "runtime scanning");
        assertEquals(row.expected(), types(generatedMetas(row.sources())), "generated code");
    }

    // --- The two paths ---

    private static final class NoOpProcessor extends AbstractProcessor {
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return false;
        }
    }

    static List<ResourceMethodMeta> runtimeMetas(List<JavaFileObject> sources) throws Exception {
        var result = ProcessorTestHarness.run(new NoOpProcessor(), sources.toArray(JavaFileObject[]::new));
        result.assertSuccess();
        Object instance = result.generatedClassLoader()
                .loadClass(PKG + ".Leaf")
                .getDeclaredConstructor()
                .newInstance();
        return new JaxRsRouteRegistrar().scanResource(instance);
    }

    @SuppressWarnings("unchecked")
    static List<ResourceMethodMeta> generatedMetas(List<JavaFileObject> sources) throws Exception {
        var result = ProcessorTestHarness.run(new JaxRsPipelineProcessor(), sources.toArray(JavaFileObject[]::new));
        result.assertSuccess();
        Object instance = result.generatedClassLoader()
                .loadClass(PKG + ".Leaf")
                .getDeclaredConstructor()
                .newInstance();
        Class<?> descriptorClass = result.loadGeneratedClass(PKG + ".Leaf_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        var describe =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describe.invoke(descriptor, instance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());
    }

    // --- Normalization ---

    static List<String> types(List<ResourceMethodMeta> metas) {
        return metas.stream()
                .sorted(Comparator.comparing(m -> m.method().getName()))
                .map(m -> m.method().getName() + " ret=" + m.responseBodyType().getName() + " future="
                        + m.returnsFuture() + " params="
                        + m.params().stream()
                                .map(p -> p.source() + ":" + p.type().getName() + "/"
                                        + (p.componentType() == null
                                                ? "-"
                                                : p.componentType().getName()))
                                .collect(Collectors.toList()))
                .toList();
    }

    /** Renders a reflective type structurally, so the JDK's types and the scanner's compare by shape. */
    static String render(Type type) {
        if (type == null) {
            return "null";
        }
        if (type instanceof Class<?> cls) {
            return cls.getName();
        }
        if (type instanceof ParameterizedType pt) {
            return render(pt.getRawType()) + "<"
                    + Stream.of(pt.getActualTypeArguments())
                            .map(GeneratedMetadataTypeParityTest::render)
                            .collect(Collectors.joining(","))
                    + ">";
        }
        if (type instanceof GenericArrayType array) {
            return render(array.getGenericComponentType()) + "[]";
        }
        if (type instanceof WildcardType wildcard) {
            return wildcard.getLowerBounds().length > 0
                    ? "? super " + render(wildcard.getLowerBounds()[0])
                    : "? extends " + render(wildcard.getUpperBounds()[0]);
        }
        if (type instanceof TypeVariable<?> variable) {
            return "var " + variable.getName();
        }
        return type.getTypeName();
    }
}
