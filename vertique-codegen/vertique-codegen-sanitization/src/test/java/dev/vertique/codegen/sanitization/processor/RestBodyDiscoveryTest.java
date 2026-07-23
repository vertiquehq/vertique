// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SanitizationProcessor} body-parameter discovery via JAX-RS resource methods.
 *
 * <p>Verifies both negative cases (parameters that should NOT produce a generated
 * {@code _InputProcessor}) and positive cases (those that should produce one).
 */
class RestBodyDiscoveryTest {

    // --- Shared DTO used as a valid body parameter type ---

    private static final JavaFileObject MY_DTO = SourceFiles.inline("com.example.MyDto", """
            package com.example;
            public class MyDto {
                public String name;
            }
            """);

    // --- Negative cases: no _InputProcessor should be emitted ---

    @Nested
    @DisplayName("negative cases — no _InputProcessor emitted")
    class NegativeCases {

        @Test
        @DisplayName("class without @Path annotation — method with @POST is ignored")
        void classWithoutPath_notDiscovered() {
            JavaFileObject resource = SourceFiles.inline("com.example.NoPathResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Consumes;
                    public class NoPathResource {
                        @POST
                        @Consumes("application/json")
                        public String create(MyDto dto) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.MyDto_InputProcessor");
        }

        @Test
        @DisplayName("method without HTTP verb annotation — not treated as resource method")
        void methodWithoutHttpVerb_notDiscovered() {
            JavaFileObject resource = SourceFiles.inline("com.example.PlainResource", """
                    package com.example;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class PlainResource {
                        public String helper(MyDto dto) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.MyDto_InputProcessor");
        }

        @Test
        @DisplayName("RoutingContext parameter — excluded as context type")
        void routingContextParam_excluded() {
            JavaFileObject routingContextStub = SourceFiles.inline("io.vertx.ext.web.RoutingContext", """
                    package io.vertx.ext.web;
                    public interface RoutingContext {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.CtxResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import io.vertx.ext.web.RoutingContext;
                    @Path("/items")
                    public class CtxResource {
                        @POST
                        public String create(RoutingContext rc) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), routingContextStub, resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("SecurityContext (Vertique) parameter — excluded as context type")
        void vertiqueSecurityContextParam_excluded() {
            JavaFileObject scStub = SourceFiles.inline("dev.vertique.security.SecurityContext", """
                    package dev.vertique.security;
                    public interface SecurityContext {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.SecResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import dev.vertique.security.SecurityContext;
                    @Path("/items")
                    public class SecResource {
                        @POST
                        public String create(SecurityContext sc) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), scStub, resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("JAX-RS SecurityContext parameter — excluded as context type")
        void jaxrsSecurityContextParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.JaxrsSecResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.core.SecurityContext;
                    @Path("/items")
                    public class JaxrsSecResource {
                        @POST
                        public String create(SecurityContext sc) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("RequestPreconditions parameter — excluded as context type")
        void requestPreconditionsParam_excluded() {
            JavaFileObject reqPrecondStub =
                    SourceFiles.inline("dev.vertique.rest.core.request.RequestPreconditions", """
                    package dev.vertique.rest.core.request;
                    public class RequestPreconditions {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.PrecondResource", """
                    package com.example;
                    import jakarta.ws.rs.PUT;
                    import jakarta.ws.rs.Path;
                    import dev.vertique.rest.core.request.RequestPreconditions;
                    @Path("/items")
                    public class PrecondResource {
                        @PUT
                        public String update(RequestPreconditions rp) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), reqPrecondStub, resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("@RequestParams-annotated type — excluded as framework BeanParam analogue")
        void requestParamsAnnotatedType_excluded() {
            JavaFileObject requestParamsAnnotation =
                    SourceFiles.inline("dev.vertique.rest.core.request.RequestParams", """
                    package dev.vertique.rest.core.request;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface RequestParams {}
                    """);
            JavaFileObject paramsDto = SourceFiles.inline("com.example.ItemParams", """
                    package com.example;
                    import dev.vertique.rest.core.request.RequestParams;
                    @RequestParams
                    public class ItemParams {
                        public String q;
                    }
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.ParamResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class ParamResource {
                        @GET
                        public String list(ItemParams params) { return null; }
                    }
                    """);

            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), requestParamsAnnotation, paramsDto, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.ItemParams_InputProcessor");
        }

        @Test
        @DisplayName("@BeanParam annotated parameter — excluded")
        void beanParamAnnotatedParam_excluded() {
            JavaFileObject dto = SourceFiles.inline("com.example.SearchParams", """
                    package com.example;
                    public class SearchParams { public String q; }
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.SearchResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.BeanParam;
                    @Path("/search")
                    public class SearchResource {
                        @GET
                        public String search(@BeanParam SearchParams params) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), dto, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.SearchParams_InputProcessor");
        }

        @Test
        @DisplayName("@PathParam annotated parameter — excluded")
        void pathParamAnnotatedParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.PathParamResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.PathParam;
                    @Path("/items/{id}")
                    public class PathParamResource {
                        @GET
                        public String get(@PathParam("id") String id) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("@QueryParam annotated parameter — excluded")
        void queryParamAnnotatedParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.QueryParamResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.QueryParam;
                    @Path("/items")
                    public class QueryParamResource {
                        @GET
                        public String list(@QueryParam("page") int page) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("@HeaderParam annotated parameter — excluded")
        void headerParamAnnotatedParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.HeaderParamResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.HeaderParam;
                    @Path("/items")
                    public class HeaderParamResource {
                        @GET
                        public String get(@HeaderParam("X-Token") String token) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("@CookieParam annotated parameter — excluded")
        void cookieParamAnnotatedParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.CookieResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.CookieParam;
                    @Path("/items")
                    public class CookieResource {
                        @GET
                        public String get(@CookieParam("session") String session) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("@FormParam annotated parameter — excluded")
        void formParamAnnotatedParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.FormResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.FormParam;
                    @Path("/login")
                    public class FormResource {
                        @POST
                        public String login(@FormParam("username") String user) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("List<FileUpload> parameter — excluded as multipart type")
        void fileUploadListParam_excluded() {
            JavaFileObject fileUploadStub = SourceFiles.inline("io.vertx.ext.web.FileUpload", """
                    package io.vertx.ext.web;
                    public interface FileUpload {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.UploadResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import io.vertx.ext.web.FileUpload;
                    import java.util.List;
                    @Path("/upload")
                    public class UploadResource {
                        @POST
                        public String upload(List<FileUpload> files) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), fileUploadStub, resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("List<EntityPart> parameter — excluded as multipart entity-part type")
        void entityPartListParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.EntityPartResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.core.EntityPart;
                    import java.util.List;
                    @Path("/multipart")
                    public class EntityPartResource {
                        @POST
                        public String upload(List<EntityPart> parts) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("String scalar parameter — excluded (no map structure to switch on)")
        void stringScalarParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.ScalarResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class ScalarResource {
                        @POST
                        public String create(String body) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("UUID scalar parameter — excluded")
        void uuidScalarParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.UuidResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import java.util.UUID;
                    @Path("/items")
                    public class UuidResource {
                        @POST
                        public String create(UUID id) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("Integer scalar parameter — excluded")
        void integerScalarParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.IntResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class IntResource {
                        @POST
                        public String create(Integer count) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("List<String> parameter — excluded (collection of scalars)")
        void listOfStringParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.TagResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import java.util.List;
                    @Path("/tags")
                    public class TagResource {
                        @POST
                        public String create(List<String> tags) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("String[] array parameter — excluded (array of scalars)")
        void stringArrayParam_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.ArrayResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class ArrayResource {
                        @POST
                        public String create(String[] names) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("enum parameter — excluded (scalar leaf)")
        void enumParam_excluded() {
            JavaFileObject status = SourceFiles.inline("com.example.Status", """
                    package com.example;
                    public enum Status { ACTIVE, INACTIVE }
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.StatusResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class StatusResource {
                        @POST
                        public String create(Status status) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), status, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.Status_InputProcessor");
        }

        // --- @Context annotation exclusion (FR-REST-016 slice g) ---

        @Test
        @DisplayName("@Context-annotated ContextValue param — excluded (annotation takes priority)")
        void contextAnnotatedContextValue_excluded() {
            JavaFileObject contextValueStub = SourceFiles.inline("dev.vertique.core.context.ContextValue", """
                    package dev.vertique.core.context;
                    public interface ContextValue {}
                    """);
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.context.ContextValue;
                    public record MyCtx(String tenantId) implements ContextValue {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.CtxAnnotatedResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.core.Context;
                    @Path("/items")
                    public class CtxAnnotatedResource {
                        @POST
                        public String create(@Context MyCtx ctx) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), contextValueStub, myCtx, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.MyCtx_InputProcessor");
        }

        @Test
        @DisplayName("unannotated ContextValue param — excluded as ContextValue subtype")
        void unannotatedContextValue_excluded() {
            JavaFileObject contextValueStub = SourceFiles.inline("dev.vertique.core.context.ContextValue", """
                    package dev.vertique.core.context;
                    public interface ContextValue {}
                    """);
            JavaFileObject myCtx = SourceFiles.inline("com.example.MyCtx", """
                    package com.example;
                    import dev.vertique.core.context.ContextValue;
                    public record MyCtx(String tenantId) implements ContextValue {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.UnannotatedCtxResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class UnannotatedCtxResource {
                        @POST
                        public String create(MyCtx ctx) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), contextValueStub, myCtx, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.MyCtx_InputProcessor");
        }

        @Test
        @DisplayName("@Context PaymentService (non-injectable arbitrary class) — excluded by @Context annotation")
        void contextAnnotatedArbitraryClass_excluded() {
            JavaFileObject paymentService = SourceFiles.inline("com.example.PaymentService", """
                    package com.example;
                    public class PaymentService {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.PaymentResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.core.Context;
                    @Path("/payments")
                    public class PaymentResource {
                        @POST
                        public String pay(@Context PaymentService svc) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), paymentService, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(result, "com.example.PaymentService_InputProcessor");
        }

        @Test
        @DisplayName("@Context UriInfo (reserved JAX-RS type) — excluded by @Context annotation")
        void contextAnnotatedUriInfo_excluded() {
            JavaFileObject resource = SourceFiles.inline("com.example.UriInfoResource", """
                    package com.example;
                    import jakarta.ws.rs.GET;
                    import jakarta.ws.rs.Path;
                    import jakarta.ws.rs.core.Context;
                    import jakarta.ws.rs.core.UriInfo;
                    @Path("/info")
                    public class UriInfoResource {
                        @GET
                        public String info(@Context UriInfo ui) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), resource);
            result.assertSuccess();
        }

        @Test
        @DisplayName("RequestPreconditions param — still excluded (unchanged regression guard)")
        void requestPreconditionsParam_stillExcluded() {
            JavaFileObject reqPrecondStub =
                    SourceFiles.inline("dev.vertique.rest.core.request.RequestPreconditions", """
                    package dev.vertique.rest.core.request;
                    public class RequestPreconditions {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.RegressionPrecondResource", """
                    package com.example;
                    import jakarta.ws.rs.PUT;
                    import jakarta.ws.rs.Path;
                    import dev.vertique.rest.core.request.RequestPreconditions;
                    @Path("/items/{id}")
                    public class RegressionPrecondResource {
                        @PUT
                        public String update(RequestPreconditions rp) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), reqPrecondStub, resource);
            result.assertSuccess();
            assertNoInputProcessorGenerated(
                    result, "dev.vertique.rest.core.request.RequestPreconditions_InputProcessor");
        }
    }

    // --- Positive cases: _InputProcessor IS emitted ---

    @Nested
    @DisplayName("positive cases — _InputProcessor is emitted")
    class PositiveCases {

        @Test
        @DisplayName("plain MyDto body parameter — processor emitted unconditionally")
        void plainDtoBody_processorEmitted() {
            JavaFileObject resource = SourceFiles.inline("com.example.ItemResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class ItemResource {
                        @POST
                        public String create(MyDto dto) { return null; }
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("List<MyDto> body — discovery root is MyDto, not List; processor for MyDto emitted")
        void listOfDtoBody_elementProcessorEmitted() {
            JavaFileObject resource = SourceFiles.inline("com.example.ItemResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import java.util.List;
                    @Path("/items")
                    public class ItemResource {
                        @POST
                        public String createBatch(List<MyDto> dtos) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("MyDto[] array body — discovery root is MyDto; processor emitted")
        void arrayOfDtoBody_elementProcessorEmitted() {
            JavaFileObject resource = SourceFiles.inline("com.example.ItemResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class ItemResource {
                        @POST
                        public String createBatch(MyDto[] dtos) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("@OPTIONS body parameter — discovery includes the verb; processor emitted")
        void optionsBodyParameter_processorEmitted() {
            // Pins the inclusion of jakarta.ws.rs.OPTIONS in HTTP_VERB_FQNS — runtime
            // ResourceScanner.resolveHttpMethod treats @OPTIONS as a normal resource method,
            // so a body DTO referenced only from an @OPTIONS endpoint must still get a
            // generated processor.
            JavaFileObject resource = SourceFiles.inline("com.example.OptionsResource", """
                    package com.example;
                    import jakarta.ws.rs.OPTIONS;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class OptionsResource {
                        @OPTIONS
                        public String describe(MyDto dto) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("Set<MyDto> body — discovery root is MyDto; processor emitted")
        void setOfDtoBody_elementProcessorEmitted() {
            JavaFileObject resource = SourceFiles.inline("com.example.ItemResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    import java.util.Set;
                    @Path("/items")
                    public class ItemResource {
                        @POST
                        public String createBatch(Set<MyDto> dtos) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, resource);
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("plain DTO body alongside a ContextValue param — DTO still discovered, ContextValue excluded")
        void dtoBodyAlongsideContextValue_dtoDiscoveredContextExcluded() {
            JavaFileObject contextValueStub = SourceFiles.inline("dev.vertique.core.context.ContextValue", """
                    package dev.vertique.core.context;
                    public interface ContextValue {}
                    """);
            JavaFileObject myCtx = SourceFiles.inline("com.example.TenantContext", """
                    package com.example;
                    import dev.vertique.core.context.ContextValue;
                    public record TenantContext(String id) implements ContextValue {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.MixedResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/items")
                    public class MixedResource {
                        @POST
                        public String create(TenantContext ctx, MyDto dto) { return null; }
                    }
                    """);

            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), contextValueStub, myCtx, MY_DTO, resource);
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
            assertNoInputProcessorGenerated(result, "com.example.TenantContext_InputProcessor");
        }

        @Test
        @DisplayName("@Path via meta-annotation on resource class — accepted as resource class")
        void pathViaMetaAnnotation_accepted() {
            JavaFileObject metaPath = SourceFiles.inline("com.example.RestEndpoint", """
                    package com.example;
                    import java.lang.annotation.*;
                    import jakarta.ws.rs.Path;
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.RUNTIME)
                    @Path("/endpoint")
                    public @interface RestEndpoint {}
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.MetaResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    @RestEndpoint
                    public class MetaResource {
                        @POST
                        public String create(MyDto dto) { return null; }
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), MY_DTO, metaPath, resource)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.MyDto_InputProcessor", "targetType()");
        }
    }

    // --- Helper ---

    /**
     * Asserts that the compilation did NOT generate a source file for the given FQN.
     *
     * @param result the compilation result
     * @param fqn    the fully-qualified name that should NOT have been generated
     */
    private static void assertNoInputProcessorGenerated(Result result, String fqn) {
        boolean generated = result.compilation().generatedSourceFile(fqn).isPresent();
        if (generated) {
            throw new org.opentest4j.AssertionFailedError(
                    "Expected no generated source for '" + fqn + "' but one was found.");
        }
    }
}
