// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.jaxrs.stubs.PolicyTestStubs;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * APT compile-tests for {@link JaxRsPipelineProcessor} bean-param model emission (CG-010 step 4c).
 *
 * <p>Each test compiles a small fixture through the pipeline processor, loads the generated
 * {@code _BeanParamModel} class via the compilation's classloader, instantiates it, and calls
 * {@code fields()} to verify the produced {@link BeanParamFieldMeta} list.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Record components in declaration order with various {@code JaxRsParamSource} values.</li>
 *   <li>Class hierarchy with subclass-wins field-hiding dedup (closed adjacent defect ex-#30
 *       alignment).</li>
 *   <li>{@code @DefaultValue} propagation on record and class fields.</li>
 *   <li>Each {@code JaxRsParamSource} value covered: PATH, QUERY, HEADER, COOKIE, FORM.</li>
 * </ul>
 */
class BeanParamModelEmitterTest {

    // --- Helper: load and call bean-param model ---

    /**
     * Loads the generated {@code _BeanParamModel}, instantiates it, and calls {@code fields()}.
     *
     * @param result         the compilation result
     * @param modelFqn       FQN of the generated model class
     * @return the list of {@link BeanParamFieldMeta} from {@code fields()}
     * @throws Exception if any reflection step fails
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<BeanParamFieldMeta> callFields(ProcessorTestHarness.Result result, String modelFqn)
            throws Exception {
        Class<?> modelClass = result.loadGeneratedClass(modelFqn);
        Object model = modelClass.getDeclaredConstructor().newInstance();
        Method fieldsMethod = modelClass.getMethod("fields");
        return (List<BeanParamFieldMeta>) fieldsMethod.invoke(model);
    }

    // --- Helper: build a fixture that has a @BeanParam resource and one bean-param type ---

    /**
     * Compiles a fixture that contains both a resource (with a {@code @BeanParam} parameter) and
     * the specified bean type. The pipeline processor discovers and emits a model for the bean.
     *
     * @param beanFqn     the fully-qualified name for the bean class fixture
     * @param beanSource  the bean class fixture source
     * @return the compilation result
     */
    private static ProcessorTestHarness.Result compileBeanFixture(String beanFqn, String beanSource) {
        // Resource fixture that triggers @BeanParam discovery
        String resourceFqn = beanFqn.substring(0, beanFqn.lastIndexOf('.')) + ".BpResource";
        String resourceSource = """
                package %s;

                import jakarta.ws.rs.BeanParam;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/bp")
                public class BpResource {
                    public BpResource() {}

                    @GET
                    public String get(@BeanParam %s params) { return ""; }
                }
                """.formatted(
                beanFqn.substring(0, beanFqn.lastIndexOf('.')), beanFqn.substring(beanFqn.lastIndexOf('.') + 1));

        return ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline(resourceFqn, resourceSource),
                SourceFiles.inline(beanFqn, beanSource));
    }

    // --- Record components ---

    @Nested
    @DisplayName("record components in declaration order")
    class RecordComponents {

        @Test
        @DisplayName("record with PATH + QUERY — emitted in declaration order")
        void record_pathAndQuery_declarationOrder() throws Exception {
            // Use a resource with the matching path placeholder to avoid PathParam validation error
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.SearchParams", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.QueryParam;

                            public record SearchParams(
                                    @PathParam("id") String id,
                                    @QueryParam("q") String query) {}
                            """),
                    SourceFiles.inline("dev.vertique.test.BpResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp/{id}")
                            public class BpResource {
                                public BpResource() {}

                                @GET
                                public String get(@BeanParam SearchParams params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.SearchParams_BeanParamModel");

            assertEquals(2, fields.size(), "Both record components should appear");
            assertEquals("id", fields.get(0).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.PATH, fields.get(0).meta().source());
            assertEquals("id", fields.get(0).meta().name());

            assertEquals("query", fields.get(1).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.QUERY, fields.get(1).meta().source());
            assertEquals("q", fields.get(1).meta().name());
        }

        @Test
        @DisplayName("record with @DefaultValue — propagated to ParamMeta.defaultValue")
        void record_defaultValue_propagated() throws Exception {
            var result = compileBeanFixture("dev.vertique.test.FilterParams", """
                    package dev.vertique.test;

                    import jakarta.ws.rs.DefaultValue;
                    import jakarta.ws.rs.QueryParam;

                    public record FilterParams(
                            @QueryParam("limit") @DefaultValue("20") String limit) {}
                    """);

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.FilterParams_BeanParamModel");

            assertEquals(1, fields.size());
            assertEquals("20", fields.get(0).meta().defaultValue(), "@DefaultValue must be propagated");
        }

        @Test
        @DisplayName("record with HEADER + COOKIE — all sources covered")
        void record_headerAndCookie() throws Exception {
            var result = compileBeanFixture("dev.vertique.test.AuthParams", """
                    package dev.vertique.test;

                    import jakarta.ws.rs.CookieParam;
                    import jakarta.ws.rs.HeaderParam;

                    public record AuthParams(
                            @HeaderParam("Authorization") String authHeader,
                            @CookieParam("session") String sessionCookie) {}
                    """);

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.AuthParams_BeanParamModel");

            assertEquals(2, fields.size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.HEADER, fields.get(0).meta().source());
            assertEquals("Authorization", fields.get(0).meta().name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.COOKIE, fields.get(1).meta().source());
            assertEquals("session", fields.get(1).meta().name());
        }

        @Test
        @DisplayName("record with FORM — form source covered")
        void record_formParam() throws Exception {
            var result = compileBeanFixture("dev.vertique.test.FormData", """
                    package dev.vertique.test;

                    import jakarta.ws.rs.FormParam;

                    public record FormData(@FormParam("name") String name) {}
                    """);

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.FormData_BeanParamModel");

            assertEquals(1, fields.size());
            assertEquals(
                    ResourceMethodMeta.ParamSource.FORM, fields.get(0).meta().source());
            assertEquals("name", fields.get(0).meta().name());
        }
    }

    // --- Class hierarchy with field hiding ---

    @Nested
    @DisplayName("class hierarchy — subclass-wins field-hiding dedup")
    class ClassHierarchyFieldHiding {

        @Test
        @DisplayName("subclass field shadows superclass field of same name — subclass wins")
        void subclassFieldSuperclassField_subclassWins() throws Exception {
            // Superclass has @QueryParam("q") on field `q`, subclass also has `q` with @QueryParam("q_sub")
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.BaseParams", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class BaseParams {
                                @QueryParam("q_base")
                                public String q;
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.SubParams", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class SubParams extends BaseParams {
                                @QueryParam("q_sub")
                                public String q;  // Hides superclass field
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.BpResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp")
                            public class BpResource {
                                public BpResource() {}

                                @GET
                                public String get(@BeanParam SubParams params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.SubParams_BeanParamModel");

            assertEquals(1, fields.size(), "Only one entry for field 'q' — subclass-wins dedup");
            assertEquals("q_sub", fields.get(0).meta().name(), "Subclass param name must win");
        }

        @Test
        @DisplayName("disjoint fields on subclass and superclass — both appear, subclass first")
        void disjointFields_bothPresent_subclassFirst() throws Exception {
            // Use only QUERY params to avoid PathParam validation mismatch
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.BaseP", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class BaseP {
                                @QueryParam("base_q")
                                public String baseField;
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.SubP", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class SubP extends BaseP {
                                @QueryParam("sub_q")
                                public String subField;
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.BpResource2", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp2")
                            public class BpResource2 {
                                public BpResource2() {}

                                @GET
                                public String get(@BeanParam SubP params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.SubP_BeanParamModel");

            assertEquals(2, fields.size());
            // Subclass field first (subField), then superclass field (baseField)
            assertEquals("subField", fields.get(0).name(), "Subclass field should appear first");
            assertEquals(
                    ResourceMethodMeta.ParamSource.QUERY, fields.get(0).meta().source());
            assertEquals("sub_q", fields.get(0).meta().name());

            assertEquals("baseField", fields.get(1).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.QUERY, fields.get(1).meta().source());
            assertEquals("base_q", fields.get(1).meta().name());
        }

        @Test
        @DisplayName("@DefaultValue on class field — propagated to ParamMeta")
        void classField_defaultValue_propagated() throws Exception {
            var result = compileBeanFixture("dev.vertique.test.ClassBeanParams", """
                    package dev.vertique.test;

                    import jakarta.ws.rs.DefaultValue;
                    import jakarta.ws.rs.QueryParam;

                    public class ClassBeanParams {
                        @QueryParam("size")
                        @DefaultValue("10")
                        public String size;
                    }
                    """);

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.ClassBeanParams_BeanParamModel");

            assertEquals(1, fields.size());
            assertEquals(
                    "10", fields.get(0).meta().defaultValue(), "@DefaultValue must be propagated for class fields");
        }
    }

    // --- Type resolution ---

    @Nested
    @DisplayName("type resolution — field types resolve correctly")
    class TypeResolution {

        @Test
        @DisplayName("int field type — resolves to int.class")
        void intFieldType_resolvesCorrectly() throws Exception {
            var result = compileBeanFixture("dev.vertique.test.CountParams", """
                    package dev.vertique.test;

                    import jakarta.ws.rs.QueryParam;

                    public record CountParams(@QueryParam("count") int count) {}
                    """);

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.CountParams_BeanParamModel");

            assertEquals(1, fields.size());
            assertEquals(int.class, fields.get(0).meta().type(), "int field should resolve to int.class");
        }

        @Test
        @DisplayName("application-defined enum field — TYPE_n resolves via companion classloader (not bootstrap)")
        void applicationEnumFieldType_resolvesViaCompanionClassloader() throws Exception {
            // The TYPE_n constant in a generated _BeanParamModel resolves the field's declared type
            // via Class.forName(fqn, true, classLoader). If classLoader is bootstrap (the value of
            // Thread.class.getClassLoader()), only JDK types are visible — any application-defined
            // type (enums, domain classes) throws ClassNotFoundException → IllegalStateException
            // → ExceptionInInitializerError on first request to a route owning the bean. This test
            // pins the fix that switched the resolver helper to use SELF_CL (the companion's own
            // classloader). Before the fix, this test fails at companion class-init.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.SortDir", """
                            package dev.vertique.test;

                            public enum SortDir { ASC, DESC }
                            """),
                    SourceFiles.inline("dev.vertique.test.AppEnumBean", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class AppEnumBean {
                                @QueryParam("sort")
                                public SortDir sort;
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.BpResource5", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp5")
                            public class BpResource5 {
                                public BpResource5() {}

                                @GET
                                public String get(@BeanParam AppEnumBean params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Class-init runs the TYPE_n initializer, which calls resolveClass("dev.vertique.test.SortDir").
            // If resolveClass uses bootstrap, this throws; with SELF_CL it resolves to the enum class.
            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.AppEnumBean_BeanParamModel");

            assertEquals(1, fields.size());
            assertEquals("sort", fields.get(0).name());
            Class<?> fieldType = fields.get(0).meta().type();
            assertEquals(
                    "dev.vertique.test.SortDir",
                    fieldType.getName(),
                    "Application-defined enum type must resolve via the companion's own classloader");
            assertTrue(fieldType.isEnum(), "Resolved type must be the enum class");
        }
    }

    // --- Input-policy annotation emission (HIGH 1 parity fix) ---

    /**
     * FQNs and simple names of the stub canonicalizer/sanitizer types used in inline fixtures.
     * Uses canonical form (dotted nested) so javac import directives work.
     */
    private static final String STUB_CANON_FQN = PolicyTestStubs.StubCanonicalizer.class.getCanonicalName();

    private static final String STUB_SANIT_FQN = PolicyTestStubs.StubSanitizer.class.getCanonicalName();
    private static final String STUB_CANON_SIMPLE = PolicyTestStubs.StubCanonicalizer.class.getSimpleName();
    private static final String STUB_SANIT_SIMPLE = PolicyTestStubs.StubSanitizer.class.getSimpleName();

    @Nested
    @DisplayName("input-policy annotations — field annotations are emitted and non-null (HIGH 1 parity fix)")
    class InputPolicyAnnotationEmission {

        @Test
        @DisplayName(
                "class bean field with @Canonicalize — meta().annotations() is non-null and contains the annotation")
        void classBeanField_canonicalize_annotationsNonNull() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.CanonBean", String.format("""
                            package dev.vertique.test;

                            import %s;
                            import dev.vertique.core.sanitization.Canonicalize;
                            import jakarta.ws.rs.QueryParam;

                            public class CanonBean {
                                @QueryParam("name")
                                @Canonicalize(%s.class)
                                public String name;
                            }
                            """, STUB_CANON_FQN, STUB_CANON_SIMPLE)),
                    SourceFiles.inline("dev.vertique.test.BpResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp")
                            public class BpResource {
                                public BpResource() {}

                                @GET
                                public String get(@BeanParam CanonBean params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.CanonBean_BeanParamModel");

            assertEquals(1, fields.size(), "One field expected");
            Annotation[] annotations = fields.get(0).meta().annotationsLazy().get();
            assertNotNull(annotations, "annotations() must not be null — per-field input policies require it");
            boolean hasCanon = Arrays.stream(annotations)
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("Canonicalize"));
            assertTrue(hasCanon, "annotations() must contain @Canonicalize declared on the field");
        }

        @Test
        @DisplayName("class bean field with @Sanitize — meta().annotations() contains @Sanitize")
        void classBeanField_sanitize_annotationPresent() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.SanitBean", String.format("""
                            package dev.vertique.test;

                            import %s;
                            import dev.vertique.core.sanitization.Sanitize;
                            import jakarta.ws.rs.QueryParam;

                            public class SanitBean {
                                @QueryParam("tag")
                                @Sanitize(%s.class)
                                public String tag;
                            }
                            """, STUB_SANIT_FQN, STUB_SANIT_SIMPLE)),
                    SourceFiles.inline("dev.vertique.test.BpResource2", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp2")
                            public class BpResource2 {
                                public BpResource2() {}

                                @GET
                                public String get(@BeanParam SanitBean params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.SanitBean_BeanParamModel");

            assertEquals(1, fields.size());
            Annotation[] annotations = fields.get(0).meta().annotationsLazy().get();
            assertNotNull(annotations, "annotations() must not be null");
            boolean hasSanit = Arrays.stream(annotations)
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("Sanitize"));
            assertTrue(hasSanit, "annotations() must contain @Sanitize declared on the field");
        }

        @Test
        @DisplayName("record component with @Canonicalize — meta().annotations() contains @Canonicalize")
        void recordComponent_canonicalize_annotationPresent() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.CanonRecord", String.format("""
                            package dev.vertique.test;

                            import %s;
                            import dev.vertique.core.sanitization.Canonicalize;
                            import jakarta.ws.rs.QueryParam;

                            public record CanonRecord(@QueryParam("q") @Canonicalize(%s.class) String q) {}
                            """, STUB_CANON_FQN, STUB_CANON_SIMPLE)),
                    SourceFiles.inline("dev.vertique.test.BpResource3", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp3")
                            public class BpResource3 {
                                public BpResource3() {}

                                @GET
                                public String get(@BeanParam CanonRecord params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.CanonRecord_BeanParamModel");

            assertEquals(1, fields.size());
            Annotation[] annotations = fields.get(0).meta().annotationsLazy().get();
            assertNotNull(annotations, "annotations() must not be null for record components");
            boolean hasCanon = Arrays.stream(annotations)
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("Canonicalize"));
            assertTrue(hasCanon, "annotations() must contain @Canonicalize on the record component accessor");
        }

        @Test
        @DisplayName("field with @SkipCanonicalization — meta().annotations() contains @SkipCanonicalization")
        void classBeanField_skipCanonicalization_annotationPresent() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline(
                            "dev.vertique.test.SkipCanonBean", String.format("""
                            package dev.vertique.test;

                            import %s;
                            import dev.vertique.core.sanitization.Canonicalize;
                            import dev.vertique.core.sanitization.SkipCanonicalization;
                            import jakarta.ws.rs.QueryParam;

                            @Canonicalize(%s.class)
                            public class SkipCanonBean {
                                @QueryParam("name")
                                @SkipCanonicalization
                                public String name;
                            }
                            """, STUB_CANON_FQN, STUB_CANON_SIMPLE)),
                    SourceFiles.inline("dev.vertique.test.BpResource4", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/bp4")
                            public class BpResource4 {
                                public BpResource4() {}

                                @GET
                                public String get(@BeanParam SkipCanonBean params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.SkipCanonBean_BeanParamModel");

            assertEquals(1, fields.size());
            Annotation[] annotations = fields.get(0).meta().annotationsLazy().get();
            assertNotNull(annotations, "annotations() must not be null");
            boolean hasSkip = Arrays.stream(annotations)
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("SkipCanonicalization"));
            assertTrue(hasSkip, "annotations() must contain @SkipCanonicalization declared on the field");
        }
    }

    // --- Nested bean class — binary-name vs source-name parity (round-5 review) ---

    @Nested
    @DisplayName("nested bean class — companion class-init resolves binary FQN (Outer$Inner)")
    class NestedBeanBinaryName {

        @Test
        @DisplayName("@BeanParam on a nested static class — fields() loads without ClassNotFoundException")
        void nestedStaticBean_classInitResolvesBinaryName() throws Exception {
            // The bean type is a nested static class. The emitted ANN_n constants reference the
            // bean class by FQN string; resolveClass uses Class.forName(fqn, true, cl) which requires
            // the BINARY name (Outer$Inner), NOT the source-form qualified name (Outer.Inner) that
            // TypeElement.getQualifiedName() returns. Before the round-5 fix, class-init of the
            // generated companion threw ExceptionInInitializerError wrapping ClassNotFoundException
            // for any nested @BeanParam type. This test pins the regression.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.Outer", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.QueryParam;

                            public class Outer {
                                public static class Inner {
                                    @QueryParam("q")
                                    public String q;
                                }
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.NestedBpResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.BeanParam;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/nested")
                            public class NestedBpResource {
                                public NestedBpResource() {}

                                @GET
                                public String get(@BeanParam Outer.Inner params) { return ""; }
                            }
                            """));

            result.assertSuccess();

            // Class-init of the generated companion runs loadFieldAnnotations(beanFqn, fieldName).
            // If beanFqn is the source-form "dev.vertique.test.Outer.Inner", Class.forName throws
            // ClassNotFoundException → IllegalStateException → ExceptionInInitializerError.
            // If beanFqn is the binary "dev.vertique.test.Outer$Inner", the load succeeds and
            // fields() returns the field meta cleanly.
            List<BeanParamFieldMeta> fields = callFields(result, "dev.vertique.test.Outer_Inner_BeanParamModel");

            assertEquals(1, fields.size(), "Nested bean must produce exactly one field");
            assertEquals("q", fields.get(0).name(), "Field name must be 'q'");
            assertNotNull(
                    fields.get(0).meta().annotationsLazy().get(),
                    "annotations() must be non-null — proves loadFieldAnnotations resolved the nested class");
        }
    }
}
