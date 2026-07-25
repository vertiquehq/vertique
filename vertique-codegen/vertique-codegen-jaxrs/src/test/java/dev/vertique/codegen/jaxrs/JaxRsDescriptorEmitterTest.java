// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * APT compile-tests for {@link JaxRsPipelineProcessor} descriptor emission (CG-010 step 4b).
 *
 * <p>Each test compiles a small fixture through the pipeline processor, loads the generated
 * {@code _JaxRsDescriptor} class via the compilation's classloader, instantiates it reflectively,
 * and calls {@code describe(...)} to verify the produced {@link ResourceMethodMeta} list.
 *
 * <p>Covered scenarios:
 * <ul>
 *   <li>Simple {@code @Path} resource — descriptor emitted and {@code describe()} returns correct
 *       metadata (operationId, httpMethod, path, params, responseBodyType, returnsFuture,
 *       returnsVoid).</li>
 *   <li>Interface-backed resource — effective contract surfaces correctly in descriptor output.</li>
 *   <li>Pre-computed {@link SecurityPolicy} variants — None, PermitAll, DenyAll,
 *       AuthenticatedOnly, Constrained — each emitted as the correct static constant.</li>
 *   <li>Sub-resource locator (no HTTP verb) — not present in the descriptor method list.</li>
 *   <li>Void return type — {@code returnsVoid} is {@code true}.</li>
 *   <li>{@code Future<T>} return type — {@code returnsFuture} is {@code true} and response body
 *       type is the unwrapped type argument.</li>
 * </ul>
 */
class JaxRsDescriptorEmitterTest {

    // --- Helper: load and call descriptor ---

