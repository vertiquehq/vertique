// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.fixture.ApplicationA;
import dev.vertique.rest.jaxrs.publication.fixture.ApplicationB;
import dev.vertique.rest.jaxrs.publication.fixture.ApplicationC;
import dev.vertique.rest.jaxrs.publication.fixture.ApplicationD;
import dev.vertique.rest.jaxrs.publication.fixture.ApplicationE;
import dev.vertique.rest.jaxrs.publication.fixture.CountingSchemaSource;
import dev.vertique.rest.jaxrs.publication.fixture.DualOperationResource;
import dev.vertique.rest.jaxrs.publication.fixture.EchoResource;
import dev.vertique.rest.jaxrs.publication.fixture.GateMutatingValidationStrategy;
import dev.vertique.rest.jaxrs.publication.fixture.ItemsResource;
import dev.vertique.rest.jaxrs.publication.fixture.ProfiledOperationsResource;
import dev.vertique.rest.jaxrs.publication.fixture.PromiseControlledPublicationHook;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingPublicationHook;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingValidationStrategy;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit proofs for T006's internal publication seam: {@code JaxRsRouterMount.createRouter} building
 * exactly one {@code MountPublication} per non-empty mount and handing it to every bound
 * {@code MountPublicationHook}, entirely through a bare {@code TestFactories}-built
 * {@code Factory} — no Dagger composition and no running {@code HttpVerticle}.
 *
 * <p>TP-002 and TP-003 additionally bind a loopback {@code HttpServer} to prove, respectively, that a
 * regex-constrained route actually distinguishes a matching request from a non-matching one, and that
 * the schema source is consulted only once per operation at router build, never again per request.
 * Every other proof here inspects {@code createRouter}'s return value and the recording fixtures
 * directly.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MountPublicationHookTest {

    private static final String ID_ROUTE_VALUE = "/items/:id";
    private static final String DETAIL_PATH_TEMPLATE = "/items/{id: [0-9]+}/detail";
    private static final String DETAIL_ROUTE_REGEX = "^\\Q/items/\\E(?<id>[0-9]+)\\Q/detail\\E$";

    private HttpServer server;
    private WebClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ctx.succeedingThenComplete());
    }

    // --- TP-002 ---

    @Test
    @DisplayName("Publications record route values as registered and the effective security facts")
    void publicationRecordsRegisteredRouteValuesAndSecurity(Vertx vertx, VertxTestContext ctx) {
        RecordingPublicationHook hook = new RecordingPublicationHook();
        ActionRef adminViewAction = ActionRef.parse(ItemsResource.ADMIN_VIEW_ACTION);

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(bearerAuthHandler()))
                .authEnforcementCapability(Optional.of(AuthEnforcementCapability.INSTANCE))
                .actionRegistry(Optional.of(new StubActionRegistry(adminViewAction)))
                .authorizer(Optional.of(new PresenceOnlyAuthorizer()))
                .publicationHooks(new LinkedHashSet<>(List.of(hook)))
                .build();

        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ItemsResource()));

        mount.createRouter(vertx).onComplete(ctx.succeeding(apiRouter -> {
            ctx.verify(() -> {
                MountPublication publication = hook.onlyReceived();
                Map<String, OperationPublication> byId = publication.operations().stream()
                        .collect(Collectors.toMap(OperationPublication::operationId, op -> op));

                assertEquals("GET", byId.get("list").httpMethod());
                assertEquals("/items", byId.get("list").jaxRsPathTemplate());
                assertEquals("/items", byId.get("list").vertxRouteValue());
                assertFalse(byId.get("list").vertxRouteIsRegex());

                assertEquals("/items/{id}", byId.get("getById").jaxRsPathTemplate());
                assertEquals(ID_ROUTE_VALUE, byId.get("getById").vertxRouteValue());
                assertFalse(byId.get("getById").vertxRouteIsRegex());

                assertEquals(DETAIL_PATH_TEMPLATE, byId.get("getDetail").jaxRsPathTemplate());
                assertEquals(DETAIL_ROUTE_REGEX, byId.get("getDetail").vertxRouteValue());
                assertTrue(byId.get("getDetail").vertxRouteIsRegex());

                assertEquals("HEAD", byId.get("headById").httpMethod());

                OperationPublication create = byId.get("create");
                assertEquals(
                        new SecurityPolicy.Constrained(List.of("admin"), List.of("items:write"), true),
                        create.effectivePolicy());
                assertEquals(1, create.securityRequirementSets().size());
                assertEquals(
                        "bearerAuth",
                        create.securityRequirementSets().get(0).schemes().get(0).schemeName());
                assertEquals(
                        List.of("items:write"),
                        create.securityRequirementSets().get(0).schemes().get(0).scopes());

                // S6-007: the exact operation count, and requiresAction == false for every
                // non-action operation, not just "list".
                assertEquals(6, byId.size(), "exactly the six declared operations must be published");
                assertTrue(byId.get("adminView").requiresAction());
                for (String operationId : List.of("list", "getById", "getDetail", "headById", "create")) {
                    assertFalse(
                            byId.get(operationId).requiresAction(),
                            "operation '" + operationId + "' must not require an action");
                }
            });

            Router root = Router.router(vertx);
            root.route("/*").subRouter(apiRouter);
            vertx.createHttpServer()
                    .requestHandler(root)
                    .listen(0, "127.0.0.1")
                    .onComplete(ctx.succeeding(startedServer -> {
                        server = startedServer;
                        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                        client.get(startedServer.actualPort(), "127.0.0.1", "/items/42/detail")
                                .send()
                                .compose(matching -> {
                                    ctx.verify(() -> assertEquals(200, matching.statusCode()));
                                    return client.get(startedServer.actualPort(), "127.0.0.1", "/items/x/detail")
                                            .send();
                                })
                                .onComplete(ctx.succeeding(nonMatching -> {
                                    ctx.verify(() -> assertEquals(404, nonMatching.statusCode()));
                                    ctx.completeNow();
                                }));
                    }));
        }));
    }

    // --- TP-003 ---

    @Test
    @DisplayName("One source resolution feeds both the gate and the snapshot")
    void oneSourceResolutionFeedsGateAndSnapshot(Vertx vertx, VertxTestContext ctx) {
        JsonMapperProfile defaultProfile = appProfile("profile-default");
        JsonMapperProfile methodProfile = appProfile(ProfiledOperationsResource.METHOD_PROFILE_ID);
        DefaultJsonMapperProfileRegistry registry =
                new DefaultJsonMapperProfileRegistry(Set.of(defaultProfile, methodProfile));

        CountingSchemaSource source = new CountingSchemaSource((op, call) -> OperationSchemas.builder()
                .bodySchema(new JsonObject().put("type", "object").put("x-call", call))
                .parameterSchema(
                        ParamLocation.PATH,
                        "id",
                        new JsonObject().put("type", "string").put("x-call", call))
                .build());

        RecordingValidationStrategy strategy = new RecordingValidationStrategy();
        RecordingPublicationHook hook = new RecordingPublicationHook(applicationName -> true);

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .validationStrategies(Set.of(strategy))
                .operationSchemaSource(Optional.of(source))
                .jsonMapperProfileRegistry(registry)
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(RecordingValidationStrategy.ID)
                        .jsonProfile("profile-default")
                        .build())
                .publicationHooks(new LinkedHashSet<>(List.of(hook)))
                .build();

        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ProfiledOperationsResource()));

        mount.createRouter(vertx).onComplete(ctx.succeeding(apiRouter -> {
            ctx.verify(() -> assertEquals(2, source.calls()));

            Router root = Router.router(vertx);
            root.route("/*").subRouter(apiRouter);
            vertx.createHttpServer()
                    .requestHandler(root)
                    .listen(0, "127.0.0.1")
                    .onComplete(ctx.succeeding(startedServer -> {
                        server = startedServer;
                        client = WebClient.create(vertx);
                        int port = startedServer.actualPort();
                        sendRepeatedly(client, port, "/profiled/7/default", 5)
                                .compose(v -> sendRepeatedly(client, port, "/profiled/7/method", 5))
                                .onComplete(ctx.succeeding(v -> {
                                    ctx.verify(() -> {
                                        assertEquals(2, source.calls());

                                        MountPublication publication = hook.onlyReceived();
                                        Map<String, OperationPublication> byId = publication.operations().stream()
                                                .collect(Collectors.toMap(OperationPublication::operationId, op -> op));

                                        OperationPublication byDefault = byId.get("byDefaultProfile");
                                        OperationPublication byMethod = byId.get("byMethodProfile");

                                        assertTrue(byDefault.detail().gateInstalled());
                                        assertTrue(byMethod.detail().gateInstalled());
                                        assertEquals(
                                                "profile-default",
                                                byDefault.detail().profileId());
                                        assertEquals(
                                                ProfiledOperationsResource.METHOD_PROFILE_ID,
                                                byMethod.detail().profileId());

                                        OperationSchemas gateForDefault = strategy.received("byDefaultProfile");
                                        OperationSchemas gateForMethod = strategy.received("byMethodProfile");

                                        assertEquals(
                                                gateForDefault.bodySchema().orElseThrow(),
                                                byDefault.detail().schemas().body());
                                        assertEquals(
                                                gateForDefault
                                                        .parameterSchema(ParamLocation.PATH, "id")
                                                        .orElseThrow(),
                                                byDefault
                                                        .detail()
                                                        .schemas()
                                                        .parameters()
                                                        .get(new InputKey(ParamLocation.PATH, "id")));
                                        assertEquals(
                                                gateForMethod.bodySchema().orElseThrow(),
                                                byMethod.detail().schemas().body());
                                        assertEquals(
                                                gateForMethod
                                                        .parameterSchema(ParamLocation.PATH, "id")
                                                        .orElseThrow(),
                                                byMethod.detail()
                                                        .schemas()
                                                        .parameters()
                                                        .get(new InputKey(ParamLocation.PATH, "id")));
                                    });
                                    ctx.completeNow();
                                }));
                    }));
        }));
    }

    // --- TP-004 ---

    @Test
    @DisplayName("Captured schemas are detached from the source and the gate")
    void capturedSchemasAreDetachedFromSourceAndGate(Vertx vertx) {
        Object sentinelProvenance = new Object();
        CountingSchemaSource source = new CountingSchemaSource((op, call) -> {
            if (DualOperationResource.WITHOUT_BODY_OPERATION_ID.equals(op.operationId())) {
                return OperationSchemas.builder().build();
            }
            JsonObject properties = new JsonObject().put("name", new JsonObject().put("type", "string"));
            JsonObject body = new JsonObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", new JsonArray().add("name"));
            return OperationSchemas.builder()
                    .bodySchema(body, sentinelProvenance)
                    .parameterSchema(ParamLocation.PATH, "id", new JsonObject().put("type", "string"))
                    .parameterSchema(ParamLocation.QUERY, "q", new JsonObject().put("type", "string"))
                    .parameterSchema(ParamLocation.HEADER, "rogue", new JsonObject().put("type", "string"))
                    .build();
        });

        GateMutatingValidationStrategy strategy = new GateMutatingValidationStrategy();
        RecordingPublicationHook hook = new RecordingPublicationHook(applicationName -> true);

        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .validationStrategies(Set.of(strategy))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(GateMutatingValidationStrategy.ID)
                        .build())
                .operationSchemaSource(Optional.of(source))
                .publicationHooks(new LinkedHashSet<>(List.of(hook)))
                .build();

        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new DualOperationResource()));
        mount.createRouter(vertx);

        MountPublication publication = hook.onlyReceived();
        Map<String, OperationPublication> byId = publication.operations().stream()
                .collect(Collectors.toMap(OperationPublication::operationId, op -> op));
        OperationPublication withParams = byId.get("withParams");
        OperationPublication withoutBody = byId.get(DualOperationResource.WITHOUT_BODY_OPERATION_ID);

        CapturedSchemas captured = withParams.detail().schemas();

        // (i) mutate the source's own retained objects, at the root and the nested level.
        OperationSchemas retained = source.retained("withParams");
        JsonObject retainedBody = retained.bodySchema().orElseThrow();
        retainedBody.put("x-source-root", true);
        retainedBody.getJsonObject("properties").put("x-source-nested", true);

        assertFalse(captured.body().containsKey("x-gate"), "the copy must not carry the gate's mutation");
        assertFalse(captured.body().containsKey("x-source-root"), "the copy must not see a later source mutation");
        assertFalse(
                captured.body().getJsonObject("properties").containsKey("x-source-nested"),
                "the copy's nested object must not see a later source mutation");

        // (ii) decorate the captured copy, at the root and the nested level.
        captured.body().put("x-doc", true);
        captured.body().getJsonObject("properties").put("x-doc", true);

        OperationSchemas gateReceived = strategy.received("withParams");
        JsonObject gateBody = gateReceived.bodySchema().orElseThrow();
        assertTrue(gateBody.containsKey("x-gate"), "the gate's own copy must carry its own mutation");
        assertFalse(gateBody.containsKey("x-doc"), "the gate's objects must not see the capture's decoration");
        assertFalse(
                gateBody.getJsonObject("properties").containsKey("x-doc"),
                "the gate's nested object must not see the capture's decoration");

        Set<Object> sourceIdentities = collectIdentities(retainedBody);
        Set<Object> gateIdentities = collectIdentities(gateBody);
        Set<Object> copyIdentities = collectIdentities(captured.body());
        assertTrue(
                Collections.disjoint(copyIdentities, sourceIdentities),
                "no JsonObject/JsonArray/backing-collection reachable from the copy is the source's");
        assertTrue(
                Collections.disjoint(copyIdentities, gateIdentities),
                "no JsonObject/JsonArray/backing-collection reachable from the copy is the gate's");

        // S6-001: the identity walk must also cover the captured parameter schemas, not just the
        // body — GateMutatingValidationStrategy mutates every declared parameter's schema too.
        JsonObject capturedId = captured.parameters().get(new InputKey(ParamLocation.PATH, "id"));
        JsonObject capturedQ = captured.parameters().get(new InputKey(ParamLocation.QUERY, "q"));
        assertFalse(capturedId.containsKey("x-gate"), "the id parameter copy must not carry the gate's mutation");
        assertFalse(capturedQ.containsKey("x-gate"), "the q parameter copy must not carry the gate's mutation");

        JsonObject sourceIdParam =
                retained.parameterSchema(ParamLocation.PATH, "id").orElseThrow();
        JsonObject sourceQParam =
                retained.parameterSchema(ParamLocation.QUERY, "q").orElseThrow();
        JsonObject gateIdParam =
                gateReceived.parameterSchema(ParamLocation.PATH, "id").orElseThrow();
        JsonObject gateQParam =
                gateReceived.parameterSchema(ParamLocation.QUERY, "q").orElseThrow();
        assertTrue(gateIdParam.containsKey("x-gate"), "the gate's own id parameter copy must carry its own mutation");
        assertTrue(gateQParam.containsKey("x-gate"), "the gate's own q parameter copy must carry its own mutation");

        Set<Object> capturedIdIdentities = collectIdentities(capturedId);
        Set<Object> capturedQIdentities = collectIdentities(capturedQ);
        assertTrue(
                Collections.disjoint(capturedIdIdentities, collectIdentities(sourceIdParam)),
                "no JsonObject/JsonArray/backing-collection reachable from the captured id schema is the source's");
        assertTrue(
                Collections.disjoint(capturedIdIdentities, collectIdentities(gateIdParam)),
                "no JsonObject/JsonArray/backing-collection reachable from the captured id schema is the gate's");
        assertTrue(
                Collections.disjoint(capturedQIdentities, collectIdentities(sourceQParam)),
                "no JsonObject/JsonArray/backing-collection reachable from the captured q schema is the source's");
        assertTrue(
                Collections.disjoint(capturedQIdentities, collectIdentities(gateQParam)),
                "no JsonObject/JsonArray/backing-collection reachable from the captured q schema is the gate's");

        assertEquals(2, captured.parameters().size());
        assertTrue(captured.parameters().containsKey(new InputKey(ParamLocation.PATH, "id")));
        assertTrue(captured.parameters().containsKey(new InputKey(ParamLocation.QUERY, "q")));
        assertFalse(captured.parameters().containsKey(new InputKey(ParamLocation.HEADER, "rogue")));

        assertSame(sentinelProvenance, captured.bodyProvenance());

        CapturedSchemas capturedWithoutBody = withoutBody.detail().schemas();
        assertNull(capturedWithoutBody.body());
        assertNull(capturedWithoutBody.bodyProvenance());
    }

    // --- TP-005 ---

    @Test
    @DisplayName("Detail is captured only for mounts a sink wants")
    void detailOnlyForMountsTheHookWants(Vertx vertx) {
        RecordingPublicationHook hook = new RecordingPublicationHook(
                applicationName -> "a".equals(applicationName) || "c".equals(applicationName));
        RecordingValidationStrategy strategy = new RecordingValidationStrategy();

        JaxRsRouterMount.Factory factory1 = TestFactories.builder()
                .validationStrategies(Set.of(strategy))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(RecordingValidationStrategy.ID)
                        .build())
                .publicationHooks(new LinkedHashSet<>(List.of(hook)))
                .build();

        JaxRsRouterMount mountA = factory1.createApplicationMount(
                "/api/a/*", "openapi.json", Set.of(new EchoResource()), "a", ApplicationA.class);
        JaxRsRouterMount mountB = factory1.createApplicationMount(
                "/api/b/*", "openapi.json", Set.of(new EchoResource()), "b", ApplicationB.class);
        mountA.markValidated();
        mountB.markValidated();
        JaxRsRouterMount manualMount = factory1.create("/*", "openapi.json", Set.of(new EchoResource()));

        JaxRsRouterMount.Factory factory2 = TestFactories.builder()
                .publicationHooks(new LinkedHashSet<>(List.of(hook)))
                .build();
        JaxRsRouterMount mountC = factory2.createApplicationMount(
                "/api/c/*", "openapi.json", Set.of(new EchoResource()), "c", ApplicationC.class);
        mountC.markValidated();

        mountA.createRouter(vertx);
        mountB.createRouter(vertx);
        manualMount.createRouter(vertx);
        mountC.createRouter(vertx);

        assertEquals(Arrays.asList("a", "b", null, "c"), hook.wantsDetailArgs());

        MountPublication pubA = hook.onlyReceivedFor("/api/a/*");
        MountPublication pubB = hook.onlyReceivedFor("/api/b/*");
        MountPublication pubManual = hook.onlyReceivedFor("/*");
        MountPublication pubC = hook.onlyReceivedFor("/api/c/*");

        // S6-007: a non-empty check plus a per-element assertion, not allMatch over a list that
        // could vacuously be empty.
        assertEveryOperationHasDetail(pubA, true);
        assertNoOperationHasDetail(pubB);
        assertNoOperationHasDetail(pubManual);
        assertEveryOperationHasDetail(pubC, false);

        // Disagreeing-hooks row: two hooks in a fixed iteration order, only the second wants "d".
        RecordingPublicationHook hookWantsNothing = new RecordingPublicationHook(applicationName -> false);
        RecordingPublicationHook hookWantsD =
                new RecordingPublicationHook(applicationName -> "d".equals(applicationName));
        RecordingValidationStrategy strategy2 = new RecordingValidationStrategy();

        JaxRsRouterMount.Factory factory3 = TestFactories.builder()
                .validationStrategies(Set.of(strategy2))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(RecordingValidationStrategy.ID)
                        .build())
                .publicationHooks(new LinkedHashSet<>(List.of(hookWantsNothing, hookWantsD)))
                .build();

        JaxRsRouterMount mountD = factory3.createApplicationMount(
                "/api/d/*", "openapi.json", Set.of(new EchoResource()), "d", ApplicationD.class);
        JaxRsRouterMount mountE = factory3.createApplicationMount(
                "/api/e/*", "openapi.json", Set.of(new EchoResource()), "e", ApplicationE.class);
        mountD.markValidated();
        mountE.markValidated();

        mountD.createRouter(vertx);
        mountE.createRouter(vertx);

        MountPublication dViaHookWantsNothing = hookWantsNothing.onlyReceivedFor("/api/d/*");
        MountPublication dViaHookWantsD = hookWantsD.onlyReceivedFor("/api/d/*");
        assertSame(dViaHookWantsNothing, dViaHookWantsD, "every sink must receive the same publication instance");
        assertEveryOperationHasDetail(dViaHookWantsNothing);

        MountPublication eViaHookWantsNothing = hookWantsNothing.onlyReceivedFor("/api/e/*");
        assertNoOperationHasDetail(eViaHookWantsNothing);

        // S6-003: a row where the hook that wants detail comes FIRST, proving every hook is still
        // asked wantsDetail (no short-circuit) once an earlier hook already wants detail.
        RecordingPublicationHook hookWantsDFirst =
                new RecordingPublicationHook(applicationName -> "d".equals(applicationName));
        RecordingPublicationHook hookWantsNothingSecond = new RecordingPublicationHook(applicationName -> false);
        RecordingValidationStrategy strategy3 = new RecordingValidationStrategy();

        JaxRsRouterMount.Factory factory4 = TestFactories.builder()
                .validationStrategies(Set.of(strategy3))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(RecordingValidationStrategy.ID)
                        .build())
                .publicationHooks(new LinkedHashSet<>(List.of(hookWantsDFirst, hookWantsNothingSecond)))
                .build();

        JaxRsRouterMount mountDReordered = factory4.createApplicationMount(
                "/api/d/*", "openapi.json", Set.of(new EchoResource()), "d", ApplicationD.class);
        mountDReordered.markValidated();

        mountDReordered.createRouter(vertx);

        assertEquals(List.of("d"), hookWantsDFirst.wantsDetailArgs());
        assertEquals(
                List.of("d"),
                hookWantsNothingSecond.wantsDetailArgs(),
                "every sink must be asked wantsDetail even when an earlier sink already wants detail");
        MountPublication dReorderedViaFirst = hookWantsDFirst.onlyReceivedFor("/api/d/*");
        MountPublication dReorderedViaSecond = hookWantsNothingSecond.onlyReceivedFor("/api/d/*");
        assertSame(dReorderedViaFirst, dReorderedViaSecond, "every sink must receive the same publication instance");
        assertEveryOperationHasDetail(dReorderedViaFirst);
    }

    /**
     * Asserts every operation of {@code publication} carries a non-{@code null} {@code detail}, after
     * first asserting {@code publication} has at least one operation (S6-007: a non-empty check plus
     * a per-element assertion, since {@code allMatch} over an empty list is vacuously {@code true}).
     *
     * @param publication the publication to check
     */
    private static void assertEveryOperationHasDetail(MountPublication publication) {
        assertFalse(
                publication.operations().isEmpty(),
                "mount '" + publication.mountPath() + "' must have at least one operation");
        for (OperationPublication operation : publication.operations()) {
            assertNotNull(
                    operation.detail(),
                    "operation '" + operation.operationId() + "' of mount '" + publication.mountPath()
                            + "' must carry detail");
        }
    }

    /**
     * Asserts every operation of {@code publication} carries detail whose {@code gateInstalled} equals
     * {@code gateInstalled}, after first asserting {@code publication} has at least one operation.
     *
     * @param publication   the publication to check
     * @param gateInstalled the expected {@code gateInstalled} value for every operation's detail
     */
    private static void assertEveryOperationHasDetail(MountPublication publication, boolean gateInstalled) {
        assertEveryOperationHasDetail(publication);
        for (OperationPublication operation : publication.operations()) {
            assertEquals(
                    gateInstalled,
                    operation.detail().gateInstalled(),
                    "operation '" + operation.operationId() + "' of mount '" + publication.mountPath()
                            + "' gateInstalled mismatch");
        }
    }

    /**
     * Asserts every operation of {@code publication} carries a {@code null} {@code detail}, after
     * first asserting {@code publication} has at least one operation (S6-007: a non-empty check plus
     * a per-element assertion, since {@code allMatch} over an empty list is vacuously {@code true}).
     *
     * @param publication the publication to check
     */
    private static void assertNoOperationHasDetail(MountPublication publication) {
        assertFalse(
                publication.operations().isEmpty(),
                "mount '" + publication.mountPath() + "' must have at least one operation");
        for (OperationPublication operation : publication.operations()) {
            assertNull(
                    operation.detail(),
                    "operation '" + operation.operationId() + "' of mount '" + publication.mountPath()
                            + "' must carry no detail");
        }
    }

    // --- TP-008 ---

    @Test
    @DisplayName("createRouter completes only when every sink's future has")
    void createRouterAwaitsEveryHookFuture(Vertx vertx) {
        verifySuccessScenario(vertx, "/api/one/*", Set.of(new EchoResource()));
        verifySuccessScenario(vertx, "/api/none/*", Set.of());
        verifyFailureScenario(vertx, "/api/one/*", Set.of(new EchoResource()));
        verifyFailureScenario(vertx, "/api/none/*", Set.of());
    }

    private static void verifySuccessScenario(Vertx vertx, String mountPath, Set<Object> resources) {
        PromiseControlledPublicationHook hook1 = new PromiseControlledPublicationHook();
        PromiseControlledPublicationHook hook2 = new PromiseControlledPublicationHook();
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .publicationHooks(new LinkedHashSet<>(List.of(hook1, hook2)))
                .build();
        JaxRsRouterMount mount = factory.create(mountPath, "openapi.json", resources);

        Future<Router> future = mount.createRouter(vertx);

        assertEquals(1, hook1.received().size(), "both sinks must be called before any promise completes");
        assertEquals(1, hook2.received().size(), "both sinks must be called before any promise completes");
        assertSame(hook1.received().get(0), hook2.received().get(0));
        assertFalse(future.isComplete(), "the router future must not complete before any sink future does");

        hook1.latestPromise().complete();
        assertFalse(future.isComplete(), "the router future must stay incomplete after only the first sink completes");

        hook2.latestPromise().complete();
        assertTrue(future.succeeded(), "the router future must succeed once every sink future has");
    }

    private static void verifyFailureScenario(Vertx vertx, String mountPath, Set<Object> resources) {
        PromiseControlledPublicationHook hook1 = new PromiseControlledPublicationHook();
        PromiseControlledPublicationHook hook2 = new PromiseControlledPublicationHook();
        RuntimeException sentinel = new RuntimeException("sentinel failure for " + mountPath);
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .publicationHooks(new LinkedHashSet<>(List.of(hook1, hook2)))
                .build();
        JaxRsRouterMount mount = factory.create(mountPath, "openapi.json", resources);

        Future<Router> future = mount.createRouter(vertx);

        assertEquals(1, hook1.received().size(), "both sinks must be called before the promise is failed");
        assertEquals(1, hook2.received().size(), "both sinks must be called before the promise is failed");
        assertFalse(future.isComplete());

        hook1.latestPromise().fail(sentinel);

        assertTrue(future.failed(), "a failed sink future must fail the router future");
        assertSame(sentinel, future.cause());
    }

    // --- Shared helpers ---

    private static SecuritySchemeHandler bearerAuthHandler() {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return "bearerAuth";
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                registry.authenticationHandler(SimpleAuthenticationHandler.create());
            }
        };
    }

    private static JsonMapperProfile appProfile(String id) {
        return JsonMapperProfiles.of(
                JsonProfileId.of(id), new ObjectMapper().registerModule(VertxJsonSupport.module()));
    }

    private static Future<Void> sendRepeatedly(WebClient client, int port, String path, int times) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < times; i++) {
            chain = chain.compose(
                    v -> client.get(port, "127.0.0.1", path).send().mapEmpty());
        }
        return chain;
    }

    /**
     * Collects the identity of every {@link JsonObject}/{@link JsonArray} reachable from {@code root}
     * (including {@code root} itself) and each such container's backing {@code Map}/{@code List}
     * (E14): the returned set uses reference identity for both membership and, via
     * {@link Collections#disjoint}, comparison.
     *
     * @param root the root object to walk
     * @return an identity-based set of every reachable container and backing collection
     */
    private static Set<Object> collectIdentities(JsonObject root) {
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root, identities);
        return identities;
    }

    private static void walk(JsonObject json, Set<Object> identities) {
        identities.add(json);
        identities.add(json.getMap());
        for (Object value : json.getMap().values()) {
            if (value instanceof JsonObject nested) {
                walk(nested, identities);
            } else if (value instanceof JsonArray array) {
                walk(array, identities);
            }
        }
    }

    private static void walk(JsonArray array, Set<Object> identities) {
        identities.add(array);
        identities.add(array.getList());
        for (Object value : array.getList()) {
            if (value instanceof JsonObject nested) {
                walk(nested, identities);
            } else if (value instanceof JsonArray nestedArray) {
                walk(nestedArray, identities);
            }
        }
    }

    /** {@link ActionRegistry} stand-in holding exactly the actions given at construction. */
    private static final class StubActionRegistry implements ActionRegistry {
        private final Set<ActionRef> known;

        StubActionRegistry(ActionRef... actions) {
            this.known = Set.of(actions);
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return known.stream().map(ActionDefinition::new).toList();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return known.contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return known.contains(action);
        }
    }

    /**
     * {@link Authorizer} whose decision methods are never exercised: only its presence matters to
     * {@code resolveRequiredAction}'s fail-closed presence checks (TP-002 registers, but never
     * dispatches, its {@code @RequiresAction} operation).
     */
    private static final class PresenceOnlyAuthorizer implements Authorizer {
        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException("PresenceOnlyAuthorizer is a presence-only fixture");
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("PresenceOnlyAuthorizer is a presence-only fixture");
        }
    }
}
