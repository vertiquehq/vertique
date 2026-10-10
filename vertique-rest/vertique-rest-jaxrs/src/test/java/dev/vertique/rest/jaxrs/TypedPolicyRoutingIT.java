// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reflective routing proof for a policy that exists only on an interface the consumer adds.
 *
 * <p>The generated hello application cannot host this case: it has no action registry, and a type
 * policy on {@code HelloResource} would mix with that class's inline method annotations. Scheme
 * rejection and the custom evaluator are proved here for the same reason.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TypedPolicyRoutingIT {

    private static final ActionRef GREETING_READ = ActionRef.parse("hello.greeting.read");

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;

    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));
        ctx.completeNow();
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    @Test
    @DisplayName("an interface policy on an inherited implementation is enforced, and conflicts are rejected")
    void shouldEnforceAnInterfacePolicyOnAnInheritedImplementation(VertxTestContext ctx) {
        assertTrue(
                causedBy(registrationFailure(conflictingTypes()), IllegalArgumentException.class),
                "distinct policies in one type set must be rejected");
        assertTrue(
                causedBy(registrationFailure(mixedPolicyAndPermit()), IllegalArgumentException.class),
                "RequiresPolicy mixed with PermitAll must be rejected");
        RouteRegistrationException withoutAuth = assertInstanceOf(
                RouteRegistrationException.class, registrationFailure(inheritedResource(), false, Optional.empty()));
        assertEquals(
                RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                withoutAuth.violations().get(0).type());
        assertTrue(
                registrationFailure(invalidScheme()).getMessage().contains("not-a-scheme"),
                "an unknown HTTP security scheme must be rejected at registration");
        RouteRegistrationException unknownAction = assertInstanceOf(
                RouteRegistrationException.class,
                registrationFailure(unknownActionResource(), true, Optional.of(new KnownActions(GREETING_READ))));
        assertEquals(
                RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                unknownAction.violations().get(0).type());

        ResourceMethodMeta inline = new JaxRsRouteRegistrar()
                .scanResource(new InlineQueryResource())
                .get(0);
        assertEquals(
                List.of("q", "page"),
                inline.params().stream().map(ResourceMethodMeta.ParamMeta::name).toList());
        assertEquals(
                ResourceMethodMeta.ParamSource.QUERY, inline.params().get(0).source());
        SecurityPolicy.Constrained inlinePolicy =
                assertInstanceOf(SecurityPolicy.Constrained.class, inline.securityPolicy());
        assertEquals(List.of("user"), inlinePolicy.requiredRoles());

        AtomicInteger decisions = new AtomicInteger();
        AtomicReference<AuthorizationRequest> lastDecision = new AtomicReference<>();
        AuthorizationPolicy policy = request -> {
            decisions.incrementAndGet();
            lastDecision.set(request);
            if ("override".equals(request.securityContext().identity().actor().id())) {
                return AuthorizationDecision.permit("POLICY_OVERRIDE");
            }
            Object required = request.context().get("requiredRoles");
            if (required instanceof List<?> roles) {
                Set<String> held = request.securityContext().authorization().valuesOf(AuthorityKind.ROLE);
                boolean allowed = roles.stream().allMatch(role -> held.contains(String.valueOf(role)));
                return allowed ? AuthorizationDecision.permit("ROLES") : AuthorizationDecision.deny("ROLES");
            }
            return AuthorizationDecision.permit("NO_ROLES_REQUIRED");
        };
        List<AuthorizationRequest> actionRequests = new CopyOnWriteArrayList<>();
        Authorizer authorizer = new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                actionRequests.add(request);
                return Future.succeededFuture(AuthorizationDecision.permit("ACTION"));
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext securityContext, ActionRef action, ResourceRef resource) {
                throw new AssertionError("the action gate must pass the ambient InvocationOrigin");
            }
        };
        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((bound, secure) -> null);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.of(policy),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                restOrigin(),
                securityRuntime,
                Optional.of(authorizer),
                Resilience.create(vertx));
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(Set.of(new AuthorizationContributor(enforcer)))
                .actionRegistry(Optional.of(new KnownActions(GREETING_READ)))
                .authorizer(Optional.of(authorizer))
                .build();
        JaxRsRouterMount mount = factory.create(
                "/*",
                "openapi.json",
                Set.of(
                        new InheritedResource(),
                        new PermitResource(),
                        new DenyResource(),
                        new ActionResource(),
                        new InlineQueryResource()));
        Router api = mount.createRouter(vertx).result();
        assertTrue(UnusedRestrictivePolicy.class.isInterface());
        assertFalse(
                api.getRoutes().stream()
                        .map(route -> route.getPath() == null ? "" : route.getPath())
                        .anyMatch(path -> path.toLowerCase().contains("unused")),
                "an unreferenced restrictive policy must not add a route");
        assertEquals(0, decisions.get(), "building the router must not evaluate a policy");

        Router root = Router.router(vertx);
        root.route("/*").handler(new RequestContextLifecycle());
        root.route("/*").handler(routingContext -> bindCaller(routingContext, securityRuntime));
        root.route("/*").subRouter(api);

        vertx.createHttpServer()
                .requestHandler(root)
                .listen(0, "127.0.0.1")
                .compose(http -> {
                    server = http;
                    int port = http.actualPort();
                    return exchange(port, "/typed/inherited", "user", "user")
                            .compose(denied -> {
                                ctx.verify(() -> {
                                    assertEquals(403, denied.statusCode());
                                    assertEquals(0, InheritedResource.work.get());
                                    assertEquals(1, decisions.get());
                                });
                                return exchange(port, "/typed/inherited", "admin", "admin");
                            })
                            .compose(allowed -> {
                                ctx.verify(() -> {
                                    assertEquals(200, allowed.statusCode());
                                    assertEquals("inherited", allowed.body());
                                    assertEquals(1, InheritedResource.work.get());
                                    assertEquals(
                                            "route",
                                            lastDecision.get().resource().type());
                                    assertTrue(
                                            lastDecision.get().resource().id().endsWith("/typed/inherited"));
                                    assertEquals(
                                            "rest", lastDecision.get().origin().kind());
                                });
                                int beforeOverride = decisions.get();
                                return exchange(port, "/typed/inherited", "override", null)
                                        .map(override -> new int[] {beforeOverride, override.statusCode()});
                            })
                            .compose(override -> {
                                ctx.verify(() -> {
                                    assertEquals(200, override[1]);
                                    assertEquals(
                                            override[0] + 1,
                                            decisions.get(),
                                            "the policy permit must be the only check");
                                    assertEquals(2, InheritedResource.work.get());
                                });
                                int beforePermit = decisions.get();
                                return exchange(port, "/typed/permit", null, null)
                                        .map(response ->
                                                new int[] {beforePermit, response.statusCode(), decisions.get()});
                            })
                            .compose(permit -> {
                                ctx.verify(() -> {
                                    assertEquals(200, permit[1]);
                                    assertEquals(1, PermitResource.work.get());
                                    assertEquals(permit[0], permit[2], "PermitAll must not call the custom policy");
                                });
                                int beforeDeny = decisions.get();
                                return exchange(port, "/typed/deny", "admin", "admin")
                                        .map(response ->
                                                new int[] {beforeDeny, response.statusCode(), decisions.get()});
                            })
                            .compose(deny -> {
                                ctx.verify(() -> {
                                    assertEquals(403, deny[1]);
                                    assertEquals(0, DenyResource.work.get());
                                    assertEquals(deny[0], deny[2], "DenyAll must not call the custom policy");
                                });
                                return exchange(port, "/typed/action", "admin", "admin");
                            })
                            .compose(action -> {
                                ctx.verify(() -> {
                                    assertEquals(200, action.statusCode());
                                    assertEquals("acted", action.body());
                                    assertEquals(1, actionRequests.size());
                                    assertEquals(
                                            "rest",
                                            actionRequests.get(0).origin().kind());
                                    assertEquals(
                                            "route",
                                            actionRequests.get(0).resource().type());
                                    assertEquals(1, ActionResource.work.get());
                                });
                                return exchange(port, "/typed/inline", "user", "user");
                            });
                })
                .onComplete(ctx.succeeding(inlineResponse -> ctx.verify(() -> {
                    assertEquals(200, inlineResponse.statusCode());
                    assertEquals("a:2", inlineResponse.body());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an interface policy on a generic-substituted inherited implementation is enforced")
    void shouldEnforceAnInterfacePolicyOnAGenericSubstitutedImplementation(VertxTestContext ctx) {
        GenericResource.work.set(0);
        AtomicInteger decisions = new AtomicInteger();
        AuthorizationPolicy policy = request -> {
            decisions.incrementAndGet();
            Object required = request.context().get("requiredRoles");
            if (required instanceof List<?> roles) {
                Set<String> held = request.securityContext().authorization().valuesOf(AuthorityKind.ROLE);
                boolean allowed = roles.stream().allMatch(role -> held.contains(String.valueOf(role)));
                return allowed ? AuthorizationDecision.permit("ROLES") : AuthorizationDecision.deny("ROLES");
            }
            return AuthorizationDecision.permit("NO_ROLES_REQUIRED");
        };
        HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((bound, secure) -> null);
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.of(policy),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                restOrigin(),
                securityRuntime,
                Optional.empty(),
                Resilience.create(vertx));
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .operationHandlerContributors(Set.of(new AuthorizationContributor(enforcer)))
                .build();
        Router api = factory.create("/*", "openapi.json", Set.of(new GenericResource()))
                .createRouter(vertx)
                .result();
        Router root = Router.router(vertx);
        root.route("/*").handler(new RequestContextLifecycle());
        root.route("/*").handler(routingContext -> bindCaller(routingContext, securityRuntime));
        root.route("/*").subRouter(api);

        vertx.createHttpServer()
                .requestHandler(root)
                .listen(0, "127.0.0.1")
                .compose(http -> {
                    server = http;
                    int port = http.actualPort();
                    return exchange(port, "/typed/generic/7", "user", "user").compose(denied -> {
                        ctx.verify(() -> {
                            assertEquals(403, denied.statusCode());
                            assertEquals(
                                    0,
                                    GenericResource.work.get(),
                                    "a denied caller must not reach the inherited implementation");
                            assertEquals(1, decisions.get());
                        });
                        return exchange(port, "/typed/generic/7", "admin", "admin");
                    });
                })
                .onComplete(ctx.succeeding(allowed -> ctx.verify(() -> {
                    assertEquals(200, allowed.statusCode());
                    assertEquals("removed:7", allowed.body());
                    assertEquals(1, GenericResource.work.get());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a scanned method keeps its own policy and its own inline security under a type policy")
    void shouldNeverReplaceTheScannedMethodsOwnSecurity() {
        ResourceMethodMeta ancestor =
                new JaxRsRouteRegistrar().scanResource(new AncestorConsumer()).get(0);
        SecurityPolicy.Constrained constrained =
                assertInstanceOf(SecurityPolicy.Constrained.class, ancestor.securityPolicy());
        assertEquals(
                List.of("admin"),
                constrained.requiredRoles(),
                "an inherited generic method's own policy must not be replaced by the type policy");
        assertTrue(
                causedBy(registrationFailure(new NonPublicInlineResource()), IllegalArgumentException.class),
                "inline security on a non-public route under a type policy must reject, not be stripped");
    }

    private static void bindCaller(RoutingContext routingContext, HolderBackedSecurityRuntime securityRuntime) {
        String user = routingContext.request().getHeader("X-Test-User");
        if (user != null) {
            List<AuthorityClaim> claims = new ArrayList<>();
            String roles = routingContext.request().getHeader("X-Test-Roles");
            if (roles != null && !roles.isBlank()) {
                for (String role : roles.split(",")) {
                    claims.add(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", java.util.Map.of()));
                }
            }
            SecurityContext securityContext = SecurityContexts.assemble(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, user, java.util.Map.of())),
                    new AuthenticationState(
                            DefaultAuthMethod.custom("test"),
                            List.of(),
                            Optional.empty(),
                            Optional.empty(),
                            java.util.Map.of()),
                    new AuthorizationClaims(Set.copyOf(claims), java.util.Map.of()),
                    Optional.empty());
            RequestContextLifecycle.fromRoutingContext(routingContext)
                    .onClose(securityRuntime.bindCurrent(securityContext));
        }
        routingContext.next();
    }

    private static Future<HttpResponse<String>> exchange(int port, String path, String user, String roles) {
        var request = client.get(port, "127.0.0.1", path).as(BodyCodec.string());
        if (user != null) {
            request.putHeader("X-Test-User", user);
        }
        if (roles != null) {
            request.putHeader("X-Test-Roles", roles);
        }
        if ("/typed/inline".equals(path)) {
            request.addQueryParam("q", "a").addQueryParam("page", "2");
        }
        return request.send();
    }

    private static Throwable registrationFailure(Object resource) {
        return registrationFailure(resource, true, Optional.empty());
    }

    private static Throwable registrationFailure(
            Object resource, boolean authEnabled, Optional<ActionRegistry> actionRegistry) {
        try {
            var builder = TestFactories.builder().actionRegistry(actionRegistry);
            if (authEnabled) {
                builder.authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE));
            }
            Future<Router> created = builder.build()
                    .create("/*", "openapi.json", Set.of(resource))
                    .createRouter(vertx);
            if (created.failed()) {
                return created.cause();
            }
            throw new AssertionError(
                    "registration succeeded for " + resource.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            return ex;
        }
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static ContextHolder restOrigin() {
        return new ContextHolder() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> current(Class<T> type) {
                if (type == InvocationOrigin.class) {
                    return (Optional<T>) Optional.of(InvocationOrigin.of("rest"));
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    private static InheritedResource inheritedResource() {
        return new InheritedResource();
    }

    private static Object conflictingTypes() {
        return new ConflictingResource();
    }

    private static Object mixedPolicyAndPermit() {
        return new MixedResource();
    }

    private static Object invalidScheme() {
        return new InvalidSchemeResource();
    }

    private static Object unknownActionResource() {
        return new UnknownActionResource();
    }

    /** Declared and never selected. Registration must not grow a route for it. */
    @DenyAll
    public interface UnusedRestrictivePolicy extends AccessPolicy {}

    @RolesAllowed("admin")
    public interface AdminPolicy extends AccessPolicy {}

    @PermitAll
    public interface OpenPolicy extends AccessPolicy {}

    @DenyAll
    public interface ClosedPolicy extends AccessPolicy {}

    @RequiresAction("hello.greeting.read")
    public interface GreetingReadPolicy extends AccessPolicy {}

    @RequiresAction("missing.action.ref")
    public interface MissingActionPolicy extends AccessPolicy {}

    @PermitAll
    public interface LeftPolicy extends AccessPolicy {}

    @DenyAll
    public interface RightPolicy extends AccessPolicy {}

    interface InheritedOps {
        @GET
        @Path("/inherited")
        @RequiresPolicy(AdminPolicy.class)
        String inherited();
    }

    static class InheritedBase {
        @GET
        @Path("/inherited")
        public String inherited() {
            InheritedResource.work.incrementAndGet();
            return "inherited";
        }
    }

    @Path("/typed")
    static class InheritedResource extends InheritedBase implements InheritedOps {
        static final AtomicInteger work = new AtomicInteger();
    }

    interface GenericOps<ID> {
        @RequiresPolicy(AdminPolicy.class)
        String remove(ID id);
    }

    static class GenericBase {
        static final AtomicInteger work = new AtomicInteger();

        @GET
        @Path("/{id}")
        public String remove(@PathParam("id") String id) {
            work.incrementAndGet();
            return "removed:" + id;
        }
    }

    @Path("/typed/generic")
    static class GenericResource extends GenericBase implements GenericOps<String> {}

    static class AncestorWithMethodPolicy<T> {
        @GET
        @Path("/{id}")
        @RequiresPolicy(AdminPolicy.class)
        public String fetch(@PathParam("id") T id) {
            return "fetched";
        }
    }

    @Path("/typed/ancestor")
    @RequiresPolicy(OpenPolicy.class)
    static class AncestorConsumer extends AncestorWithMethodPolicy<String> {}

    @Path("/typed/nonpublic")
    @RequiresPolicy(OpenPolicy.class)
    static class NonPublicInlineResource {
        @GET
        @RolesAllowed("admin")
        String secret() {
            return "secret";
        }
    }

    @Path("/typed/permit")
    static class PermitResource {
        static final AtomicInteger work = new AtomicInteger();

        @GET
        @RequiresPolicy(OpenPolicy.class)
        public String open() {
            work.incrementAndGet();
            return "open";
        }
    }

    @Path("/typed/deny")
    static class DenyResource {
        static final AtomicInteger work = new AtomicInteger();

        @GET
        @RequiresPolicy(ClosedPolicy.class)
        public String closed() {
            work.incrementAndGet();
            return "closed";
        }
    }

    @Path("/typed/action")
    static class ActionResource {
        static final AtomicInteger work = new AtomicInteger();

        @GET
        @RequiresPolicy(GreetingReadPolicy.class)
        public String act() {
            work.incrementAndGet();
            return "acted";
        }
    }

    @Path("/typed/inline")
    static class InlineQueryResource {
        @GET
        @RolesAllowed("user")
        public String echo(@QueryParam("q") String q, @QueryParam("page") String page) {
            return q + ":" + page;
        }
    }

    interface LeftOps {
        @GET
        @RequiresPolicy(LeftPolicy.class)
        String get();
    }

    interface RightOps {
        @GET
        @RequiresPolicy(RightPolicy.class)
        String get();
    }

    static class ConflictBase {
        @GET
        public String get() {
            return "conflict";
        }
    }

    @Path("/typed/conflict")
    static class ConflictingResource extends ConflictBase implements LeftOps, RightOps {}

    @Path("/typed/mixed")
    static class MixedResource {
        @GET
        @RequiresPolicy(AdminPolicy.class)
        @PermitAll
        public String get() {
            return "mixed";
        }
    }

    @Path("/typed/bad-scheme")
    static class InvalidSchemeResource {
        @GET
        @PermitAll
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "not-a-scheme")
        public String get() {
            return "scheme";
        }
    }

    @Path("/typed/missing-action")
    static class UnknownActionResource {
        @GET
        @RequiresPolicy(MissingActionPolicy.class)
        public String get() {
            return "missing";
        }
    }

    static final class KnownActions implements ActionRegistry {
        private final Set<String> known;

        KnownActions(ActionRef... refs) {
            this.known =
                    java.util.Arrays.stream(refs).map(ActionRef::value).collect(java.util.stream.Collectors.toSet());
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
}