    /**
     * Loads the generated {@code _JaxRsDescriptor} class, instantiates it, and calls
     * {@code describe(resourceInstance, support, emptyList)} to obtain the method meta list.
     *
     * @param result        the compilation result
     * @param resourceFqn   FQN of the generated resource class (compiled from fixture)
     * @param descriptorFqn FQN of the expected generated descriptor class
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

    // --- Simple GET resource ---

    @Nested
    @DisplayName("simple @Path resource — descriptor emitted with correct metadata")
    class SimpleResource {

        @Test
        @DisplayName("@GET method — operationId, httpMethod, path, param, responseBodyType correct")
        void getMethod_metadataCorrect() throws Exception {
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

            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.UserResource", "dev.vertique.test.UserResource_JaxRsDescriptor");

            assertEquals(1, metas.size(), "Expected exactly one method meta");
            ResourceMethodMeta meta = metas.get(0);

            assertEquals("GET", meta.httpMethod());
            assertEquals("/users/{id}", meta.path());
            assertEquals(String.class, meta.responseBodyType());
            assertFalse(meta.returnsFuture(), "Plain String return should not be Future");
            assertFalse(meta.returnsVoid(), "String return should not be void");
            assertEquals(1, meta.params().size());
            assertEquals("id", meta.params().get(0).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.PATH, meta.params().get(0).source());
        }

        @Test
        @DisplayName("void return — returnsVoid is true")
        void voidReturn_returnsVoidIsTrue() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.OrderResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.DELETE;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/orders")
                            public class OrderResource {
                                public OrderResource() {}

                                @DELETE
                                @Path("/{id}")
                                public void deleteOrder(@PathParam("id") String id) {}
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.OrderResource", "dev.vertique.test.OrderResource_JaxRsDescriptor");

            assertEquals(1, metas.size());
            assertTrue(metas.get(0).returnsVoid(), "void return method should have returnsVoid=true");
        }

        @Test
        @DisplayName("multiple methods — all appear in describe() output")
        void multipleMethods_allInOutput() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ItemResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/items")
                            public class ItemResource {
                                public ItemResource() {}

                                @GET
                                public String list() { return ""; }

                                @POST
                                public String create(String body) { return body; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.ItemResource", "dev.vertique.test.ItemResource_JaxRsDescriptor");

            assertEquals(2, metas.size(), "Both methods should appear");
        }
    }

    // --- Sub-resource locator skip ---

    @Nested
    @DisplayName("sub-resource locator — skipped in descriptor")
    class SubResourceLocatorSkip {

        @Test
        @DisplayName("method without HTTP verb (sub-resource locator) — not in descriptor")
        void subResourceLocator_notInDescriptor() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.NsResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/ns")
                            public class NsResource {
                                public NsResource() {}

                                @GET
                                public String list() { return ""; }

                                // No HTTP verb — should be skipped as sub-resource locator
                                @Path("/sub")
                                public Object locator() { return null; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.NsResource", "dev.vertique.test.NsResource_JaxRsDescriptor");

            assertEquals(1, metas.size(), "Only the @GET method should appear; sub-resource locator skipped");
            assertEquals("GET", metas.get(0).httpMethod());
        }
    }

    // --- SecurityPolicy variants ---

    @Nested
    @DisplayName("SecurityPolicy precomputation — all variants")
    class SecurityPolicyVariants {

        @Test
        @DisplayName("unannotated method — SecurityPolicy.None")
        void noSecurity_policyNone() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.OpenResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/open")
                            public class OpenResource {
                                public OpenResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.OpenResource", "dev.vertique.test.OpenResource_JaxRsDescriptor");

            assertInstanceOf(
                    SecurityPolicy.None.class,
                    metas.get(0).securityPolicy(),
                    "Unannotated method should have SecurityPolicy.None");
        }

        @Test
        @DisplayName("@PermitAll method — SecurityPolicy.PermitAll")
        void permitAll_policyPermitAll() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PaResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/pa")
                            public class PaResource {
                                public PaResource() {}

                                @GET
                                @PermitAll
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.PaResource", "dev.vertique.test.PaResource_JaxRsDescriptor");

            assertInstanceOf(
                    SecurityPolicy.PermitAll.class,
                    metas.get(0).securityPolicy(),
                    "@PermitAll method should have SecurityPolicy.PermitAll");
        }

        @Test
        @DisplayName("@DenyAll method — SecurityPolicy.DenyAll")
        void denyAll_policyDenyAll() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.DaResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/da")
                            public class DaResource {
                                public DaResource() {}

                                @GET
                                @DenyAll
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.DaResource", "dev.vertique.test.DaResource_JaxRsDescriptor");

            assertInstanceOf(
                    SecurityPolicy.DenyAll.class,
                    metas.get(0).securityPolicy(),
                    "@DenyAll method should have SecurityPolicy.DenyAll");
        }

        @Test
        @DisplayName("@RolesAllowed method — SecurityPolicy.Constrained with roles")
        void rolesAllowed_policyConstrained() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.RaResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.RolesAllowed;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/ra")
                            public class RaResource {
                                public RaResource() {}

                                @GET
                                @RolesAllowed({"admin", "user"})
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.RaResource", "dev.vertique.test.RaResource_JaxRsDescriptor");

            SecurityPolicy policy = metas.get(0).securityPolicy();
            assertInstanceOf(
                    SecurityPolicy.Constrained.class,
                    policy,
                    "@RolesAllowed method should have SecurityPolicy.Constrained");
            SecurityPolicy.Constrained constrained = (SecurityPolicy.Constrained) policy;
            assertEquals(List.of("admin", "user"), constrained.requiredRoles());
            assertTrue(constrained.requiredScopes().isEmpty(), "No scopes expected");
        }

        @Test
        @DisplayName("class-level @DenyAll + no method annotation — method inherits DenyAll")
        void classLevelDenyAll_inheritedByMethod() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.ClassDaResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/cda")
                            @DenyAll
                            public class ClassDaResource {
                                public ClassDaResource() {}

                                @GET
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.ClassDaResource", "dev.vertique.test.ClassDaResource_JaxRsDescriptor");

            assertInstanceOf(
                    SecurityPolicy.DenyAll.class,
                    metas.get(0).securityPolicy(),
                    "Method should inherit class-level @DenyAll");
        }

        @Test
        @DisplayName("method-level @PermitAll overrides class-level @DenyAll")
        void methodOverridesClass_permitAllWins() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.OverrideResource", """
                            package dev.vertique.test;

                            import jakarta.annotation.security.DenyAll;
                            import jakarta.annotation.security.PermitAll;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;

                            @Path("/override")
                            @DenyAll
                            public class OverrideResource {
                                public OverrideResource() {}

                                @GET
                                @PermitAll
                                public String get() { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.OverrideResource", "dev.vertique.test.OverrideResource_JaxRsDescriptor");

            assertInstanceOf(
                    SecurityPolicy.PermitAll.class,
                    metas.get(0).securityPolicy(),
                    "Method-level @PermitAll should override class-level @DenyAll");
        }
    }

    // --- Interface-backed resource ---

    @Nested
    @DisplayName("interface-backed resource — effective contract in descriptor output")
    class InterfaceBackedResource {

        @Test
        @DisplayName("concrete impl with no annotations, interface carries @Path + @GET + @PathParam")
        void interfaceBacked_correctMetadata() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.PetApi", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/pets")
                            public interface PetApi {
                                @GET
                                @Path("/{id}")
                                String getPet(@PathParam("id") String id);
                            }
                            """),
                    SourceFiles.inline("dev.vertique.test.PetResource", """
                            package dev.vertique.test;

                            public class PetResource implements PetApi {
                                public PetResource() {}

                                @Override
                                public String getPet(String id) { return id; }
                            }
                            """));

            result.assertSuccess();

            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.PetResource", "dev.vertique.test.PetResource_JaxRsDescriptor");

            assertEquals(1, metas.size(), "One method from interface");
            ResourceMethodMeta meta = metas.get(0);
            assertEquals("GET", meta.httpMethod());
            assertEquals("/pets/{id}", meta.path());
            assertEquals(1, meta.params().size());
            assertEquals("id", meta.params().get(0).name());
            assertEquals(
                    ResourceMethodMeta.ParamSource.PATH, meta.params().get(0).source());
        }
    }

    // --- Consumes/Produces media types ---

    @Nested
    @DisplayName("media types — consumes and produces from contract")
    class MediaTypes {

        @Test
        @DisplayName("@Consumes + @Produces on method — emitted in descriptor")
        void consumesProduces_emittedCorrectly() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.MediaResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.Consumes;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.Produces;

                            @Path("/media")
                            public class MediaResource {
                                public MediaResource() {}

                                @POST
                                @Consumes("application/json")
                                @Produces("application/json")
                                public String create(String body) { return body; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.MediaResource", "dev.vertique.test.MediaResource_JaxRsDescriptor");

            assertEquals(1, metas.size());
            ResourceMethodMeta.MediaTypes mediaTypes = metas.get(0).mediaTypes();
            assertFalse(mediaTypes.consumes().isEmpty(), "Consumes should not be empty");
            assertFalse(mediaTypes.produces().isEmpty(), "Produces should not be empty");
            assertTrue(mediaTypes.consumes().contains("application/json"));
            assertTrue(mediaTypes.produces().contains("application/json"));
        }
    }

    // --- String FQN resolution for common param types ---

    @Nested
    @DisplayName("param type resolution — common types resolve correctly")
    class ParamTypeResolution {

        @Test
        @DisplayName("int primitive parameter — resolves to int.class")
        void intParam_resolvesCorrectly() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.PageResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/pages")
                            public class PageResource {
                                public PageResource() {}

                                @GET
                                public String list(@QueryParam("page") int page) { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.PageResource", "dev.vertique.test.PageResource_JaxRsDescriptor");

            assertEquals(1, metas.size());
            ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
            assertEquals(int.class, paramMeta.type(), "int param should resolve to int.class");
            assertEquals(ResourceMethodMeta.ParamSource.QUERY, paramMeta.source());
            assertEquals("page", paramMeta.name());
        }

        @Test
        @DisplayName("@QueryParam String — resolves to String.class")
        void stringQueryParam_resolvesCorrectly() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.SearchResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/search")
                            public class SearchResource {
                                public SearchResource() {}

                                @GET
                                public String search(@QueryParam("q") String query) { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.SearchResource", "dev.vertique.test.SearchResource_JaxRsDescriptor");

            ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
            assertEquals(String.class, paramMeta.type());
            assertEquals("q", paramMeta.name());
        }

        @Test
        @DisplayName("blank @PathParam name preserved (CG-009 blank-name parity)")
        void blankParamName_preserved() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.BlankNameResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.QueryParam;

                            @Path("/blank")
                            public class BlankNameResource {
                                public BlankNameResource() {}

                                @GET
                                public String get(@QueryParam("") String q) { return ""; }
                            }
                            """));

            result.assertSuccess();
            List<ResourceMethodMeta> metas = callDescribe(
                    result,
                    "dev.vertique.test.BlankNameResource",
                    "dev.vertique.test.BlankNameResource_JaxRsDescriptor");

            ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);
            assertEquals("", paramMeta.name(), "Empty string param name must be preserved");
        }
    }

    // --- Literal-backed parameter annotations (GitHub issue #162, slice 3) ---

    /**
     * RED tests for literal-backed parameter annotations on {@code JaxRsDescriptorEmitter}'s
     * generated {@code describe()} output (GitHub issue #162, PRD-REST-018 §3.1b follow-up).
     *
     * <p>Before this slice, the descriptor's per-parameter {@code ParameterMetadata} view is
     * wrapped in a jaxrs-local {@code ReflectiveParameterMetadata} backed by a live
     * {@code support.effectiveParameterAnnotations(method, index)} reflective read — real
     * annotations are present, but resolved reflectively at class-registration time rather than
     * compile-time-literal-backed. These tests prove the fix: each parameter's runtime-retained
     * annotations are materialized at compile time into a literal-backed {@code ParameterMetadata}
     * implementation, so {@code findAnnotation}/{@code annotationsLazy()} resolve real values with
     * zero reflection at class-registration time (mirroring
     * {@code ExecutionPlanEmitter}'s {@code ParameterAnnotationLiterals} tests, slice 2).
     */
    @Nested
    @DisplayName("literal-backed parameter annotations (issue #162)")
    class ParameterAnnotationLiterals {

