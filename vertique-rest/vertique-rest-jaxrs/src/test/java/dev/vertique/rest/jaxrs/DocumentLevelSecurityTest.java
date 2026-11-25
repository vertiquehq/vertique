// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.SecuritySchemeAnnotationScanner.DocumentSecurity;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Document-level security: an application's declaring interface's {@code
 * @OpenAPIDefinition(security = ...)} applies to every operation without its own requirement, the
 * method and class levels replace it, and an empty {@code @SecurityRequirements({})} declared on the
 * resource class or its own method opts out of it.
 */
class DocumentLevelSecurityTest {

    private static final String BEARER = "bearerAuth";
    private static final String API_KEY = "apiKey";
    private static final SecurityRequirementSet BEARER_SET = set(BEARER);
    private static final SecurityRequirementSet API_KEY_SET = set(API_KEY);

    // --- Precedence, resolved by the scanner ---

    @Nested
    @DisplayName("Precedence")
    class Precedence {

        @Test
        @DisplayName("An operation with no requirement of its own inherits the document's sets")
        void unannotatedOperationInheritsTheDocument() {
            assertEquals(List.of(BEARER_SET), effective(new PlainResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("A class-level requirement replaces the document's sets")
        void classLevelReplacesTheDocument() {
            assertEquals(List.of(API_KEY_SET), effective(new ClassApiKeyResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("A method-level requirement replaces the document's sets")
        void methodLevelReplacesTheDocument() {
            assertEquals(List.of(API_KEY_SET), effective(new MethodApiKeyResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("@Operation(security) on the method replaces the document's sets")
        void operationSecurityReplacesTheDocument() {
            assertEquals(List.of(API_KEY_SET), effective(new OperationApiKeyResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("An empty @SecurityRequirements on the resource's own method makes it public")
        void methodMarkerMakesTheOperationPublic() {
            assertEquals(List.of(), effective(new MethodMarkerResource(), "open", bearerDocument()));
            assertEquals(
                    List.of(BEARER_SET),
                    effective(new MethodMarkerResource(), "closed", bearerDocument()),
                    "control: a sibling method without the marker still inherits the document");
        }

        @Test
        @DisplayName("An empty @SecurityRequirements on the resource class makes every method public")
        void classMarkerMakesEveryOperationPublic() {
            assertEquals(List.of(), effective(new ClassMarkerResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("The marker never removes a class-level requirement")
        void markerNeverRemovesTheClassLevel() {
            assertEquals(
                    List.of(API_KEY_SET), effective(new ClassApiKeyMethodMarkerResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("Without a document the marker leaves the operation as it was: no set")
        void markerWithoutADocument() {
            assertEquals(List.of(), effective(new MethodMarkerResource(), "open", DocumentSecurity.NONE));
            assertEquals(List.of(), effective(new PlainResource(), "get", DocumentSecurity.NONE));
        }

        @Test
        @DisplayName("A marker reached only through an interface method fails startup")
        void markerOnAnInterfaceMethodFails() {
            RestConfigurationException failure = assertThrows(
                    RestConfigurationException.class,
                    () -> effective(new InheritsInterfaceMarkerResource(), "get", bearerDocument()));
            assertTrue(failure.getMessage().contains("inherits @SecurityRequirements({})"), failure.getMessage());
        }

        @Test
        @DisplayName("A marker on an interface default method the resource does not override fails startup")
        void markerOnAnInheritedDefaultMethodFails() {
            assertThrows(
                    RestConfigurationException.class,
                    () -> effective(new InheritsDefaultMethodMarkerResource(), "get", bearerDocument()));
        }

        @Test
        @DisplayName("A marker reached only through a superclass fails startup")
        void markerOnASuperclassFails() {
            RestConfigurationException failure = assertThrows(
                    RestConfigurationException.class,
                    () -> effective(new InheritsSuperclassMarkerResource(), "get", bearerDocument()));
            assertTrue(failure.getMessage().contains("inherits @SecurityRequirements({})"), failure.getMessage());
        }

        @Test
        @DisplayName("A resource whose hierarchy carries @OpenAPIDefinition(security) fails startup")
        void resourceDocumentSecurityFails() {
            RestConfigurationException direct = assertThrows(
                    RestConfigurationException.class,
                    () -> effective(new ResourceWithDocumentSecurity(), "get", DocumentSecurity.NONE));
            assertTrue(direct.getMessage().contains(ResourceWithDocumentSecurity.class.getName()), direct.getMessage());
            assertThrows(
                    RestConfigurationException.class,
                    () -> effective(new InheritsDocumentSecurityResource(), "get", DocumentSecurity.NONE));
        }

        @Test
        @DisplayName("A resource carrying an info-only @OpenAPIDefinition still registers")
        void resourceWithInfoOnlyDefinitionRegisters() {
            assertEquals(List.of(BEARER_SET), effective(new ResourceWithInfoOnlyDefinition(), "get", bearerDocument()));
        }
    }

    // --- Reading the document ---

    @Nested
    @DisplayName("Document")
    class Document {

        @Test
        @DisplayName("Reads the declaring interface's requirements as OR alternatives, in declaration order")
        void readsAlternativesInOrder() {
            assertEquals(
                    List.of(BEARER_SET, API_KEY_SET),
                    SecuritySchemeAnnotationScanner.documentSecurity(BearerOrApiKeyApi.class, "orders")
                            .sets());
        }

        @Test
        @DisplayName("An interface without @OpenAPIDefinition(security) has no document level")
        void noSecurityMeansNone() {
            assertSame(DocumentSecurity.NONE, SecuritySchemeAnnotationScanner.documentSecurity(InfoOnlyApi.class, "a"));
            assertSame(
                    DocumentSecurity.NONE,
                    SecuritySchemeAnnotationScanner.documentSecurity(NoDefinitionApi.class, "a"));
        }

        @Test
        @DisplayName("A multi-scheme document requirement fails naming the application and its interface")
        void andGroupFails() {
            RestConfigurationException failure = assertThrows(
                    RestConfigurationException.class,
                    () -> SecuritySchemeAnnotationScanner.documentSecurity(AndGroupApi.class, "orders"));
            assertTrue(failure.getMessage().contains("'orders'"), failure.getMessage());
            assertTrue(failure.getMessage().contains(AndGroupApi.class.getName()), failure.getMessage());
            assertTrue(failure.getMessage().contains("multi-scheme"), failure.getMessage());
        }

        @Test
        @DisplayName("Scopes on one of several document alternatives fail naming the interface")
        void scopedAlternativesFail() {
            RestConfigurationException failure = assertThrows(
                    RestConfigurationException.class,
                    () -> SecuritySchemeAnnotationScanner.documentSecurity(ScopedAlternativesApi.class, "orders"));
            assertTrue(failure.getMessage().contains(ScopedAlternativesApi.class.getName()), failure.getMessage());
        }

        @Test
        @DisplayName("A single scoped document requirement is accepted")
        void singleScopedRequirementIsAccepted() {
            assertEquals(
                    List.of(new SecurityRequirementSet(List.of(new SecurityRequirement(BEARER, List.of("read"))))),
                    SecuritySchemeAnnotationScanner.documentSecurity(ScopedApi.class, "orders")
                            .sets());
        }
    }

    // --- Startup allow list on the declaring interface ---

    @Nested
    @DisplayName("Allow list")
    class AllowList {

        @Test
        @DisplayName("A declaring interface may set security on @OpenAPIDefinition")
        void securityIsAllowed() {
            assertEquals(List.of(), ApplicationAnnotationAllowList.violations("orders", BearerApi.class));
        }

        @Test
        @DisplayName("Any other element besides info and security still fails")
        void otherElementsStillFail() {
            List<String> violations = ApplicationAnnotationAllowList.violations("orders", BearerWithServersApi.class);
            assertEquals(1, violations.size(), violations.toString());
            assertTrue(violations.get(0).contains("only its info and security elements may be set"), violations.get(0));
        }
    }

    // --- End to end through an application mount ---

    @Nested
    @DisplayName("Application mount")
    class ApplicationMount {

        private Vertx vertx;
        private @Nullable HttpServer server;
        private @Nullable HttpClient transport;
        private @Nullable WebClient client;
        private int port;

        @BeforeEach
        void setUp() {
            vertx = Vertx.vertx();
        }

        @AfterEach
        void tearDown() {
            Future<Void> clientClose = transport != null ? transport.close() : Future.succeededFuture();
            Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
            await(Future.join(clientClose, serverClose).mapEmpty());
            await(vertx.close());
        }

        @Test
        @DisplayName("An operation covered only by the document refuses an anonymous caller with 401")
        void documentSecuredOperationRequiresCredentials() {
            RecordingContributor contributor = new RecordingContributor();
            JaxRsRouterMount mount =
                    applicationMount(factory(contributor), BearerApi.class, new MethodMarkerResource());
            Router root = Router.router(vertx);
            Router router = await(mount.createRouter(vertx));
            root.route(mount.mountPath()).subRouter(router);
            listen(root);

            assertAll(
                    () -> assertEquals(401, get("/api/closed", null), "anonymous request to a document-secured route"),
                    () -> assertEquals(200, get("/api/closed", "Bearer ok"), "authenticated request"),
                    () -> assertEquals(200, get("/api/open", null), "the marked route stays anonymous"),
                    () -> assertEquals(
                            List.of(BEARER_SET),
                            contributor.descriptors.get("closed").securityRequirementSets(),
                            "the descriptor every contributor and the served document read lists the inherited set"),
                    () -> assertEquals(
                            List.of(),
                            contributor.descriptors.get("open").securityRequirementSets(),
                            "the marked operation's descriptor lists no set"));
        }

        @Test
        @DisplayName("@PermitAll with the marker under a document level stays anonymous")
        void permitAllWithMarkerStaysAnonymous() {
            RecordingContributor contributor = new RecordingContributor();
            JaxRsRouterMount mount =
                    applicationMount(factory(contributor), BearerApi.class, new PermitAllMarkedResource());
            Router root = Router.router(vertx);
            root.route(mount.mountPath()).subRouter(await(mount.createRouter(vertx)));
            listen(root);

            assertEquals(200, get("/api/login", null));
            assertEquals(List.of(), contributor.descriptors.get("login").securityRequirementSets());
        }

        @Test
        @DisplayName("Scopes from both the document and @Authorized fail startup naming the operation")
        void documentScopesWithAuthorizedScopesFail() {
            JaxRsRouterMount mount = applicationMount(
                    factory(new RecordingContributor()), ScopedApi.class, new AuthorizedScopesResource());
            Throwable failure = failureOf(() -> mount.createRouter(vertx));
            assertTrue(failure instanceof RestConfigurationException, String.valueOf(failure));
            assertTrue(failure.getMessage().contains("'scoped'"), failure.getMessage());
            assertTrue(
                    failure.getMessage().contains("both @Authorized and @SecurityRequirement"), failure.getMessage());
        }

        @Test
        @DisplayName("requireExplicitPolicy accepts an operation covered only by the document level")
        void documentLevelCountsAsExplicit() {
            JaxRsRouterMount.Factory strict = TestFactories.builder()
                    .securitySchemeHandlers(Set.of(new BearerSchemeHandler()))
                    .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                    .jaxRsConfig(dev.vertique.rest.core.config.JaxRsConfig.builder()
                            .validationStrategy(dev.vertique.rest.jaxrs.validation.NoneValidationStrategy.ID)
                            .security(new dev.vertique.rest.core.config.JaxRsSecurityConfig(true))
                            .build())
                    .build();

            await(applicationMount(strict, BearerApi.class, new PlainResource()).createRouter(vertx));
            JaxRsRouterMount withoutDocument = applicationMount(strict, NoDefinitionApi.class, new PlainResource());
            Throwable control = failureOf(() -> withoutDocument.createRouter(vertx));
            assertTrue(
                    String.valueOf(control).contains("no explicit security policy"),
                    "control: without the document the same operation is implicit and fails: " + control);
        }

        @Test
        @DisplayName("A hand-built mount has no document level")
        void handBuiltMountHasNoDocument() {
            RecordingContributor contributor = new RecordingContributor();
            JaxRsRouterMount mount = factory(contributor).create("/api/*", null, Set.<Object>of(new PlainResource()));
            await(mount.createRouter(vertx));

            assertEquals(List.of(), contributor.descriptors.get("get").securityRequirementSets());
        }

        private JaxRsRouterMount.Factory factory(RecordingContributor contributor) {
            return TestFactories.builder()
                    .securitySchemeHandlers(Set.of(new BearerSchemeHandler()))
                    .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                    .operationHandlerContributors(Set.of(contributor))
                    .build();
        }

        private void listen(Router root) {
            server = await(vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1"));
            port = server.actualPort();
            transport = vertx.createHttpClient();
            client = WebClient.wrap(transport);
        }

        private int get(String path, @Nullable String authorization) {
            var request = client.get(port, "127.0.0.1", path);
            if (authorization != null) {
                request.putHeader("Authorization", authorization);
            }
            return await(request.send()).statusCode();
        }
    }

    // --- Helpers ---

    private static DocumentSecurity bearerDocument() {
        return new DocumentSecurity(List.of(BEARER_SET));
    }

    private static SecurityRequirementSet set(String scheme) {
        return new SecurityRequirementSet(List.of(new SecurityRequirement(scheme, List.of())));
    }

    private static List<SecurityRequirementSet> effective(
            Object resource, String methodName, DocumentSecurity document) {
        Method method;
        try {
            method = resource.getClass().getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(resource.getClass());
        ResourceMethodMeta meta = new ResourceMethodMeta(
                resource,
                method,
                methodName,
                "GET",
                "/" + methodName,
                List.of(),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                methodAnnotations,
                classAnnotations,
                List.of(),
                List.of());
        return SecuritySchemeAnnotationScanner.effectiveRequirements(meta, document);
    }

    private static JaxRsRouterMount applicationMount(
            JaxRsRouterMount.Factory factory, Class<?> declaringType, Object resource) {
        JaxRsRouterMount mount =
                factory.createApplicationMount("/api/*", null, Set.of(resource), "orders", declaringType);
        mount.markValidated();
        return mount;
    }

    /** The router build's failure, whether it throws or returns a failed future. */
    private static Throwable failureOf(java.util.function.Supplier<Future<?>> build) {
        try {
            build.get().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (RuntimeException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected the router build to fail");
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("asynchronous step failed: " + e.getMessage(), e);
        }
    }

    /** Records each operation's descriptor by operation id. */
    private static final class RecordingContributor implements OperationHandlerContributor {
        final Map<String, RestOperationDescriptor> descriptors = new ConcurrentHashMap<>();

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            descriptors.put(context.operationId(), context.operation());
        }
    }

    /** Authenticates a request carrying {@code Authorization: Bearer ok}; refuses any other with 401. */
    private static final class BearerSchemeHandler implements SecuritySchemeHandler {
        @Override
        public String schemeName() {
            return BEARER;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(ctx -> {
                if ("Bearer ok".equals(ctx.request().getHeader("Authorization"))) {
                    ctx.next();
                } else {
                    ctx.fail(401);
                }
            });
        }
    }

    // --- Declaring interfaces ---

    @OpenAPIDefinition(security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = BEARER))
    interface BearerApi {}

    @OpenAPIDefinition(
            security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = BEARER),
            servers = @io.swagger.v3.oas.annotations.servers.Server(url = "/"))
    interface BearerWithServersApi {}

    @OpenAPIDefinition(
            security = {
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = BEARER),
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY)
            })
    interface BearerOrApiKeyApi {}

    @OpenAPIDefinition(info = @io.swagger.v3.oas.annotations.info.Info(title = "t", version = "1"))
    interface InfoOnlyApi {}

    interface NoDefinitionApi {}

    @OpenAPIDefinition(
            security =
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                            combine = {
                                @SecurityRequirementEntry(name = BEARER),
                                @SecurityRequirementEntry(name = API_KEY)
                            }))
    interface AndGroupApi {}

    @OpenAPIDefinition(
            security = {
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                        name = BEARER,
                        scopes = {"read"}),
                @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY)
            })
    interface ScopedAlternativesApi {}

    @OpenAPIDefinition(
            security =
                    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
                            name = BEARER,
                            scopes = {"read"}))
    interface ScopedApi {}

    // --- Resources ---

    @Path("")
    public static class PlainResource {
        @GET
        @Path("/get")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "get")
        public String get() {
            return "ok";
        }
    }

    @Path("")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY)
    public static class ClassApiKeyResource {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }

    @Path("")
    public static class MethodApiKeyResource {
        @GET
        @Path("/get")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY)
        public String get() {
            return "ok";
        }
    }

    @Path("")
    public static class OperationApiKeyResource {
        @GET
        @Path("/get")
        @Operation(security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY))
        public String get() {
            return "ok";
        }
    }

    @Path("")
    public static class MethodMarkerResource {
        @GET
        @Path("/open")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "open")
        @SecurityRequirements({})
        public String open() {
            return "ok";
        }

        @GET
        @Path("/closed")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "closed")
        public String closed() {
            return "ok";
        }
    }

    @Path("")
    @SecurityRequirements({})
    public static class ClassMarkerResource {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }

    @Path("")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = API_KEY)
    public static class ClassApiKeyMethodMarkerResource {
        @GET
        @Path("/get")
        @SecurityRequirements({})
        public String get() {
            return "ok";
        }
    }

    @Path("")
    public static class PermitAllMarkedResource {
        @GET
        @Path("/login")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "login")
        @jakarta.annotation.security.PermitAll
        @SecurityRequirements({})
        public String login() {
            return "ok";
        }
    }

    @Path("")
    public static class AuthorizedScopesResource {
        @GET
        @Path("/scoped")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "scoped")
        @dev.vertique.security.authz.Authorized(scopes = "write")
        public String scoped() {
            return "ok";
        }
    }

    public interface MarkedContract {
        @GET
        @Path("/get")
        @SecurityRequirements({})
        String get();
    }

    @Path("")
    public static class InheritsInterfaceMarkerResource implements MarkedContract {
        @Override
        public String get() {
            return "ok";
        }
    }

    public interface MarkedDefaultContract {
        @GET
        @Path("/get")
        @SecurityRequirements({})
        default String get() {
            return "ok";
        }
    }

    @Path("")
    public static class InheritsDefaultMethodMarkerResource implements MarkedDefaultContract {}

    @SecurityRequirements({})
    public static class MarkedBase {}

    @Path("")
    public static class InheritsSuperclassMarkerResource extends MarkedBase {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }

    @Path("")
    @OpenAPIDefinition(security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = BEARER))
    public static class ResourceWithDocumentSecurity {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }

    @OpenAPIDefinition(security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = BEARER))
    public static class DocumentSecurityBase {}

    @Path("")
    public static class InheritsDocumentSecurityResource extends DocumentSecurityBase {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }

    @Path("")
    @OpenAPIDefinition(info = @io.swagger.v3.oas.annotations.info.Info(title = "t", version = "1"))
    public static class ResourceWithInfoOnlyDefinition {
        @GET
        @Path("/get")
        public String get() {
            return "ok";
        }
    }
}
