// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.validation.ValidateWith;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.RequiresAction;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.EntityPart;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JaxRsRouteRegistrarTest {

    private JaxRsRouteRegistrar registrar;
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        registrar = new JaxRsRouteRegistrar();
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    // --- Test resource classes ---

    @Path("/test")
    static class SimpleResource {
        @GET
        public Future<String> getAll() {
            return Future.succeededFuture("all");
        }

        @GET
        @Path("/{id}")
        public Future<String> getById(@PathParam("id") String id) {
            return Future.succeededFuture(id);
        }

        @POST
        public Future<String> create(String body) {
            return Future.succeededFuture(body);
        }

        @DELETE
        @Path("/{id}")
        public Future<Void> delete(@PathParam("id") String id) {
            return Future.succeededFuture();
        }

        // Non-annotated method should be skipped
        public String helper() {
            return "not a resource method";
        }
    }

    @Path("/custom")
    static class CustomOperationIdResource {
        @GET
        @Operation(operationId = "myCustomOp")
        public Future<String> someMethodName() {
            return Future.succeededFuture("custom");
        }
    }

    @Path("/params")
    static class ParamTypesResource {
        @GET
        public Future<String> withParams(
                @QueryParam("q") String query,
                @HeaderParam("X-Token") String token,
                @CookieParam("session") String session) {
            return Future.succeededFuture("ok");
        }

        @POST
        @Path("/{id}")
        public Future<String> withContext(@PathParam("id") int id, RoutingContext ctx) {
            return Future.succeededFuture("ok");
        }
    }

    static class NoPathResource {
        @GET
        public String noPath() {
            return "nope";
        }
    }

    @Path("/sync")
    static class SyncResource {
        @GET
        public String syncMethod() {
            return "sync";
        }
    }

    @Path("/all-methods")
    static class AllHttpMethodsResource {
        @GET
        public Future<String> doGet() {
            return Future.succeededFuture();
        }

        @POST
        public Future<String> doPost() {
            return Future.succeededFuture();
        }

        @PUT
        public Future<String> doPut() {
            return Future.succeededFuture();
        }

        @DELETE
        public Future<Void> doDelete() {
            return Future.succeededFuture();
        }

        @PATCH
        public Future<String> doPatch() {
            return Future.succeededFuture();
        }

        @HEAD
        public Future<Void> doHead() {
            return Future.succeededFuture();
        }

        @OPTIONS
        public Future<String> doOptions() {
            return Future.succeededFuture();
        }
    }

    @Path("/multi-body")
    static class MultipleBodyParamsResource {
        @POST
        @Operation(operationId = "multiBody")
        public Future<String> create(String body1, String body2) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/dup-a")
    static class DuplicateOpIdResourceA {
        @GET
        @Operation(operationId = "sharedOp")
        public Future<String> methodA() {
            return Future.succeededFuture();
        }
    }

    @Path("/dup-b")
    static class DuplicateOpIdResourceB {
        @GET
        @Operation(operationId = "sharedOp")
        public Future<String> methodB() {
            return Future.succeededFuture();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Should discover GET, POST, DELETE methods and skip helper")
    void shouldDiscoverAnnotatedMethods() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new SimpleResource());

        assertEquals(4, methods.size());

        ResourceMethodMeta getAll = findByOperationId(methods, "getAll");
        assertNotNull(getAll);
        assertEquals("GET", getAll.httpMethod());
        assertEquals("/test", getAll.path());
        assertTrue(getAll.params().isEmpty());
        assertTrue(getAll.returnsFuture());
        assertFalse(getAll.returnsVoid());

        ResourceMethodMeta getById = findByOperationId(methods, "getById");
        assertNotNull(getById);
        assertEquals("GET", getById.httpMethod());
        assertEquals("/test/{id}", getById.path());
        assertEquals(1, getById.params().size());
        assertEquals(
                ResourceMethodMeta.ParamSource.PATH, getById.params().get(0).source());
        assertEquals("id", getById.params().get(0).name());

        ResourceMethodMeta create = findByOperationId(methods, "create");
        assertNotNull(create);
        assertEquals("POST", create.httpMethod());
        assertEquals("/test", create.path());
        assertEquals(1, create.params().size());
        assertEquals(ResourceMethodMeta.ParamSource.BODY, create.params().get(0).source());

        ResourceMethodMeta delete = findByOperationId(methods, "delete");
        assertNotNull(delete);
        assertEquals("DELETE", delete.httpMethod());
        assertTrue(delete.returnsFuture());
        assertTrue(delete.returnsVoid());
        assertEquals(Void.class, delete.responseBodyType());
    }

    @Test
    @DisplayName("Should prefer @Operation(operationId) over method name")
    void shouldUseCustomOperationId() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new CustomOperationIdResource());

        assertEquals(1, methods.size());
        assertEquals("myCustomOp", methods.get(0).operationId());
    }

    @Test
    @DisplayName("Should detect all parameter source types including RoutingContext")
    void shouldResolveAllParamTypes() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ParamTypesResource());

        ResourceMethodMeta withParams = findByOperationId(methods, "withParams");
        assertNotNull(withParams);
        assertEquals(3, withParams.params().size());

        assertEquals(
                ResourceMethodMeta.ParamSource.QUERY, withParams.params().get(0).source());
        assertEquals("q", withParams.params().get(0).name());

        assertEquals(
                ResourceMethodMeta.ParamSource.HEADER,
                withParams.params().get(1).source());
        assertEquals("X-Token", withParams.params().get(1).name());

        assertEquals(
                ResourceMethodMeta.ParamSource.COOKIE,
                withParams.params().get(2).source());
        assertEquals("session", withParams.params().get(2).name());

        ResourceMethodMeta withContext = findByOperationId(methods, "withContext");
        assertNotNull(withContext);
        assertEquals(2, withContext.params().size());

        assertEquals(
                ResourceMethodMeta.ParamSource.PATH, withContext.params().get(0).source());
        assertEquals("id", withContext.params().get(0).name());
        assertEquals(int.class, withContext.params().get(0).type());

        assertEquals(
                ResourceMethodMeta.ParamSource.CONTEXT,
                withContext.params().get(1).source());
        assertEquals(RoutingContext.class, withContext.params().get(1).type());
    }

    @Test
    @DisplayName("Should skip classes without @Path annotation")
    void shouldSkipClassWithoutPath() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new NoPathResource());
        assertTrue(methods.isEmpty());
    }

    @Test
    @DisplayName("Should detect sync (non-Future) return types")
    void shouldDetectSyncReturnType() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new SyncResource());

        assertEquals(1, methods.size());
        ResourceMethodMeta meta = methods.get(0);
        assertFalse(meta.returnsFuture());
        assertFalse(meta.returnsVoid());
        assertEquals(String.class, meta.responseBodyType());
    }

    @Test
    @DisplayName("Should detect all HTTP method types including HEAD and OPTIONS")
    void shouldDetectAllHttpMethods() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new AllHttpMethodsResource());

        assertEquals(7, methods.size());
        assertNotNull(findByHttpMethod(methods, "GET"));
        assertNotNull(findByHttpMethod(methods, "POST"));
        assertNotNull(findByHttpMethod(methods, "PUT"));
        assertNotNull(findByHttpMethod(methods, "DELETE"));
        assertNotNull(findByHttpMethod(methods, "PATCH"));
        assertNotNull(findByHttpMethod(methods, "HEAD"));
        assertNotNull(findByHttpMethod(methods, "OPTIONS"));
    }

    @Test
    @DisplayName("Should correctly combine class and method paths")
    void shouldCombinePaths() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new SimpleResource());

        ResourceMethodMeta getAll = findByOperationId(methods, "getAll");
        assertEquals("/test", getAll.path());

        ResourceMethodMeta getById = findByOperationId(methods, "getById");
        assertEquals("/test/{id}", getById.path());
    }

    @Path("/base")
    static class BaseResource {
        @GET
        @Operation(operationId = "baseGet")
        public Future<String> getAll() {
            return Future.succeededFuture("base");
        }

        @POST
        @Operation(operationId = "baseCreate")
        public Future<String> create(String body) {
            return Future.succeededFuture(body);
        }
    }

    @Path("/child")
    static class ChildResource extends BaseResource {
        @Override
        @GET
        @Operation(operationId = "childGet")
        public Future<String> getAll() {
            return Future.succeededFuture("child");
        }

        @DELETE
        @Operation(operationId = "childDelete")
        public Future<Void> delete() {
            return Future.succeededFuture();
        }
    }

    @Test
    @DisplayName("Should discover inherited methods from superclass")
    void shouldDiscoverInheritedMethods() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ChildResource());

        // childGet (override), childDelete (own), baseCreate (inherited)
        assertEquals(3, methods.size());
        assertNotNull(findByOperationId(methods, "childGet"));
        assertNotNull(findByOperationId(methods, "childDelete"));
        assertNotNull(findByOperationId(methods, "baseCreate"));

        // baseGet should NOT be present (overridden by childGet)
        assertNull(findByOperationId(methods, "baseGet"));
    }

    @Test
    @DisplayName("Should use subclass @Path for inherited methods")
    void shouldUseChildPathForInheritedMethods() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ChildResource());

        ResourceMethodMeta inherited = findByOperationId(methods, "baseCreate");
        assertNotNull(inherited);
        // The @Path comes from ChildResource, not BaseResource
        assertEquals("/child", inherited.path());
    }

    // --- File upload and content type test resource classes ---

    @Path("/form")
    static class FormParamResource {
        @POST
        @Operation(operationId = "uploadFile")
        @Consumes("multipart/form-data")
        public Future<String> upload(@FormParam("file") FileUpload file, @FormParam("description") String description) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/form-entity")
    static class FormParamEntityPartResource {
        @POST
        @Operation(operationId = "uploadEntityPart")
        @Consumes("multipart/form-data")
        public Future<String> upload(@FormParam("file") EntityPart file) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/form-list")
    static class FormParamListResource {
        @POST
        @Operation(operationId = "uploadFiles")
        @Consumes("multipart/form-data")
        public Future<String> upload(@FormParam("files") List<FileUpload> files) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/file-uploads")
    static class FileUploadsResource {
        @POST
        @Operation(operationId = "allUploads")
        public Future<String> upload(List<FileUpload> files) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/entity-parts")
    static class EntityPartsResource {
        @POST
        @Operation(operationId = "allParts")
        public Future<String> upload(List<EntityPart> parts) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/form-body-conflict")
    static class FormAndBodyConflictResource {
        @POST
        @Operation(operationId = "formBodyConflict")
        public Future<String> upload(@FormParam("field") String field, String body) {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/consumes-produces")
    @Consumes("application/json")
    @Produces("application/json")
    static class ConsumesProducesResource {
        @GET
        @Operation(operationId = "defaultProduces")
        public Future<String> getDefault() {
            return Future.succeededFuture("ok");
        }

        @POST
        @Operation(operationId = "overrideConsumes")
        @Consumes("multipart/form-data")
        @Produces("text/csv")
        public Future<String> upload(@FormParam("file") FileUpload file) {
            return Future.succeededFuture("ok");
        }
    }

    // --- registerAll validation tests ---

    @Test
    @DisplayName("Should reject multiple body parameters")
    void shouldRejectMultipleBodyParams() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new MultipleBodyParamsResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.MULTIPLE_BODY_PARAMS,
                ex.violations().get(0).type());
        assertEquals("multiBody", ex.violations().get(0).operationId());
    }

    @Test
    @DisplayName("Should reject duplicate operationIds")
    void shouldRejectDuplicateOperationIds() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new DuplicateOpIdResourceA(), new DuplicateOpIdResourceB()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.DUPLICATE_OPERATION_ID,
                ex.violations().get(0).type());
        assertTrue(ex.violations().get(0).message().contains("sharedOp"));
    }

    // --- File upload and content type tests ---

    @Test
    @DisplayName("Should detect @FormParam with FileUpload and String types")
    void shouldDetectFormParamWithFileUpload() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new FormParamResource());

        ResourceMethodMeta meta = findByOperationId(methods, "uploadFile");
        assertNotNull(meta);
        assertEquals(2, meta.params().size());

        ResourceMethodMeta.ParamMeta fileParam = meta.params().get(0);
        assertEquals(ResourceMethodMeta.ParamSource.FORM, fileParam.source());
        assertEquals("file", fileParam.name());
        assertEquals(FileUpload.class, fileParam.type());

        ResourceMethodMeta.ParamMeta descParam = meta.params().get(1);
        assertEquals(ResourceMethodMeta.ParamSource.FORM, descParam.source());
        assertEquals("description", descParam.name());
        assertEquals(String.class, descParam.type());
    }

    @Test
    @DisplayName("Should detect @FormParam with EntityPart type")
    void shouldDetectFormParamWithEntityPart() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new FormParamEntityPartResource());

        ResourceMethodMeta meta = findByOperationId(methods, "uploadEntityPart");
        assertNotNull(meta);
        assertEquals(1, meta.params().size());

        ResourceMethodMeta.ParamMeta param = meta.params().get(0);
        assertEquals(ResourceMethodMeta.ParamSource.FORM, param.source());
        assertEquals("file", param.name());
        assertEquals(EntityPart.class, param.type());
    }

    @Test
    @DisplayName("Should detect @FormParam with List<FileUpload> and componentType=FileUpload")
    void shouldDetectFormParamWithList() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new FormParamListResource());

        ResourceMethodMeta meta = findByOperationId(methods, "uploadFiles");
        assertNotNull(meta);
        assertEquals(1, meta.params().size());

        ResourceMethodMeta.ParamMeta param = meta.params().get(0);
        assertEquals(ResourceMethodMeta.ParamSource.FORM, param.source());
        assertEquals("files", param.name());
        assertEquals(List.class, param.type());
        assertEquals(FileUpload.class, param.componentType());
    }

    @Test
    @DisplayName("Should detect unannotated List<FileUpload> as FILE_UPLOADS source")
    void shouldDetectUnannotatedFileUploadList() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new FileUploadsResource());

        ResourceMethodMeta meta = findByOperationId(methods, "allUploads");
        assertNotNull(meta);
        assertEquals(1, meta.params().size());

        ResourceMethodMeta.ParamMeta param = meta.params().get(0);
        assertEquals(ResourceMethodMeta.ParamSource.FILE_UPLOADS, param.source());
        assertEquals(List.class, param.type());
        assertEquals(FileUpload.class, param.componentType());
    }

    @Test
    @DisplayName("Should detect unannotated List<EntityPart> as ENTITY_PARTS source")
    void shouldDetectUnannotatedEntityPartList() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new EntityPartsResource());

        ResourceMethodMeta meta = findByOperationId(methods, "allParts");
        assertNotNull(meta);
        assertEquals(1, meta.params().size());

        ResourceMethodMeta.ParamMeta param = meta.params().get(0);
        assertEquals(ResourceMethodMeta.ParamSource.ENTITY_PARTS, param.source());
        assertEquals(List.class, param.type());
        assertEquals(EntityPart.class, param.componentType());
    }

    @Test
    @DisplayName("Should reject method mixing @FormParam with a body parameter")
    void shouldRejectFormAndBodyConflict() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new FormAndBodyConflictResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertEquals(1, ex.violations().size());
        assertEquals(
                RouteRegistrationViolation.ViolationType.FORM_AND_BODY_CONFLICT,
                ex.violations().get(0).type());
        assertEquals("formBodyConflict", ex.violations().get(0).operationId());
    }

    @Test
    @DisplayName("Should resolve class-level @Consumes/@Produces and allow method-level override")
    void shouldResolveConsumesAndProduces() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ConsumesProducesResource());

        ResourceMethodMeta defaultMeta = findByOperationId(methods, "defaultProduces");
        assertNotNull(defaultMeta);
        assertEquals(List.of("application/json"), defaultMeta.mediaTypes().consumes());
        assertEquals(List.of("application/json"), defaultMeta.mediaTypes().produces());

        ResourceMethodMeta overrideMeta = findByOperationId(methods, "overrideConsumes");
        assertNotNull(overrideMeta);
        assertEquals(List.of("multipart/form-data"), overrideMeta.mediaTypes().consumes());
        assertEquals(List.of("text/csv"), overrideMeta.mediaTypes().produces());
    }

    @Path("/conflicting")
    static class ConflictingSecurityResource {
        @GET
        @Operation(operationId = "conflictingOp")
        @DenyAll
        @RolesAllowed("admin")
        public Future<String> conflicting() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("Should throw SecurityPolicyViolationException for method with conflicting security annotations")
    void shouldThrowForConflictingAnnotations() {
        SecurityPolicyViolationException ex = assertThrows(
                SecurityPolicyViolationException.class,
                () -> registrar.scanResource(new ConflictingSecurityResource()));

        assertEquals(1, ex.violations().size());
        SecurityPolicyViolation violation = ex.violations().get(0);
        assertEquals("conflictingOp", violation.operationId());
        assertEquals(SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS, violation.type());
    }

    /**
     * Resource with class-level {@code @PermitAll} but method-level {@code @RolesAllowed}. Jakarta
     * EE semantics require the method annotation to override the class annotation, producing a
     * {@link SecurityPolicy.Constrained} policy — NOT a conflict.
     */
    @Path("/override")
    @PermitAll
    static class ClassPermitAllMethodRolesResource {
        @GET
        @Operation(operationId = "overrideOp")
        @RolesAllowed("user")
        public Future<String> restricted() {
            return Future.succeededFuture("ok");
        }

        @GET
        @Path("/open")
        @Operation(operationId = "openOp")
        @PermitAll
        public Future<String> open() {
            return Future.succeededFuture("open");
        }
    }

    /**
     * Resource with class-level {@code @PermitAll} and method-level {@code @Authorized(scopes =
     * "write")}. Method-level must override class-level producing a {@link
     * SecurityPolicy.Constrained} policy.
     */
    @Path("/scopeoverride")
    @PermitAll
    static class ClassPermitAllMethodAuthorizedResource {
        @GET
        @Operation(operationId = "scopedOp")
        @Authorized(scopes = "write")
        public Future<String> scoped() {
            return Future.succeededFuture("scoped");
        }
    }

    @Test
    @DisplayName("Method-level @RolesAllowed overrides class-level @PermitAll (not a conflict)")
    void methodRolesAllowedOverridesClassPermitAll() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ClassPermitAllMethodRolesResource());

        assertEquals(2, methods.size());

        ResourceMethodMeta restrictedMeta = findByOperationId(methods, "overrideOp");
        assertNotNull(restrictedMeta);
        assertInstanceOf(SecurityPolicy.Constrained.class, restrictedMeta.securityPolicy());
        SecurityPolicy.Constrained constrained = (SecurityPolicy.Constrained) restrictedMeta.securityPolicy();
        assertEquals(List.of("user"), constrained.requiredRoles());

        ResourceMethodMeta openMeta = findByOperationId(methods, "openOp");
        assertNotNull(openMeta);
        assertInstanceOf(SecurityPolicy.PermitAll.class, openMeta.securityPolicy());
    }

    @Test
    @DisplayName("Method-level @Authorized overrides class-level @PermitAll (not a conflict)")
    void methodAuthorizedOverridesClassPermitAll() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ClassPermitAllMethodAuthorizedResource());

        assertEquals(1, methods.size());
        ResourceMethodMeta meta = methods.get(0);
        assertInstanceOf(SecurityPolicy.Constrained.class, meta.securityPolicy());
        SecurityPolicy.Constrained constrained = (SecurityPolicy.Constrained) meta.securityPolicy();
        assertEquals(List.of("write"), constrained.requiredScopes());
    }

    @Path("/emptyroles")
    static class EmptyRolesResource {
        @GET
        @Operation(operationId = "emptyRolesOp")
        @RolesAllowed({})
        public Future<String> emptyRoles() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("Should throw SecurityPolicyViolationException for @RolesAllowed with empty array")
    void shouldThrowForEmptyRolesAllowed() {
        SecurityPolicyViolationException ex = assertThrows(
                SecurityPolicyViolationException.class, () -> registrar.scanResource(new EmptyRolesResource()));

        assertEquals(1, ex.violations().size());
        SecurityPolicyViolation violation = ex.violations().get(0);
        assertEquals("emptyRolesOp", violation.operationId());
        assertEquals(SecurityPolicyViolation.ViolationType.EMPTY_ROLES_ALLOWED, violation.type());
        assertTrue(violation.message().contains("emptyRolesOp"));
    }

    // --- @ValidateWith test resource ---

    /** Marker interface used as a validation group in tests. */
    interface Create {}

    @Path("/validate")
    static class ValidateWithResource {
        @POST
        @Operation(operationId = "createWithGroups")
        @ValidateWith({Create.class})
        public void create() {}

        @GET
        @Operation(operationId = "getWithoutGroups")
        public void get() {}
    }

    @Test
    @DisplayName("Should read @ValidateWith groups from method")
    void shouldReadValidateWithGroups() {
        List<ResourceMethodMeta> methods = registrar.scanResource(new ValidateWithResource());

        ResourceMethodMeta createMethod = methods.stream()
                .filter(m -> m.operationId().equals("createWithGroups"))
                .findFirst()
                .orElseThrow();
        assertNotNull(createMethod.validationGroups());
        assertEquals(1, createMethod.validationGroups().length);
        assertEquals(Create.class, createMethod.validationGroups()[0]);

        ResourceMethodMeta getMethod = methods.stream()
                .filter(m -> m.operationId().equals("getWithoutGroups"))
                .findFirst()
                .orElseThrow();
        assertNull(getMethod.validationGroups());
    }

    // --- @RequiresAction discovery + startup validation (slice 11) ---

    @Path("/action")
    static class RequiresActionResource {
        @GET
        @Operation(operationId = "actionGet")
        @RequiresAction("cms.content.read")
        public Future<String> read() {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/unknown-action")
    static class UnregisteredActionResource {
        @GET
        @Operation(operationId = "unknownActionGet")
        @RequiresAction("cms.unknown.read")
        public Future<String> read() {
            return Future.succeededFuture("ok");
        }
    }

    @Path("/action-permit")
    static class RequiresActionAndPermitAllResource {
        @GET
        @Operation(operationId = "actionPermitGet")
        @RequiresAction("cms.content.read")
        @PermitAll
        public Future<String> read() {
            return Future.succeededFuture("ok");
        }
    }

    @Test
    @DisplayName("Should populate requiredAction on OperationRegistrationContext from method @RequiresAction")
    void registrar_extractsRequiredAction_fromMethodAnnotation() {
        CapturingContributor capturing = new CapturingContributor();

        // authEnabled=true: the engine AND the REST enforcement pipeline are both present, the
        // realistic case for an enforceable @RequiresAction route (fail-closed C1).
        RegistrarTestSupport.registerAll(
                registrar,
                Set.of(new RequiresActionResource()),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                List.of(),
                List.of(capturing),
                null,
                true,
                List.of(),
                List.of(),
                "OFF",
                null,
                null,
                new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                // Authorizer present: the complete enforceable graph for a @RequiresAction route.
                true);

        assertNotNull(capturing.captured);
        assertEquals(Optional.of(ActionRef.parse("cms.content.read")), capturing.captured.requiredAction());
    }

    @Test
    @DisplayName("Should fail startup when @RequiresAction is not registered in the ActionRegistry")
    void registrar_unregisteredAction_throwsAtStartup() {
        // authEnabled=true so the only failure cause is the unregistered action (not the absent
        // enforcement pipeline — that is exercised separately in RouteValidatorActionGateTest).
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new UnregisteredActionResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        true,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                        // Authorizer present: the only failure cause is the one under test.
                        true));

        assertTrue(ex.violations().stream().anyMatch(v -> "unknownActionGet".equals(v.operationId())));
    }

    @Test
    @DisplayName("Should fail startup when @RequiresAction is combined with @PermitAll")
    void registrar_requiresActionAndPermitAll_throwsAtStartup() {
        // authEnabled=true so the only failure cause is the @PermitAll conflict.
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new RequiresActionAndPermitAllResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        true,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        new StubActionRegistry(ActionRef.of("cms", "content", "read")),
                        // Authorizer present: the only failure cause is the one under test.
                        true));

        assertTrue(ex.violations().stream().anyMatch(v -> "actionPermitGet".equals(v.operationId())));
    }

    /**
     * Captures the {@link OperationRegistrationContext} passed to the first contributed operation,
     * so tests can assert on {@link OperationRegistrationContext#requiredAction()}.
     */
    static final class CapturingContributor implements OperationHandlerContributor {
        private OperationRegistrationContext captured;

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            this.captured = context;
        }
    }

    /**
     * Minimal {@link ActionRegistry} stub that contains a fixed set of actions, keyed by their
     * canonical value.
     */
    static final class StubActionRegistry implements ActionRegistry {
        private final Set<String> known;

        StubActionRegistry(ActionRef... refs) {
            this.known = Arrays.stream(refs).map(ActionRef::value).collect(Collectors.toSet());
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return List.of();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return known.contains(action.value());
        }
    }

    // --- Helpers ---

    private ResourceMethodMeta findByOperationId(List<ResourceMethodMeta> methods, String operationId) {
        return methods.stream()
                .filter(m -> m.operationId().equals(operationId))
                .findFirst()
                .orElse(null);
    }

    private ResourceMethodMeta findByHttpMethod(List<ResourceMethodMeta> methods, String httpMethod) {
        return methods.stream()
                .filter(m -> m.httpMethod().equals(httpMethod))
                .findFirst()
                .orElse(null);
    }
}