        @Test
        @DisplayName("@FilePart String[] and long members survive generated descriptor annotation lookup")
        void generatedPathFindsFilePartAnnotation() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.DescFileResource", """
                            package dev.vertique.test;

                            import dev.vertique.rest.core.request.FilePart;
                            import io.vertx.ext.web.FileUpload;
                            import jakarta.ws.rs.Consumes;
                            import jakarta.ws.rs.FormParam;
                            import jakarta.ws.rs.POST;
                            import jakarta.ws.rs.Path;

                            @Path("/desc-files")
                            public class DescFileResource {
                                public DescFileResource() {}

                                @POST
                                @Consumes("multipart/form-data")
                                public String upload(
                                        @FormParam("avatar")
                                        @FilePart(
                                                allowedTypes = {"image/png", "application/pdf"},
                                                maxSizeBytes = 4096L)
                                        FileUpload avatar) {
                                    return "ok";
                                }
                            }
                            """));

            result.assertSuccess();

            List<ResourceMethodMeta> metas = callDescribe(
                    result, "dev.vertique.test.DescFileResource", "dev.vertique.test.DescFileResource_JaxRsDescriptor");
            FilePart filePart = metas.getFirst()
                    .params()
                    .getFirst()
                    .findAnnotation(FilePart.class)
                    .orElseThrow();

            assertArrayEquals(new String[] {"image/png", "application/pdf"}, filePart.allowedTypes());
            assertEquals(4096L, filePart.maxSizeBytes());
        }

        @Test
        @DisplayName(
                "@PathParam + custom marker — described ParamMeta exposes the literal annotation, not a reflective read")
        void pathParamWithCustomAnnotation_describedParamMetaExposesLiteralAnnotation() throws Exception {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(), SourceFiles.inline("dev.vertique.test.DescMarkedResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/desc-marked")
                            public class DescMarkedResource {
                                public DescMarkedResource() {}

                                @GET
                                @Path("/{id}")
                                public String getMarked(@PathParam("id") @TestParamMarker("x") String id) {
                                    return id;
                                }
                            }
                            """));

            result.assertSuccess();

            String descriptorFqn = "dev.vertique.test.DescMarkedResource_JaxRsDescriptor";
            String descriptorSrc = generatedSource(result, descriptorFqn);

            // The descriptor itself must no longer call support.effectiveParameterAnnotations(...)
            // for this parameter — that call is replaced by a reference to the generated standalone
            // ParameterMetadata impl (a top-level sibling class, not nested in the descriptor).
            assertFalse(
                    descriptorSrc.contains("effectiveParameterAnnotations"),
                    "Generated descriptor must no longer call support.effectiveParameterAnnotations(...); "
                            + "generated source:\n" + descriptorSrc);

            // The TestParamMarker literal reference lives in the sibling metadata-impl source
            // (DescMarkedResource_JaxRsDescriptor_M0P0Meta), not the descriptor's own source — mirror
            // ExecutionPlanEmitterTest's equivalent assertion, which checks the analogous sibling file.
            String metaFqn = "dev.vertique.test.DescMarkedResource_JaxRsDescriptor_M0P0Meta";
            String metaSrc = generatedSource(result, metaFqn);
            boolean referencesMarkerLiteral =
                    metaSrc.contains("TestParamMarker$JaxRsLiteral") || metaSrc.contains("TestParamMarker.class");
            assertTrue(
                    referencesMarkerLiteral,
                    "Generated standalone ParameterMetadata impl must materialize a TestParamMarker literal for the "
                            + "parameter's reflection-free findAnnotation lookup; generated source:\n" + metaSrc);

            // Reflectively drive describe() to confirm the ParamMeta actually resolves the
            // TestParamMarker literal at runtime, with the correct occurrence value ("x").
            List<ResourceMethodMeta> metas =
                    callDescribe(result, "dev.vertique.test.DescMarkedResource", descriptorFqn);
            ResourceMethodMeta.ParamMeta paramMeta = metas.get(0).params().get(0);

            Class<?> markerClass =
                    result.generatedClassLoader().loadClass("dev.vertique.codegen.jaxrs.TestParamMarker");
            @SuppressWarnings("unchecked")
            var found = (java.util.Optional<Object>) paramMeta.findAnnotation((Class) markerClass);
            assertTrue(
                    found.isPresent(), "findAnnotation(TestParamMarker.class) must resolve the materialized literal");

            Method valueMethod = markerClass.getMethod("value");
            assertEquals(
                    "x", valueMethod.invoke(found.get()), "The materialized literal must carry its occurrence value");
        }

        @Test
        @DisplayName("described parameter annotation lookup is reflection-free")
        void parameterAnnotationLookup_isReflectionFree() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.DescMarkedNoReflectResource", """
                            package dev.vertique.test;

                            import dev.vertique.codegen.jaxrs.TestParamMarker;
                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;

                            @Path("/desc-marked-noreflect")
                            public class DescMarkedNoReflectResource {
                                public DescMarkedNoReflectResource() {}

                                @GET
                                @Path("/{id}")
                                public String get(@PathParam("id") @TestParamMarker("y") String id) {
                                    return id;
                                }
                            }
                            """));

            result.assertSuccess();

            String src = generatedSource(result, "dev.vertique.test.DescMarkedNoReflectResource_JaxRsDescriptor");

            assertFalse(
                    src.contains("effectiveParameterAnnotations"),
                    "Generated descriptor must not call support.effectiveParameterAnnotations(...); source:\n" + src);
            assertFalse(
                    src.contains(".getAnnotation("),
                    "Generated descriptor must not call getAnnotation(...); source:\n" + src);
            assertFalse(
                    src.contains("getParameterAnnotations"),
                    "Generated descriptor must not call getParameterAnnotations(...); source:\n" + src);
            assertFalse(
                    src.contains("getDeclaredAnnotations"),
                    "Generated descriptor must not call getDeclaredAnnotations(...); source:\n" + src);
        }

        @Test
        @DisplayName("@Context + @PathParam conflict — still detected via literal-backed annotations at compile time")
        void contextParamConflict_stillDetectedViaLiteralAnnotations() {
            var result = ProcessorTestHarness.run(
                    new JaxRsPipelineProcessor(),
                    SourceFiles.inline("dev.vertique.test.DescContextConflictResource", """
                            package dev.vertique.test;

                            import jakarta.ws.rs.GET;
                            import jakarta.ws.rs.Path;
                            import jakarta.ws.rs.PathParam;
                            import jakarta.ws.rs.core.Context;

                            @Path("/desc-context-conflict/{id}")
                            public class DescContextConflictResource {
                                public DescContextConflictResource() {}

                                @GET
                                public String get(@Context @PathParam("id") String id) {
                                    return id;
                                }
                            }
                            """));

            result.assertFailed();
            result.assertErrorMessage("also carries a value-binding annotation");
        }

        /**
         * Reads the generated source for the given FQN from the compilation result.
         *
         * @param result       the compilation result
         * @param generatedFqn the fully-qualified name of the generated class
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
    }
}
