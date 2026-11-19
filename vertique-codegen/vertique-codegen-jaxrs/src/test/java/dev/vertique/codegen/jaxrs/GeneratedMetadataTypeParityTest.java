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
                                        "fut ret=java.lang.Object future=true params=[]")),
                        new Row(
                                "inherited array bound to a parameterized type",
                                arrayApi("List<String>"),
                                arrayExpected("java.util.List")),
                        new Row(
                                "inherited array bound to a parameterized map",
                                arrayApi("Map<String, Integer>"),
                                arrayExpected("java.util.Map")),
                        new Row(
                                "inherited array bound to a plain class",
                                arrayApi("String"),
                                arrayExpected("java.lang.String")),
                        new Row(
                                "raw leaf of an unbounded generic interface",
                                rawLeaf(""),
                                rawLeafExpected("java.lang.Object")),
                        new Row(
                                "raw leaf with a composite parameter type variable",
                                List.of(src("Dto", """
                                                @dev.vertique.rest.core.request.RequestParams
                                                public class Dto { @QueryParam("q") public String q; }
                                                """), src("Api", """
                                                public interface Api<T extends Dto> {
                                                    @POST @Path("/req")
                                                    default String req(T dto) { return ""; }
                                                    @POST @Path("/bean")
                                                    default String bean(@jakarta.ws.rs.BeanParam T dto) { return ""; }
                                                }
                                                """), src("Leaf", """
                                                @SuppressWarnings("rawtypes")
                                                @Path("/x") public class Leaf implements Api {}
                                                """)),
                                List.of(
                                        "bean ret=java.lang.String future=false params=[BEAN_PARAM:" + PKG + ".Dto/-]",
                                        "req ret=java.lang.String future=false params=[BEAN_PARAM:" + PKG + ".Dto/-]")),
                        new Row(
                                "raw leaf of a bounded generic interface",
                                rawLeaf(" extends Number"),
                                rawLeafExpected("java.lang.Number")),
                        new Row(
                                "leaf reaching the interface through a raw intermediate class",
                                intermediate("", "Mid<T> implements Api<String>", "Mid"),
                                rawLeafExpected("java.lang.Object")),
                        new Row(
                                "raw intermediate class forwarding its own variable",
                                intermediate(" extends Number", "Mid<T extends Number> implements Api<T>", "Mid"),
                                rawLeafExpected("java.lang.Number")),
                        new Row(
                                "leaf reaching the interface through a parameterized intermediate class",
                                intermediate("", "Mid<T> implements Api<String>", "Mid<Integer>"),
                                rawLeafExpected("java.lang.String")),
                        new Row(
                                "leaf reaching the interface through a non-generic intermediate class",
                                intermediate("", "Mid implements Api<String>", "Mid"),
                                rawLeafExpected("java.lang.String")))
                .map(Arguments::of);
    }

    private static List<JavaFileObject> arrayApi(String binding) {
        return List.of(
                src("Api", """
                        public interface Api<T> {
                            @POST @Path("/body")
                            default String body(T[] value) { return ""; }
                            @GET @Path("/direct")
                            default T[] direct() { return null; }
                            @GET @Path("/fut")
                            default Future<T[]> fut() { return null; }
                        }
                        """), src("Leaf", "@Path(\"/x\") public class Leaf implements Api<" + binding + "> {}\n"));
    }

    private static List<JavaFileObject> rawLeaf(String bound) {
        return rawLeafSources(bound, "@Path(\"/x\") public class Leaf implements Api {}", "");
    }

    /** A leaf that extends {@code leafSuper}, a class declared as {@code midDeclaration} implementing the API. */
    private static List<JavaFileObject> intermediate(String bound, String midDeclaration, String leafSuper) {
        return rawLeafSources(
                bound,
                "@Path(\"/x\") public class Leaf extends " + leafSuper + " {}",
                "public class " + midDeclaration + " {}");
    }

    private static List<JavaFileObject> rawLeafSources(String bound, String leaf, String mid) {
        List<JavaFileObject> sources = new ArrayList<>();
        if (!mid.isEmpty()) {
            sources.add(src("Mid", mid + "\n"));
        }
        sources.addAll(apiAndLeaf(bound, leaf));
        return sources;
    }

    private static List<JavaFileObject> apiAndLeaf(String bound, String leaf) {
        return List.of(
                src("Api", """
                        public interface Api<T%s> {
                            @POST @Path("/body")
                            default String body(T value) { return ""; }
                            @GET @Path("/direct")
                            default T direct() { return null; }
                            @GET @Path("/fut")
                            default Future<T> fut() { return null; }
                            @POST @Path("/list")
                            default String list(List<T> value) { return ""; }
                            @POST @Path("/arr")
                            default String arr(T[] value) { return ""; }
                            @GET @Path("/nested")
                            default Future<List<T>> nested() { return null; }
                            @SuppressWarnings("rawtypes")
                            @GET @Path("/rawfut")
                            default Future rawFut() { return null; }
                        }
                        """.formatted(bound)), src("Leaf", "@SuppressWarnings(\"rawtypes\")\n" + leaf + "\n"));
    }

    /** The metadata of the {@code Api} methods when its variable resolves to (or erases to) {@code erasure}. */
    private static List<String> rawLeafExpected(String erasure) {
        String array = "[L" + erasure + ";";
        return List.of(
                "arr ret=java.lang.String future=false params=[BODY:" + array + "/-]",
                "body ret=java.lang.String future=false params=[BODY:" + erasure + "/-]",
                "direct ret=" + erasure + " future=false params=[]",
                "fut ret=" + erasure + " future=true params=[]",
                "list ret=java.lang.String future=false params=[BODY:java.util.List/-]",
                "nested ret=java.util.List future=true params=[]",
                "rawFut ret=io.vertx.core.Future future=true params=[]");
    }

    private static List<String> arrayExpected(String component) {
        String array = "[L" + component + ";";
        return List.of(
                "body ret=java.lang.String future=false params=[BODY:" + array + "/-]",
                "direct ret=" + array + " future=false params=[]",
                "fut ret=" + array + " future=true params=[]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("runtime scanning and generated code report the same types")
    void bothPathsReportTheExpectedTypes(Row row) throws Throwable {
        assertEquals(row.expected(), types(runtimeMetas(row.sources())), "runtime scanning");
        assertEquals(row.expected(), types(generatedMetas(row.sources())), "generated code");
    }

    // --- ParamMeta.genericType ---

    static Stream<Arguments> genericTypeRows() {
        return Stream.of(
                        new Row(
                                "declared parameter types of every source",
                                List.of(src("Leaf", """
                                                @Path("/x") public class Leaf {
                                                    @POST @Path("/bodyList")
                                                    public String bodyList(List<String> v) { return ""; }
                                                    @POST @Path("/bodyString")
                                                    public String bodyString(String v) { return ""; }
                                                    @POST @Path("/bodyInt")
                                                    public String bodyInt(int v) { return ""; }
                                                    @POST @Path("/bodyArray")
                                                    public String bodyArray(List<String>[] v) { return ""; }
                                                    @POST @Path("/bodyWildcard")
                                                    public String bodyWildcard(List<? extends Number> v) { return ""; }
                                                    @POST @Path("/bodyNested")
                                                    public String bodyNested(Map<String, List<Integer>> v) { return ""; }
                                                    @GET @Path("/queryList")
                                                    public String queryList(@QueryParam("q") List<String> q) { return ""; }
                                                    @GET @Path("/queryString")
                                                    public String queryString(@QueryParam("q") String q) { return ""; }
                                                    @GET @Path("/queryArray")
                                                    public String queryArray(@QueryParam("q") String[] q) { return ""; }
                                                    @GET @Path("/header")
                                                    public String header(@HeaderParam("h") java.util.Optional<String> h) { return ""; }
                                                    @GET @Path("/cookie")
                                                    public String cookie(@jakarta.ws.rs.CookieParam("c") String c) { return ""; }
                                                    @GET @Path("/path/{id}")
                                                    public String path(@PathParam("id") String id) { return ""; }
                                                    @POST @Path("/form") @jakarta.ws.rs.Consumes("application/x-www-form-urlencoded")
                                                    public String form(@jakarta.ws.rs.FormParam("f") List<String> f) { return ""; }
                                                }
                                                """)),
                                List.of(
                                        "bodyArray=[java.util.List<java.lang.String>[]]",
                                        "bodyInt=[int]",
                                        "bodyList=[java.util.List<java.lang.String>]",
                                        "bodyNested=[java.util.Map<java.lang.String,java.util.List<java.lang.Integer>>]",
                                        "bodyString=[java.lang.String]",
                                        "bodyWildcard=[java.util.List<? extends java.lang.Number>]",
                                        "cookie=[java.lang.String]",
                                        "form=[null]",
                                        "header=[java.util.Optional<java.lang.String>]",
                                        "path=[null]",
                                        "queryArray=[java.lang.String[]]",
                                        "queryList=[java.util.List<java.lang.String>]",
                                        "queryString=[java.lang.String]")),
                        new Row(
                                "inherited parameter types resolved against the resource class",
                                List.of(src("Api", """
                                                public interface Api<T> {
                                                    @POST @Path("/body")
                                                    default String body(List<T> v) { return ""; }
                                                    @GET @Path("/query")
                                                    default String query(@QueryParam("q") T q) { return ""; }
                                                    @POST @Path("/array")
                                                    default String array(T[] v) { return ""; }
                                                }
                                                """), src("Leaf", """
                                                @Path("/x") public class Leaf implements Api<Integer> {}
                                                """)),
                                List.of(
                                        "array=[java.lang.Integer[]]",
                                        "body=[java.util.List<java.lang.Integer>]",
                                        "query=[java.lang.Integer]")),
                        new Row(
                                "unresolved type variables of a raw leaf and of a method",
                                List.of(src("Api", """
                                                public interface Api<T> {
                                                    @POST @Path("/body")
                                                    default String body(T v) { return ""; }
                                                    @POST @Path("/list")
                                                    default String list(List<T> v) { return ""; }
                                                    @POST @Path("/own")
                                                    default <M> String own(M v) { return ""; }
                                                }
                                                """), src("Leaf", """
                                                @SuppressWarnings("rawtypes")
                                                @Path("/x") public class Leaf implements Api {}
                                                """)),
                                List.of("body=[var T]", "list=[java.util.List<var T>]", "own=[var M]")),
                        new Row(
                                "a parameterized type that the generated class cannot name",
                                List.of(src("Leaf", """
                                                @Path("/x") public class Leaf {
                                                    private static class Hidden {}
                                                    @POST @Path("/body")
                                                    String body(List<Hidden> v) { return ""; }
                                                }
                                                """)),
                                List.of("body=[java.util.List<" + PKG + ".Leaf$Hidden>]")))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("genericTypeRows")
    @DisplayName("runtime scanning and generated code report the same generic parameter types")
    void bothPathsReportTheExpectedGenericTypes(Row row) throws Throwable {
        assertEquals(row.expected(), genericTypes(runtimeMetas(row.sources())), "runtime scanning");
        assertEquals(row.expected(), genericTypes(generatedMetas(row.sources())), "generated code");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("genericTypeRows")
    @DisplayName("the generated execution plan reports the generic types the descriptor does")
    void executionPlanReportsTheGenericTypesOfTheDescriptor(Row row) throws Throwable {
        int compared = 0;
        for (ResourceMethodMeta meta : generatedMetas(row.sources())) {
            if (meta.executionPlan() == null) {
                continue;
            }
            for (int i = 0; i < meta.params().size(); i++) {
                java.lang.reflect.Field field;
                try {
                    field = meta.executionPlan().getClass().getDeclaredField("P" + i);
                } catch (NoSuchFieldException noParamMeta) {
                    continue;
                }
                field.setAccessible(true);
                var planned = (ResourceMethodMeta.ParamMeta) field.get(null);
                assertEquals(
                        render(meta.params().get(i).genericType()),
                        render(planned.genericType()),
                        meta.method().getName() + " parameter " + i);
                compared++;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
                compared > 0 || row.label().contains("cannot name"), "the plan carried no parameter metadata");
    }

    static List<String> genericTypes(List<ResourceMethodMeta> metas) {
        return metas.stream()
                .sorted(Comparator.comparing(m -> m.method().getName()))
                .map(m -> m.method().getName() + "="
                        + m.params().stream().map(p -> render(p.genericType())).collect(Collectors.toList()))
                .toList();
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
            return cls.getTypeName();
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
