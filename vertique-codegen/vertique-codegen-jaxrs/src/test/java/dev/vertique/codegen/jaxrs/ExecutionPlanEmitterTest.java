// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * APT compile-tests for {@link dev.vertique.codegen.jaxrs.processor.emit.ExecutionPlanEmitter} (CG-010
 * step 4d, slice 2).
 *
 * <p>Each test compiles a small fixture through the full pipeline processor and asserts on the
 * presence/absence of generated execution plans and their compilability.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Simple eligible public method — plan emitted and compiles with correct class shape.</li>
 *   <li>Non-public method — plan NOT emitted (eligibility gate).</li>
 *   <li>Package-private parameter type in foreign package — plan NOT emitted (gate).</li>
 *   <li>Each {@link dev.vertique.codegen.jaxrs.JaxRsParamSource} value handled — specific
 *       support-helper call keywords present in generated source.</li>
 *   <li>Primitive parameter types — compiled plan uses boxed-type cast correctly.</li>
 *   <li>Descriptor's {@code describe()} returns {@code ResourceMethodMeta} with non-null
 *       {@code executionPlan()} for eligible methods.</li>
 * </ul>
 */
class ExecutionPlanEmitterTest {

    // --- Helpers ---

    /**
     * Returns a no-op {@link GeneratedJaxRsSupport} stub that satisfies the plan's compile-time
     * requirements during reflective invocation tests. Most methods return {@code null};
     * {@code extractFileUploads} returns an empty list.
     */
    private static GeneratedJaxRsSupport noOpSupport() {
        return new GeneratedJaxRsSupport() {
            @Override
            public Object extractScalarParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, BoundRequest r) {
                return null;
            }

            @Override
            public Object extractFormParam(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return null;
            }

            @Override
            public Object extractFileUploads(RoutingContext ctx) {
                return List.of();
            }

            @Override
            public Object extractEntityParts(ResourceMethodMeta.ParamMeta m, RoutingContext c) {
                return List.of();
            }

            @Override
            public Object deserializeBody(ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                return null;
            }

            @Override
            public Object resolveContext(Class<?> declaredType, RoutingContext c, String resourceClass, String method) {
                return null;
            }

            @Override
            public Object materializeBean(
                    dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta[] fields,
                    EffectiveInputPolicies routePolicies,
                    BoundRequest request,
                    RoutingContext ctx,
                    Class<?> beanType) {
                return null;
            }
        };
    }

    /**
     * Loads the generated descriptor's {@code describe()} output for the given resource.
     *
     * @param result        the compilation result
     * @param resourceFqn   FQN of the compiled resource class
     * @param descriptorFqn FQN of the generated descriptor class
     * @return the list of {@link ResourceMethodMeta} returned by {@code describe()}
     * @throws Exception if any reflection step fails
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ResourceMethodMeta> callDescribe(
            ProcessorTestHarness.Result result, String resourceFqn, String descriptorFqn) throws Exception {
        ClassLoader cl = result.generatedClassLoader();
        Class<?> resourceClass = cl.loadClass(resourceFqn);
        Object resourceInstance = resourceClass.getDeclaredConstructor().newInstance();

        Class<?> descriptorClass = result.loadGeneratedClass(descriptorFqn);
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        GeneratedJaxRsDescriptorSupport support = new GeneratedJaxRsDescriptorSupport();

        Method describeMethod =
                descriptorClass.getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class);
        return (List<ResourceMethodMeta>)
                describeMethod.invoke(descriptor, resourceInstance, support, Collections.emptyList());
    }

    // --- Eligible public method — plan emitted ---

    @Nested
    @DisplayName("eligible public method — plan is emitted and compiles")
    class EligiblePublicMethod {

        @Test
        @DisplayName("@GET method with @PathParam — plan class is generated")
        void getWithPathParam_planEmitted() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UserResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/users")
                            public class UserResource {
                                public UserResource() {}

                                @GET
                                @Path("/{id}")
                                public String getUser(@PathParam("id") String id) { return id; }
                            }
                            """));

            result.assertSuccess();
            // A plan class should be generated for getUser
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.UserResource_getUser_0_ExecutionPlan", "implements ResourceExecutionPlan");
        }

        @Test
        @DisplayName("@GET method with @PathParam — plan implements ResourceExecutionPlan")
        void getWithPathParam_planHasCorrectShape() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ItemResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/items")
                            public class ItemResource {
                                public ItemResource() {}

                                @GET
                                @Path("/{id}")
                                public String getItem(@PathParam("id") String id) { return id; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.ItemResource_getItem_0_ExecutionPlan", "extractArguments");
            result.assertGeneratedSourceContains("dev.vertique.test.ItemResource_getItem_0_ExecutionPlan", "invoke");
            // ParamMeta static constant for the path param
            result.assertGeneratedSourceContains("dev.vertique.test.ItemResource_getItem_0_ExecutionPlan", "PATH");
        }

        @Test
        @DisplayName("descriptor's describe() returns ResourceMethodMeta with non-null executionPlan")
        void descriptor_metaHasNonNullPlan() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.GreetResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/greet")
                            public class GreetResource {
                                public GreetResource() {}

                                @GET
                                public String greet(@QueryParam("name") String name) { return name; }
                            }
                            """));

            result.assertSuccess();

            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.GreetResource", "dev.vertique.test.GreetResource_JaxRsDescriptor");

            assertEquals(1, metas.size(), "Expected one method meta");
            ResourceMethodMeta meta = metas.get(0);
            assertNotNull(meta.executionPlan(), "executionPlan must be non-null for eligible public method");
        }
    }

    // --- Non-public method — plan not emitted ---

    @Nested
    @DisplayName("non-public method — plan not emitted (eligibility gate)")
    class NonPublicMethod {

        @Test
        @DisplayName("package-private method — no plan class generated")
        void packagePrivateMethod_noPlan() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PackagePrivateResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/pp")
                            public class PackagePrivateResource {
                                public PackagePrivateResource() {}

                                // Package-private method — not eligible for plan emission
                                @GET
                                String list() { return ""; }
                            }
                            """));

            result.assertSuccess();
            // No plan class should be generated for the package-private method
            assertFalse(
                    result.compilation()
                            .generatedSourceFile("dev.vertique.test.PackagePrivateResource_list_0_ExecutionPlan")
                            .isPresent(),
                    "Package-private method must not get a plan");
        }

        @Test
        @DisplayName("descriptor emits null executionPlan literal for non-public method")
        void descriptor_emitsNullPlanLiteralForNonPublicMethod() {
            // Verify at the generated-source level that the descriptor passes null (not new XXX_ExecutionPlan())
            // for the non-public method. We cannot call describe() reflectively for non-public methods
            // because support.resolveMethod uses Class.getMethod which only finds public methods.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PpResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/pp2")
                            public class PpResource {
                                public PpResource() {}

                                @GET
                                String list() { return ""; }
                            }
                            """));

            result.assertSuccess();
            // The descriptor should pass null (not a plan reference) for the non-public method
            result.assertGeneratedSourceContains("dev.vertique.test.PpResource_JaxRsDescriptor", "null");
            // And no plan class should have been generated
            assertFalse(
                    result.compilation()
                            .generatedSourceFile("dev.vertique.test.PpResource_list_0_ExecutionPlan")
                            .isPresent(),
                    "No plan class must be generated for non-public method");
        }
    }

    // --- Each JaxRsParamSource value handled ---

    @Nested
    @DisplayName("JaxRsParamSource coverage — each source uses the correct support-helper call")
    class ParamSourceCoverage {

        @Test
        @DisplayName("@PathParam — extractScalarParam call emitted")
        void pathParam_extractScalarParam() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PathParamResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/path")
                            public class PathParamResource {
                                public PathParamResource() {}

                                @GET @Path("/{id}")
                                public String get(@PathParam("id") String id) { return id; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.PathParamResource_get_0_ExecutionPlan", "extractScalarParam");
        }

        @Test
        @DisplayName("@QueryParam — extractScalarParam call emitted")
        void queryParam_extractScalarParam() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.QueryParamResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/query")
                            public class QueryParamResource {
                                public QueryParamResource() {}

                                @GET
                                public String list(@QueryParam("page") int page) { return ""; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.QueryParamResource_list_0_ExecutionPlan", "extractScalarParam");
        }

        @Test
        @DisplayName("unannotated body param — deserializeBody call emitted")
        void bodyParam_deserializeBody() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.BodyResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/body")
                            public class BodyResource {
                                public BodyResource() {}

                                @POST
                                public String create(String body) { return body; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.BodyResource_create_0_ExecutionPlan", "deserializeBody");
        }

        @Test
        @DisplayName("@FormParam — extractFormParam call emitted")
        void formParam_extractFormParam() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.FormResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/form")
                            public class FormResource {
                                public FormResource() {}

                                @POST
                                public String upload(@FormParam("name") String name) { return name; }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.FormResource_upload_0_ExecutionPlan", "extractFormParam");
        }

        @Test
        @DisplayName("CONTEXT parameter — resolveContext via static CTX constant, no per-request class load")
        void contextParam_ctxDirectly() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.CtxResource", """
                            package dev.vertique.test;

                            import io.vertx.ext.web.RoutingContext;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/ctx")
                            public class CtxResource {
                                public CtxResource() {}

                                @GET
                                public String get(RoutingContext ctx) { return ""; }
                            }
                            """));

            result.assertSuccess();

            String src = result.compilation()
                    .generatedSourceFile("dev.vertique.test.CtxResource_get_0_ExecutionPlan")
                    .map(f -> {
                        try {
                            return f.getCharContent(true).toString();
                        } catch (Exception e) {
                            return "";
                        }
                    })
                    .orElse("");

            // CONTEXT params resolve through the RestContextResolver chain via the support contract,
            // keyed by a static Class<?> constant resolved once at class-load time (rest-016).
            assertTrue(
                    src.contains("support.resolveContext(CTX0, ctx,"),
                    "CONTEXT param must dispatch via support.resolveContext(CTX0, ctx, ...)");
            assertTrue(
                    src.contains("static final Class<?> CTX0 = loadClass("),
                    "CONTEXT param type must be loaded once into a static CTX0 constant");

            // CG-010 optimization guard: no per-request class loading, ParamMeta, or policy for CONTEXT.
            assertFalse(
                    src.contains("resolveContext(loadClass("),
                    "CONTEXT param must NOT call loadClass(...) per request — it must use the static CTX0 constant");
            assertFalse(src.contains("extractScalarParam"), "CONTEXT param must not use extractScalarParam");
        }

        @Test
        @DisplayName("RequestPreconditions parameter — RequestPreconditions.from(ctx) emitted")
        void preconditionsParam_fromCtx() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PrecondResource", """
                            package dev.vertique.test;

                            import dev.vertique.rest.core.request.RequestPreconditions;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/precond")
                            public class PrecondResource {
                                public PrecondResource() {}

                                @GET @Path("/{id}")
                                public String get(@PathParam("id") String id, RequestPreconditions preconditions) {
                                    return id;
                                }
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.PrecondResource_get_0_ExecutionPlan", "RequestPreconditions.from(ctx)");
        }
    }

    // --- Primitive parameters — compiled plan uses boxed-type cast ---

    @Nested
    @DisplayName("primitive parameter types — boxed cast emitted so Object[] to primitive compiles")
    class PrimitiveParameters {

        @Test
        @DisplayName("int @QueryParam — plan compiles without type-cast errors")
        void intQueryParam_compilesClean() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.IntResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/int")
                            public class IntResource {
                                public IntResource() {}

                                @GET
                                public String list(@QueryParam("page") int page, @QueryParam("size") long size) {
                                    return "";
                                }
                            }
                            """));

            result.assertSuccess();
            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errorCount, "Primitive params must produce zero compile errors");

            // Verify plan was emitted
            result.assertGeneratedSourceContains("dev.vertique.test.IntResource_list_0_ExecutionPlan", "Integer");
            result.assertGeneratedSourceContains("dev.vertique.test.IntResource_list_0_ExecutionPlan", "Long");
        }

        @Test
        @DisplayName("boolean @PathParam — plan compiles without type-cast errors")
        void booleanPathParam_compilesClean() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.BoolResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/bool")
                            public class BoolResource {
                                public BoolResource() {}

                                @GET @Path("/{flag}")
                                public String get(@PathParam("flag") boolean flag) { return ""; }
                            }
                            """));

            result.assertSuccess();
            long errorCount = result.compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errorCount, "Boolean @PathParam must produce zero compile errors");
            result.assertGeneratedSourceContains("dev.vertique.test.BoolResource_get_0_ExecutionPlan", "Boolean");
        }
    }

    // --- Void return type ---

    @Nested
    @DisplayName("void return type — invoke returns null")
    class VoidReturnType {

        @Test
        @DisplayName("@DELETE void method — plan compiles and invoke() returns null")
        void voidMethod_planCompiles() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.DeleteResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/del")
                            public class DeleteResource {
                                public DeleteResource() {}

                                @DELETE @Path("/{id}")
                                public void delete(@PathParam("id") String id) {}
                            }
                            """));

            result.assertSuccess();
            result.assertGeneratedSourceContains(
                    "dev.vertique.test.DeleteResource_delete_0_ExecutionPlan", "return null");

            // Verify the plan class can be loaded and instantiated
            Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.DeleteResource_delete_0_ExecutionPlan");
            assertTrue(
                    ResourceExecutionPlan.class.isAssignableFrom(planClass),
                    "Plan class must implement ResourceExecutionPlan");
        }
    }

    // --- Descriptor integration — plan ClassName wired into ResourceMethodMeta ---

    @Nested
    @DisplayName("descriptor integration — executionPlan field wired for eligible methods")
    class DescriptorIntegration {

        @Test
        @DisplayName("multi-method resource — public method gets plan, non-public has no plan file")
        void multiMethod_publicGetsPlanNonPublicDoesNot() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MixedResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/mixed")
                            public class MixedResource {
                                public MixedResource() {}

                                @GET
                                public String list() { return ""; }

                                @DELETE @Path("/{id}")
                                // package-private — no plan
                                void delete(@PathParam("id") String id) {}
                            }
                            """));

            result.assertSuccess();

            // list() is public — should have a plan
            assertTrue(
                    result.compilation()
                            .generatedSourceFile("dev.vertique.test.MixedResource_list_0_ExecutionPlan")
                            .isPresent(),
                    "Public list() must get a plan");

            // delete() is package-private — no plan
            assertFalse(
                    result.compilation()
                            .generatedSourceFile("dev.vertique.test.MixedResource_delete_1_ExecutionPlan")
                            .isPresent(),
                    "Package-private delete() must NOT get a plan");
        }

        @Test
        @DisplayName("public-only resource — descriptor's describe() returns non-null executionPlan")
        void publicOnlyResource_describeHasPlan() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PublicOnlyResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/pubonly")
                            public class PublicOnlyResource {
                                public PublicOnlyResource() {}

                                @GET
                                public String list(@QueryParam("q") String q) { return q; }
                            }
                            """));

            result.assertSuccess();

            List<ResourceMethodMeta> metas = callDescribe(
                    result,
                    "dev.vertique.test.PublicOnlyResource",
                    "dev.vertique.test.PublicOnlyResource_JaxRsDescriptor");

            assertEquals(1, metas.size());
            assertNotNull(metas.get(0).executionPlan(), "public list() must have non-null executionPlan in describe()");
        }
    }

    // --- Neutral generated source — no OpenAPI validation coupling (slice 4) ---

    @Nested
    @DisplayName("generated execution plan is OpenAPI-validation free")
    class OpenApiNeutrality {

        @Test
        @DisplayName("generated plan imports BoundRequest and references no io.vertx.openapi.validation type")
        void generatedExecutionPlanContainsNoOpenApiValidationImport() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.NeutralResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/neutral")
                            public class NeutralResource {
                                public NeutralResource() {}

                                @GET @Path("/{id}")
                                public String getUser(@PathParam("id") int id, @QueryParam("lang") String lang) {
                                    return id + lang;
                                }
                            }
                            """));

            result.assertSuccess();
            String src = generatedSource(result, "dev.vertique.test.NeutralResource_getUser_0_ExecutionPlan");

            assertTrue(
                    src.contains("dev.vertique.rest.jaxrs.request.BoundRequest"),
                    "Generated plan must import the neutral BoundRequest type");
            assertFalse(
                    src.contains("io.vertx.openapi.validation"),
                    "Generated plan must contain zero io.vertx.openapi.validation references");
        }
    }

    // --- Generated vs reflective dispatch parity (FR-008) ---

    @Nested
    @DisplayName("generated dispatch parity with the reflective BoundRequest binding")
    class GeneratedDispatchParity {

        @Test
        @DisplayName("generated extractArguments equals reflective extraction for the same BoundRequest")
        void generatedDispatchParityWithReflective() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UserResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/users")
                            public class UserResource {
                                public UserResource() {}

                                @GET @Path("/{id}")
                                public String getUser(@PathParam("id") int id, @QueryParam("lang") String lang) {
                                    return id + lang;
                                }
                            }
                            """));

            result.assertSuccess();

            // A BoundRequest carrying the same values both paths must read: id=42 (PATH), lang="en" (QUERY).
            BoundRequest boundRequest = boundRequest(
                    java.util.Map.of("id", dev.vertique.rest.core.request.RequestValue.of(42)),
                    java.util.Map.of("lang", dev.vertique.rest.core.request.RequestValue.of("en")));

            // Generated path: instantiate the emitted plan and call extractArguments(ctx, boundRequest, support).
            Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.UserResource_getUser_0_ExecutionPlan");
            ResourceExecutionPlan plan =
                    (ResourceExecutionPlan) planClass.getDeclaredConstructor().newInstance();
            GeneratedJaxRsSupport support = boundRequestReadingSupport();
            Object[] generated = plan.extractArguments(null, boundRequest, support);

            // Reflective expectation: the neutral binding reads each scalar from the BoundRequest maps.
            Object[] reflective = new Object[] {
                boundRequest.pathParameters().get("id").getInteger(),
                boundRequest.query().get("lang").getString()
            };

            assertArrayEquals(new Object[] {42, "en"}, generated, "Generated extraction must be {42, \"en\"}");
            assertArrayEquals(reflective, generated, "Generated extraction must equal the reflective extraction");
        }

        /**
         * A {@link GeneratedJaxRsSupport} that reads each scalar straight from the {@link BoundRequest}
         * maps using the parameter's source and name — mirroring the neutral
         * {@code ParameterExtractor.extractScalarParam(meta, policies, boundRequest)} behaviour for
         * {@code EffectiveInputPolicies.NONE} so the generated path's output can be compared against
         * the reflective binding's output.
         */
        private static GeneratedJaxRsSupport boundRequestReadingSupport() {
            return new GeneratedJaxRsSupport() {
                @Override
                public Object extractScalarParam(
                        ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, BoundRequest r) {
                    java.util.Map<String, dev.vertique.rest.core.request.RequestValue> map =
                            switch (m.source()) {
                                case PATH -> r.pathParameters();
                                case QUERY -> r.query();
                                case HEADER -> r.headers();
                                case COOKIE -> r.cookies();
                                default -> throw new IllegalStateException("Unexpected source: " + m.source());
                            };
                    dev.vertique.rest.core.request.RequestValue rv = map.get(m.name());
                    if (rv == null) {
                        return null;
                    }
                    if (m.type() == int.class || m.type() == Integer.class) {
                        return rv.getInteger();
                    }
                    return rv.getString();
                }

                @Override
                public Object extractFormParam(
                        ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                    return null;
                }

                @Override
                public Object extractFileUploads(RoutingContext ctx) {
                    return List.of();
                }

                @Override
                public Object extractEntityParts(ResourceMethodMeta.ParamMeta m, RoutingContext c) {
                    return List.of();
                }

                @Override
                public Object deserializeBody(
                        ResourceMethodMeta.ParamMeta m, EffectiveInputPolicies p, RoutingContext c) {
                    return null;
                }

                @Override
                public Object resolveContext(
                        Class<?> declaredType, RoutingContext c, String resourceClass, String method) {
                    return null;
                }

                @Override
                public Object materializeBean(
                        dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta[] fields,
                        EffectiveInputPolicies routePolicies,
                        BoundRequest request,
                        RoutingContext ctx,
                        Class<?> beanType) {
                    return null;
                }
            };
        }

        /** Builds a {@link BoundRequest} stub exposing the given path and query maps (others empty). */
        private static BoundRequest boundRequest(
                java.util.Map<String, dev.vertique.rest.core.request.RequestValue> path,
                java.util.Map<String, dev.vertique.rest.core.request.RequestValue> query) {
            return new BoundRequest() {
                @Override
                public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> pathParameters() {
                    return path;
                }

                @Override
                public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> query() {
                    return query;
                }

                @Override
                public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> headers() {
                    return java.util.Map.of();
                }

                @Override
                public java.util.Map<String, dev.vertique.rest.core.request.RequestValue> cookies() {
                    return java.util.Map.of();
                }

                @Override
                public dev.vertique.rest.core.request.RequestValue body() {
                    return dev.vertique.rest.core.request.RequestValue.of(null);
                }

                @Override
                public io.vertx.core.http.HttpServerRequest raw() {
                    return null;
                }
            };
        }
    }

    /**
     * Reads the generated source for the given FQN from the compilation result.
     *
     * @param result        the compilation result
     * @param generatedFqn  the fully-qualified name of the generated class
     * @return the generated source text, or an empty string if unreadable
     */
    private static String generatedSource(ProcessorTestHarness.Result result, String generatedFqn) {
        return result.compilation()
                .generatedSourceFile(generatedFqn)
                .map(f -> {
                    try {
                        return f.getCharContent(true).toString();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .orElse("");
    }

    // --- Literal-backed parameter annotations (GitHub issue #162, slice 2) ---

    /**
     * RED tests for literal-backed parameter annotations on {@code ExecutionPlanEmitter}'s
     * generated {@code P{n}} constants (GitHub issue #162, PRD-REST-018 §3.1b follow-up).
     *
     * <p>Before this slice, {@code buildParamMetaConstants} wraps every parameter's
     * {@code ParameterMetadata} view in a jaxrs-local {@code ReflectiveParameterMetadata} whose
     * fifth ({@code Annotation[]}) constructor argument is a literal {@code null} — parameter
     * annotations are simply absent on the generated execution-plan path. These tests prove the
     * fix: each parameter's runtime-retained annotations are materialized at compile time into a
     * literal-backed {@code ParameterMetadata} implementation (mirroring
     * {@code vertique-codegen-aop}'s {@code ParameterAnnotationLiteralTest}), so
     * {@code findAnnotation}/{@code annotationsLazy()} resolve real values with zero reflection at
     * request time.
     */
    @Nested
    @DisplayName("literal-backed parameter annotations (issue #162)")
    class ParameterAnnotationLiterals {

        @Test
        @DisplayName("@PathParam + custom marker — generated plan's parameter metadata exposes the literal annotation")
        void pathParamWithCustomAnnotation_generatedPlanExposesLiteralAnnotation() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MarkedResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/marked")
                            public class MarkedResource {
                                public MarkedResource() {}

                                @GET
                                @Path("/{id}")
                                public String getMarked(@PathParam("id") @TestParamMarker("x") String id) {
                                    return id;
                                }
                            }
                            """));

            result.assertSuccess();

            String planFqn = "dev.vertique.test.MarkedResource_getMarked_0_ExecutionPlan";
            String planSrc = generatedSource(result, planFqn);

            // The plan's own P0 constant must reference a generated standalone ParameterMetadata
            // implementation (not a null-annotations ReflectiveParameterMetadata).
            assertTrue(
                    planSrc.contains("MarkedResource_getMarked_0_ExecutionPlan_P0Meta"),
                    "Generated execution plan must reference a standalone per-parameter ParameterMetadata "
                            + "implementation for P0; generated source:\n" + planSrc);

            // That standalone implementation must materialize a TestParamMarker literal — either the
            // generated TestParamMarker$JaxRsLiteral type or a TestParamMarker-typed constant — for the
            // parameter's reflection-free findAnnotation lookup.
            String metaFqn = "dev.vertique.test.MarkedResource_getMarked_0_ExecutionPlan_P0Meta";
            String metaSrc = generatedSource(result, metaFqn);
            boolean referencesMarkerLiteral =
                    metaSrc.contains("TestParamMarker$JaxRsLiteral") || metaSrc.contains("TestParamMarker.class");
            assertTrue(
                    referencesMarkerLiteral,
                    "Generated per-parameter ParameterMetadata implementation must materialize a TestParamMarker "
                            + "literal for the reflection-free findAnnotation lookup; generated source:\n" + metaSrc);

            // Reflectively drive extractArguments to confirm the ParamMeta actually resolves the
            // TestParamMarker literal at runtime, with the correct occurrence value ("x").
            Class<?> planClass = result.loadGeneratedClass(planFqn);
            ResourceExecutionPlan plan =
                    (ResourceExecutionPlan) planClass.getDeclaredConstructor().newInstance();

            java.lang.reflect.Field pField = planClass.getDeclaredField("P0");
            pField.setAccessible(true);
            ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);

            Object parameterMetadata = paramMeta.parameterMetadata();
            assertNotNull(parameterMetadata, "ParamMeta must compose a non-null ParameterMetadata view");

            Method findAnnotationMethod = parameterMetadata.getClass().getMethod("findAnnotation", Class.class);
            findAnnotationMethod.setAccessible(true);
            Class<?> markerClass =
                    result.generatedClassLoader().loadClass("dev.vertique.codegen.jaxrs.TestParamMarker");
            @SuppressWarnings("unchecked")
            var found = (java.util.Optional<Object>) findAnnotationMethod.invoke(parameterMetadata, markerClass);
            assertTrue(
                    found.isPresent(), "findAnnotation(TestParamMarker.class) must resolve the materialized literal");

            Method valueMethod = markerClass.getMethod("value");
            assertEquals(
                    "x", valueMethod.invoke(found.get()), "The materialized literal must carry its occurrence value");
        }

        @Test
        @DisplayName("generated plan's parameter annotation lookup is reflection-free")
        void parameterAnnotationLookup_isReflectionFree() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MarkedNoReflectResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/marked-noreflect")
                            public class MarkedNoReflectResource {
                                public MarkedNoReflectResource() {}

                                @GET
                                @Path("/{id}")
                                public String get(@PathParam("id") @TestParamMarker("y") String id) {
                                    return id;
                                }
                            }
                            """));

            result.assertSuccess();

            String planSrc = generatedSource(result, "dev.vertique.test.MarkedNoReflectResource_get_0_ExecutionPlan");
            String metaSrc =
                    generatedSource(result, "dev.vertique.test.MarkedNoReflectResource_get_0_ExecutionPlan_P0Meta");

            for (String src : List.of(planSrc, metaSrc)) {
                assertFalse(
                        src.contains(".getAnnotation("),
                        "Generated source must not call getAnnotation(...); source:\n" + src);
                assertFalse(
                        src.contains("getParameterAnnotations"),
                        "Generated source must not call getParameterAnnotations(...); source:\n" + src);
                assertFalse(
                        src.contains("getDeclaredAnnotations"),
                        "Generated source must not call getDeclaredAnnotations(...); source:\n" + src);
            }
        }

        @Test
        @DisplayName(
                "parameter without a custom marker — generated plan does not reference the unrelated marker literal")
        void noCustomMarker_generatedPlanDoesNotReferenceUnrelatedMarker() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.UnmarkedResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/unmarked")
                            public class UnmarkedResource {
                                public UnmarkedResource() {}

                                @GET
                                @Path("/{id}")
                                public String get(@PathParam("id") String id) { return id; }
                            }
                            """));

            result.assertSuccess();

            String src = generatedSource(result, "dev.vertique.test.UnmarkedResource_get_0_ExecutionPlan");

            assertFalse(
                    src.contains("TestParamMarker"),
                    "Generated plan for a parameter with no custom marker must not reference TestParamMarker; source:\n"
                            + src);
        }

        @Test
        @DisplayName("same annotation type on two parameters with different values — dedup allows both occurrences")
        void sameAnnotationTypeAcrossMultipleParams_singleLiteralClassEmitted() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.DualMarkedResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/dual")
                            public class DualMarkedResource {
                                public DualMarkedResource() {}

                                @GET
                                public String get(
                                        @QueryParam("a") @TestParamMarker("a") String a,
                                        @QueryParam("b") @TestParamMarker("b") String b) {
                                    return a + b;
                                }
                            }
                            """));

            result.assertSuccess();

            String planFqn = "dev.vertique.test.DualMarkedResource_get_0_ExecutionPlan";
            Class<?> planClass = result.loadGeneratedClass(planFqn);

            Class<?> markerClass =
                    result.generatedClassLoader().loadClass("dev.vertique.codegen.jaxrs.TestParamMarker");
            Method valueMethod = markerClass.getMethod("value");

            java.lang.reflect.Field p0Field = planClass.getDeclaredField("P0");
            p0Field.setAccessible(true);
            ResourceMethodMeta.ParamMeta p0 = (ResourceMethodMeta.ParamMeta) p0Field.get(null);
            Method findAnnotation0 = p0.parameterMetadata().getClass().getMethod("findAnnotation", Class.class);
            findAnnotation0.setAccessible(true);
            @SuppressWarnings("unchecked")
            var found0 = (java.util.Optional<Object>) findAnnotation0.invoke(p0.parameterMetadata(), markerClass);
            assertTrue(found0.isPresent(), "First parameter must resolve its own TestParamMarker occurrence");
            assertEquals("a", valueMethod.invoke(found0.get()));

            java.lang.reflect.Field p1Field = planClass.getDeclaredField("P1");
            p1Field.setAccessible(true);
            ResourceMethodMeta.ParamMeta p1 = (ResourceMethodMeta.ParamMeta) p1Field.get(null);
            Method findAnnotation1 = p1.parameterMetadata().getClass().getMethod("findAnnotation", Class.class);
            findAnnotation1.setAccessible(true);
            @SuppressWarnings("unchecked")
            var found1 = (java.util.Optional<Object>) findAnnotation1.invoke(p1.parameterMetadata(), markerClass);
            assertTrue(found1.isPresent(), "Second parameter must resolve its own TestParamMarker occurrence");
            assertEquals("b", valueMethod.invoke(found1.get()));
        }

        @Test
        @DisplayName(
                "interface-only marker annotation (no override on the concrete parameter) is still materialized — review finding fix")
        void interfaceOnlyMarkerAnnotation_stillMaterializedOnConcreteExecutionPlan() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.IfaceMarkedApi", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/iface-marked")
                            public interface IfaceMarkedApi {
                                @GET
                                @Path("/{id}")
                                String getMarked(@PathParam("id") @TestParamMarker("iface") String id);
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.IfaceMarkedResource", """
                            package dev.vertique.test;

                            public class IfaceMarkedResource implements IfaceMarkedApi {
                                public IfaceMarkedResource() {}

                                @Override
                                public String getMarked(String id) { return id; }
                            }
                            """));

            result.assertSuccess();

            // The concrete override's parameter carries NO annotations at all — only the interface
            // method's parameter declares @PathParam and @TestParamMarker. Before the fix, the
            // generated plan materialized annotations from EffectiveParamContract.concreteParameter()
            // alone, so this marker would be silently absent from the generated route even though the
            // reflective scan path (via AnnotationResolver.resolveParameterAnnotations) always saw it.
            Class<?> planClass =
                    result.loadGeneratedClass("dev.vertique.test.IfaceMarkedResource_getMarked_0_ExecutionPlan");
            java.lang.reflect.Field pField = planClass.getDeclaredField("P0");
            pField.setAccessible(true);
            ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);

            Class<?> markerClass =
                    result.generatedClassLoader().loadClass("dev.vertique.codegen.jaxrs.TestParamMarker");
            Method findAnnotationMethod =
                    paramMeta.parameterMetadata().getClass().getMethod("findAnnotation", Class.class);
            findAnnotationMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            var found = (java.util.Optional<Object>)
                    findAnnotationMethod.invoke(paramMeta.parameterMetadata(), markerClass);
            assertTrue(
                    found.isPresent(),
                    "An interface-only marker annotation (no direct override on the concrete parameter) must "
                            + "still be materialized on the generated execution plan, matching the reflective "
                            + "scan path's AnnotationResolver.resolveParameterAnnotations merge behavior");

            Method valueMethod = markerClass.getMethod("value");
            assertEquals("iface", valueMethod.invoke(found.get()));
        }

        @Test
        @DisplayName(
                "unsupported-member annotation + supported marker — compiles; both resolvable via reflective fallback; annotationsLazy is a defensive copy")
        void unsupportedMemberAnnotation_reflectiveFallbackPreservesFullParity() throws Exception {
            // The parameter carries a supported marker (@TestParamMarker, materialized as a literal)
            // AND an annotation with an unsupported nested-annotation member (@TestUnsupportedMemberMarker,
            // which AnnotationLiteralEmitter cannot render). ADR-0146 parity-first policy: this must
            // compile (not fail), and the unsupported annotation must remain resolvable at runtime via
            // the reflective fallback — never silently omitted.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MixedAnnResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/mixed-ann")
                            public class MixedAnnResource {
                                public MixedAnnResource() {}

                                @GET
                                public String get(
                                        @QueryParam("q") @TestParamMarker("m") @TestUnsupportedMemberMarker("u") String q) {
                                    return q;
                                }
                            }
                            """));

            // Compilation must SUCCEED — the unsupported member does not fail the build.
            result.assertSuccess();

            Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.MixedAnnResource_get_0_ExecutionPlan");
            java.lang.reflect.Field pField = planClass.getDeclaredField("P0");
            pField.setAccessible(true);
            ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);
            Object parameterMetadata = paramMeta.parameterMetadata();

            ClassLoader cl = result.generatedClassLoader();
            Class<?> supportedMarker = cl.loadClass("dev.vertique.codegen.jaxrs.TestParamMarker");
            Class<?> unsupportedMarker = cl.loadClass("dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker");

            Method findAnnotation = parameterMetadata.getClass().getMethod("findAnnotation", Class.class);
            findAnnotation.setAccessible(true);

            // The supported marker resolves (via the compile-time literal).
            @SuppressWarnings("unchecked")
            var foundSupported = (java.util.Optional<Object>) findAnnotation.invoke(parameterMetadata, supportedMarker);
            assertTrue(foundSupported.isPresent(), "The supported marker must resolve via its compile-time literal");

            // The unsupported-member annotation ALSO resolves (via the reflective fallback) — full parity.
            @SuppressWarnings("unchecked")
            var foundUnsupported =
                    (java.util.Optional<Object>) findAnnotation.invoke(parameterMetadata, unsupportedMarker);
            assertTrue(
                    foundUnsupported.isPresent(),
                    "The unsupported-member annotation must still resolve via the reflective fallback "
                            + "(ADR-0146 parity-first): codegen must not omit an annotation the reflective path sees");

            // annotationsLazy() returns the FULL merged set (both annotations), matching the reflective path.
            Method annotationsLazy = parameterMetadata.getClass().getMethod("annotationsLazy");
            annotationsLazy.setAccessible(true);
            @SuppressWarnings("unchecked")
            var supplier = (java.util.function.Supplier<java.lang.annotation.Annotation[]>)
                    annotationsLazy.invoke(parameterMetadata);
            java.lang.annotation.Annotation[] merged = supplier.get();
            boolean hasSupported = false;
            boolean hasUnsupported = false;
            for (java.lang.annotation.Annotation a : merged) {
                if (supportedMarker.isInstance(a)) hasSupported = true;
                if (unsupportedMarker.isInstance(a)) hasUnsupported = true;
            }
            assertTrue(hasSupported, "annotationsLazy() must include the supported marker");
            assertTrue(
                    hasUnsupported, "annotationsLazy() must include the unsupported-member annotation (full parity)");

            // annotationsLazy() must return a DEFENSIVE COPY — mutating the returned array must not
            // poison the next call (the backing set is a per-route singleton passed to external
            // ParamConverterProviders).
            java.util.Arrays.fill(merged, null);
            java.lang.annotation.Annotation[] second = supplier.get();
            boolean secondStillIntact = false;
            for (java.lang.annotation.Annotation a : second) {
                if (a != null && unsupportedMarker.isInstance(a)) secondStillIntact = true;
            }
            assertTrue(
                    secondStillIntact,
                    "annotationsLazy() must return a defensive copy — mutating one result must not corrupt the next");
        }

        @Test
        @DisplayName(
                "reflective fallback resolves even when a sibling parameter is array-typed (loadClass handles array FQNs)")
        void reflectiveFallback_withArrayTypedSiblingParameter_resolvesWithoutThrowing() throws Exception {
            // The fallback-triggering param (@TestUnsupportedMemberMarker forces the reflective path)
            // sits alongside an array-typed sibling parameter (String[] tags). The reflective fallback
            // resolves the enclosing Method by parameter-type FQNs; the array param's erased FQN is
            // "java.lang.String[]" (source form), which Class.forName cannot load. Before the fix,
            // loadClass falls into Class.forName("java.lang.String[]", ...) → ClassNotFoundException →
            // IllegalStateException the first time findAnnotation/annotationsLazy fires on the fallback
            // param. After the fix, loadClass materializes the array Class and the lookup succeeds.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ArraySiblingResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/array-sibling")
                            public class ArraySiblingResource {
                                public ArraySiblingResource() {}

                                @GET
                                public String get(
                                        @QueryParam("q") @TestUnsupportedMemberMarker("u") String q,
                                        @QueryParam("tags") String[] tags,
                                        @QueryParam("codes") int[] codes) {
                                    return q;
                                }
                            }
                            """));

            result.assertSuccess();

            Class<?> planClass =
                    result.loadGeneratedClass("dev.vertique.test.ArraySiblingResource_get_0_ExecutionPlan");
            // P0 is the fallback-triggering @TestUnsupportedMemberMarker param.
            java.lang.reflect.Field pField = planClass.getDeclaredField("P0");
            pField.setAccessible(true);
            ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);
            Object parameterMetadata = paramMeta.parameterMetadata();

            ClassLoader cl = result.generatedClassLoader();
            Class<?> unsupportedMarker = cl.loadClass("dev.vertique.codegen.jaxrs.TestUnsupportedMemberMarker");

            Method findAnnotation = parameterMetadata.getClass().getMethod("findAnnotation", Class.class);
            findAnnotation.setAccessible(true);

            // Firing the reflective fallback must NOT throw IllegalStateException even though a sibling
            // parameter is array-typed — and it must resolve the annotation.
            @SuppressWarnings("unchecked")
            var foundUnsupported =
                    (java.util.Optional<Object>) findAnnotation.invoke(parameterMetadata, unsupportedMarker);
            assertTrue(
                    foundUnsupported.isPresent(),
                    "The reflective fallback must resolve the annotation without throwing even when a "
                            + "sibling parameter is array-typed (loadClass must handle array FQNs)");

            // annotationsLazy() must also resolve without throwing and include the annotation.
            Method annotationsLazy = parameterMetadata.getClass().getMethod("annotationsLazy");
            annotationsLazy.setAccessible(true);
            @SuppressWarnings("unchecked")
            var supplier = (java.util.function.Supplier<java.lang.annotation.Annotation[]>)
                    annotationsLazy.invoke(parameterMetadata);
            java.lang.annotation.Annotation[] merged = supplier.get();
            boolean hasUnsupported = false;
            for (java.lang.annotation.Annotation a : merged) {
                if (unsupportedMarker.isInstance(a)) hasUnsupported = true;
            }
            assertTrue(
                    hasUnsupported,
                    "annotationsLazy() must include the unsupported-member annotation resolved via the "
                            + "reflective fallback (array-typed sibling must not break method lookup)");
        }

        @Test
        @DisplayName(
                "same-type annotation with differing member values across concrete+override forces fallback and preserves both (equality-dedup parity)")
        void sameTypeDifferingMembers_forcesFallback_preservesBothEntries() throws Exception {
            // The SAME literalizable annotation type (@TestTagMarker) is declared on the concrete param
            // as @TestTagMarker("a") and on the interface override as @TestTagMarker("b"). The runtime
            // reflective merge (AnnotationResolver.resolveParameterAnnotations) dedups by
            // LinkedHashSet<Annotation> EQUALITY (type + member values), so it keeps BOTH. The
            // all-literalizable codegen fast path dedups by annotation TYPE FQN, collapsing to ONE — a
            // parity gap. The fix forces this parameter onto the reflective fallback (which reproduces
            // the exact reflective equality-dedup), so annotationsLazy() returns BOTH @TestTagMarker
            // entries, matching the reflective path.
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.TaggedApi", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestTagMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/tagged")
                            public interface TaggedApi {
                                @GET
                                String get(@QueryParam("q") @TestTagMarker("b") String q);
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.TaggedResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestTagMarker;
                            import jakarta.ws.rs.QueryParam;

                            public class TaggedResource implements TaggedApi {
                                public TaggedResource() {}

                                @Override
                                public String get(@QueryParam("q") @TestTagMarker("a") String q) {
                                    return q;
                                }
                            }
                            """));

            result.assertSuccess();

            Class<?> planClass = result.loadGeneratedClass("dev.vertique.test.TaggedResource_get_0_ExecutionPlan");
            java.lang.reflect.Field pField = planClass.getDeclaredField("P0");
            pField.setAccessible(true);
            ResourceMethodMeta.ParamMeta paramMeta = (ResourceMethodMeta.ParamMeta) pField.get(null);
            Object parameterMetadata = paramMeta.parameterMetadata();

            ClassLoader cl = result.generatedClassLoader();
            Class<?> tagMarker = cl.loadClass("dev.vertique.codegen.jaxrs.TestTagMarker");
            Method valueMethod = tagMarker.getMethod("value");

            // annotationsLazy() must return BOTH @TestTagMarker entries ("a" and "b"), matching the
            // reflective equality-dedup. The type-dedup fast path would collapse to ONE.
            Method annotationsLazy = parameterMetadata.getClass().getMethod("annotationsLazy");
            annotationsLazy.setAccessible(true);
            @SuppressWarnings("unchecked")
            var supplier = (java.util.function.Supplier<java.lang.annotation.Annotation[]>)
                    annotationsLazy.invoke(parameterMetadata);
            java.lang.annotation.Annotation[] merged = supplier.get();

            java.util.Set<String> tagValues = new java.util.HashSet<>();
            for (java.lang.annotation.Annotation a : merged) {
                if (tagMarker.isInstance(a)) {
                    tagValues.add((String) valueMethod.invoke(a));
                }
            }
            assertEquals(
                    java.util.Set.of("a", "b"),
                    tagValues,
                    "annotationsLazy() must include BOTH @TestTagMarker entries (a + b), matching the "
                            + "reflective equality-dedup; the type-dedup literal fast path collapses to one");
        }
    }
}
