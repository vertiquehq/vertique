// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.security.DefaultChannelIdentityManager;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.rest.security.dispatch.SecurityContextServiceDispatchEncoder;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.authz.DefaultActionRegistry;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceHandler;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.config.ServiceAuthorizationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.interceptor.ServiceAuthorizationInterceptor;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.security.RolesAllowed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that a permitted WebSocket upgrade grants no standing authorization: every later message
 * that calls a policy-protected service is checked again at the service.
 *
 * <p>The upgrade goes through the real admission pipeline (authentication, identity resolution,
 * policy enforcement) of {@link WebSocketEndpointRegistrar}. The endpoint is admitted by a role
 * policy; the services it calls are protected by different policies, so admission and service
 * access are independent decisions. The services are registered by hand with the real
 * {@link ServiceAuthorizationInterceptor} on an event bus consumer and reached through a
 * {@link ServiceClientFactory} proxy, which captures the caller bound on the message thread.
 *
 * <p>The framework defines no WebSocket close code for a service denial, so the nested endpoint
 * echoes an outcome text frame: {@code OK:<result>} or {@code DENIED:<exception simple name>}. The
 * explicit counters (effects, decision events per boundary, authorizer calls) are the assertions
 * that decide each scenario; the echo only reports what the client observed.
 *
 * <p>Arbitrary handler code and ordinary Java calls are not enforced; only service dispatch is.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TypedPolicyServiceWebSocketIT {

    private static final String EXECUTE_ACTION = "svc.exec.run";
    private static final long WAIT_SECONDS = 10;

    // --- Policies ---

    /** Admission: the caller must hold the member role. */
    @RolesAllowed("member")
    public interface MemberAdmissionPolicy extends AccessPolicy {}

    /** Service access: the caller must hold the editor role. */
    @RolesAllowed("editor")
    public interface EditorRolePolicy extends AccessPolicy {}

    /** Service access: the configured authorizer decides the registered execute action. */
    @RequiresAction(EXECUTE_ACTION)
    public interface ExecuteActionPolicy extends AccessPolicy {}

    // --- Services ---

    /** Content operations guarded by a role policy and an action policy. */
    @ServiceContract(namespace = "it", value = "ws-content")
    public interface ContentService {

        /**
         * Edits content.
         *
         * @param payload the content
         * @return the stored content
         */
        @RequiresPolicy(EditorRolePolicy.class)
        @ServiceOperation("edit")
        Future<String> edit(String payload);

        /**
         * Runs a job.
         *
         * @param payload the job
         * @return the job outcome
         */
        @RequiresPolicy(ExecuteActionPolicy.class)
        @ServiceOperation("execute")
        Future<String> execute(String payload);
    }

    /** Document operation guarded by a role policy; the handler also checks ownership. */
    @ServiceContract(namespace = "it", value = "ws-documents")
    public interface DocumentService {

        /**
         * Renames a document owned by the caller.
         *
         * @param documentId the document
         * @return the renamed document
         */
        @RequiresPolicy(EditorRolePolicy.class)
        @ServiceOperation("rename")
        Future<String> rename(String documentId);
    }

    /** Direct implementation that counts every business effect. */
    public static final class CountingContentService implements ContentService {
        private final AtomicInteger edits = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public Future<String> edit(String payload) {
            edits.incrementAndGet();
            return Future.succeededFuture("edit:" + payload);
        }

        @Override
        public Future<String> execute(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("execute:" + payload);
        }

        int effects() {
            return edits.get() + executions.get();
        }

        void reset() {
            edits.set(0);
            executions.set(0);
        }
    }

    /**
     * Handler-pattern implementation: the capability policy admits any editor, then the handler
     * refuses documents the caller does not own, before any effect.
     */
    public static final class OwnershipCheckingDocumentHandler implements ServiceHandler<DocumentService> {
        private static final Map<String, String> OWNERS = Map.of("doc-bob", "bob", "doc-carol", "carol");
        private final AtomicInteger renames = new AtomicInteger();

        /**
         * Renames the document when the caller owns it.
         *
         * @param documentId the document
         * @param caller the propagated caller
         * @return the renamed document, or a failed future when the caller does not own it
         */
        public Future<String> rename(String documentId, SecurityContext caller) {
            if (!caller.identity().actor().id().equals(OWNERS.get(documentId))) {
                return Future.failedFuture(new NotFoundException("no such document for the caller"));
            }
            renames.incrementAndGet();
            return Future.succeededFuture("rename:" + documentId);
        }

        int effects() {
            return renames.get();
        }

        void reset() {
            renames.set(0);
        }
    }

    // --- Endpoint ---

    /**
     * Endpoint admitted by {@link MemberAdmissionPolicy}. Each text message is a command of the form
     * {@code <verb>:<argument>} that calls a service and echoes the outcome.
     */
    @WebSocketEndpoint("/ws/guarded")
    @RequiresPolicy(MemberAdmissionPolicy.class)
    public static final class GuardedEndpoint {
        private final Vertx vertx;
        private final ContentService content;
        private final DocumentService documents;
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicReference<SecurityContext> admittedCaller = new AtomicReference<>();

        GuardedEndpoint(Vertx vertx, ContentService content, DocumentService documents) {
            this.vertx = vertx;
            this.content = content;
            this.documents = documents;
        }

        /**
         * Records that the upgrade reached the endpoint.
         *
         * @param session the session
         * @param caller the caller admitted by the upgrade
         */
        @OnOpen
        public void onOpen(WebSocketSession session, SecurityContext caller) {
            sessionId.set(session.id());
            admittedCaller.set(caller);
            opened.incrementAndGet();
        }

        /**
         * Calls the service named by the command and echoes the outcome.
         *
         * @param session the session
         * @param command the command text
         * @return a future completing after the echo was written
         */
        @OnMessage
        public Future<Void> onMessage(WebSocketSession session, String command) {
            int separator = command.indexOf(':');
            String verb = command.substring(0, separator);
            String argument = command.substring(separator + 1);
            Future<String> outcome =
                    switch (verb) {
                        case "edit" -> content.edit(argument);
                        case "execute" -> content.execute(argument);
                        case "rename" -> documents.rename(argument);
                        case "detached-edit" -> onContextWithoutCaller(() -> content.edit(argument));
                        default -> Future.failedFuture(new IllegalArgumentException("unknown command " + verb));
                    };
            return outcome.transform(
                    ar -> session.sendText(ar.succeeded() ? "OK:" + ar.result() : "DENIED:" + simpleNameOf(ar)));
        }

        private static String simpleNameOf(io.vertx.core.AsyncResult<String> failed) {
            return failed.cause().getClass().getSimpleName();
        }

        /** Calls from a fresh context that carries no security context, as code that lost it would. */
        private Future<String> onContextWithoutCaller(Supplier<Future<String>> call) {
            Promise<String> promise = Promise.promise();
            ContextInternal fresh = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            fresh.runOnContext(v -> call.get().onComplete(promise));
            return promise.future();
        }

        int opened() {
            return opened.get();
        }

        String sessionId() {
            return sessionId.get();
        }

        SecurityContext admittedCaller() {
            return admittedCaller.get();
        }

        void reset() {
            opened.set(0);
            sessionId.set(null);
            admittedCaller.set(null);
        }
    }

    // --- Scenarios ---

    @Test
    @DisplayName("a permitted upgrade grants no standing access: every message is rechecked at the service")
    void shouldRecheckServiceAccessAfterPermittedUpgrade(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            messageFromCallerWithoutTheServiceRoleIsDenied(app);
            everyMessageFromCallerWithTheServiceRoleReachesTheService(app);
            reducedClaimsDenyTheNextMessageOfAnAdmittedConnection(app);
        }
    }

    @Test
    @DisplayName("a caller without the admission role is refused before the endpoint")
    void shouldRefuseUpgradeWithoutAdmissionRole(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            deniedUpgradeNeverReachesTheEndpoint(app);
        }
    }

    @Test
    @DisplayName("a permitted upgrade that sends no message touches no service")
    void shouldDoNoServiceWorkForPermittedUpgradeWithoutMessage(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            permittedUpgradeDoesNoServiceWork(app);
        }
    }

    @Test
    @DisplayName("a service call from a context with no bound caller is denied closed")
    void shouldDenyServiceCallWithNoBoundContext(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            messageWithNoSecurityContextBoundIsDeniedClosed(app);
        }
    }

    @Test
    @DisplayName("an authorizer that throws or fails denies the action policy without effect")
    void shouldDenyActionPolicyWhenAuthorizerFails(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            failingAuthorizerDeniesActionPolicyWithoutEffect(app);
        }
    }

    @Test
    @DisplayName("a document the caller does not own is refused after the capability permit")
    void shouldRefuseOwnershipAfterCapabilityPermit(Vertx vertx) throws Exception {
        try (Deployment app = Deployment.start(vertx)) {
            resourceRefusalAfterCapabilityHasNoEffect(app);
        }
    }

    private static void deniedUpgradeNeverReachesTheEndpoint(Deployment app) throws Exception {
        // Given a caller without the member role
        app.resetObservations();

        // When the caller attempts the upgrade
        Throwable failure = app.connect("dave|visitor").awaitFailure();

        // Then the handshake is refused before the endpoint, with one restrictive admission decision
        String scenario = "denied upgrade";
        assertEquals(
                403, assertInstanceOf(UpgradeRejectedException.class, failure).getStatus(), scenario);
        assertEquals(0, app.endpoint.opened(), scenario + ": the open callback must not run");
        assertEquals(1, app.upgradeEvents.size(), scenario + ": exactly one admission decision");
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.upgradeEvents.get(0), scenario);
        assertEquals(0, app.serviceEvents.size(), scenario + ": no service decision");
        assertEquals(0, app.totalEffects(), scenario + ": no effect");
        assertEquals(0, app.authorizer.calls(), scenario + ": no authorizer call");
    }

    private static void permittedUpgradeDoesNoServiceWork(Deployment app) throws Exception {
        // Given a caller with the member role but no service role
        app.resetObservations();

        // When the caller upgrades and sends nothing
        Client client = app.connect("alice|member").awaitClient();

        // Then the endpoint opened after one permitted admission decision, and no service was touched
        String scenario = "permitted upgrade";
        app.awaitCondition(() -> app.endpoint.opened() == 1, scenario + ": the open callback must run");
        assertEquals(1, app.upgradeEvents.size(), scenario + ": exactly one admission decision");
        assertPermitted(app.upgradeEvents.get(0), scenario);
        assertEquals(0, app.serviceEvents.size(), scenario + ": no service decision");
        assertEquals(0, app.totalEffects(), scenario + ": no effect");
        assertEquals(0, app.authorizer.calls(), scenario + ": no authorizer call");
        client.close();
    }

    private static void messageFromCallerWithoutTheServiceRoleIsDenied(Deployment app) throws Exception {
        // Given an admitted caller that lacks the editor role the service requires
        Client client = app.connect("alice|member").awaitClient();
        app.resetObservations();

        // When the caller sends a message that edits content
        String echo = client.exchange("edit:draft");

        // Then the service denies it without effect, and the upgrade policy is not consulted again
        String scenario = "caller without the service role";
        assertTrue(echo.startsWith("DENIED:"), scenario + ": echo was " + echo);
        assertEquals(0, app.content.effects(), scenario + ": no effect");
        assertEquals(1, app.serviceEvents.size(), scenario + ": exactly one service decision");
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.serviceEvents.get(0), scenario);
        assertEquals(0, app.upgradeEvents.size(), scenario + ": the upgrade is not decided again");
        assertEquals(0, app.authorizer.calls(), scenario + ": a role policy needs no authorizer");
        client.close();
    }

    private static void everyMessageFromCallerWithTheServiceRoleReachesTheService(Deployment app) throws Exception {
        // Given an admitted caller that holds the editor role
        Client client = app.connect("bob|member,editor").awaitClient();
        app.resetObservations();

        // When the caller sends two messages that edit content
        String first = client.exchange("edit:one");
        String second = client.exchange("edit:two");

        // Then both are permitted by their own service decision and take effect
        String scenario = "caller with the service role";
        assertEquals("OK:edit:one", first, scenario);
        assertEquals("OK:edit:two", second, scenario);
        assertEquals(2, app.content.effects(), scenario + ": one effect per message");
        assertEquals(2, app.serviceEvents.size(), scenario + ": one service decision per message");
        assertPermitted(app.serviceEvents.get(0), scenario);
        assertPermitted(app.serviceEvents.get(1), scenario);
        assertEquals(0, app.upgradeEvents.size(), scenario + ": the upgrade is not decided again");
        client.close();
    }

    private static void messageWithNoSecurityContextBoundIsDeniedClosed(Deployment app) throws Exception {
        // Given an admitted editor whose message handler calls the service from a context that
        // carries no security context
        Client client = app.connect("bob|member,editor").awaitClient();
        app.resetObservations();

        // When the caller sends such a message
        String echo = client.exchange("detached-edit:draft");

        // Then the service denies it closed, attributing the attempt to an anonymous actor
        String scenario = "no security context bound";
        assertTrue(echo.startsWith("DENIED:"), scenario + ": echo was " + echo);
        assertEquals(0, app.content.effects(), scenario + ": no effect");
        assertEquals(1, app.serviceEvents.size(), scenario + ": exactly one service decision");
        AuthorizationDecisionEvent event = app.serviceEvents.get(0);
        assertDenied(AuthzReasonCodes.AUTHENTICATION_REQUIRED, event, scenario);
        assertEquals(
                PrincipalType.ANONYMOUS,
                event.request().securityContext().identity().actor().type(),
                scenario + ": the denial is attributed to the anonymous actor");
        client.close();
    }

    private static void failingAuthorizerDeniesActionPolicyWithoutEffect(Deployment app) throws Exception {
        // Given an admitted caller and an action policy whose authorizer can be told to fail
        Client client = app.connect("grace|member,executor").awaitClient();
        String scenario = "authorizer";

        // When the authorizer answers normally
        app.resetObservations();
        String answered = client.exchange("execute:job");

        // Then the action runs after exactly one authorizer call
        assertEquals("OK:execute:job", answered, scenario + " answering");
        assertEquals(1, app.content.effects(), scenario + " answering: one effect");
        assertEquals(1, app.authorizer.calls(), scenario + " answering: one authorizer call");
        assertEquals(1, app.serviceEvents.size(), scenario + " answering: one service decision");
        assertPermitted(app.serviceEvents.get(0), scenario + " answering");

        // When the authorizer throws, and when it returns a failed future
        for (ScriptedAuthorizer.Mode failure :
                List.of(ScriptedAuthorizer.Mode.THROW, ScriptedAuthorizer.Mode.FAILED_FUTURE)) {
            app.authorizer.mode(failure);
            app.resetObservations();
            String echo = client.exchange("execute:job");

            // Then the message is denied closed, without effect
            String label = scenario + " " + failure;
            assertTrue(echo.startsWith("DENIED:"), label + ": echo was " + echo);
            assertEquals(0, app.content.effects(), label + ": no effect");
            assertEquals(1, app.authorizer.calls(), label + ": one authorizer call");
            assertEquals(1, app.serviceEvents.size(), label + ": exactly one service decision");
            assertDenied(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR, app.serviceEvents.get(0), label);
        }
        app.authorizer.mode(ScriptedAuthorizer.Mode.DELEGATE);
        client.close();
    }

    private static void reducedClaimsDenyTheNextMessageOfAnAdmittedConnection(Deployment app) throws Exception {
        // Given an admitted editor whose first message succeeds
        app.resetObservations();
        Client client = app.connect("heidi|member,editor").awaitClient();
        app.awaitCondition(() -> app.endpoint.opened() == 1, "claims changed: the open callback must run");
        SecurityContext admitted = app.endpoint.admittedCaller();
        String sessionId = app.endpoint.sessionId();
        assertEquals("OK:edit:before", client.exchange("edit:before"), "claims changed: before the change");
        assertEquals(1, app.content.effects(), "claims changed: before the change takes effect");

        // When the connection's available claims are reduced to the member role only
        Deployment.await(app.channelManager.refreshIdentity(sessionId, new RoleRestrictedContext(admitted, "member")));
        app.resetObservations();
        String echo = client.exchange("edit:after");

        // Then the service denies the next message although the upgrade was permitted
        String scenario = "claims changed";
        assertTrue(echo.startsWith("DENIED:"), scenario + ": echo was " + echo);
        assertEquals(0, app.content.effects(), scenario + ": no effect after the change");
        assertEquals(1, app.serviceEvents.size(), scenario + ": exactly one service decision");
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.serviceEvents.get(0), scenario);
        assertEquals(0, app.upgradeEvents.size(), scenario + ": the upgrade is not decided again");
        client.close();
    }

    private static void resourceRefusalAfterCapabilityHasNoEffect(Deployment app) throws Exception {
        // Given an admitted editor who does not own the document
        Client client = app.connect("carol|member,editor").awaitClient();
        app.resetObservations();

        // When the caller renames a document owned by someone else
        String refused = client.exchange("rename:doc-bob");

        // Then the capability gate permitted the call but the handler refused it before any effect
        String scenario = "resource refusal";
        assertEquals("DENIED:NotFoundException", refused, scenario);
        assertEquals(0, app.documents.effects(), scenario + ": no effect");
        assertEquals(1, app.serviceEvents.size(), scenario + ": exactly one service decision");
        assertPermitted(app.serviceEvents.get(0), scenario + " (capability)");

        // And when the same caller renames a document they own, it takes effect
        app.resetObservations();
        assertEquals("OK:rename:doc-carol", client.exchange("rename:doc-carol"), scenario + " control");
        assertEquals(1, app.documents.effects(), scenario + " control: one effect");
        client.close();
    }

    // --- Assertions ---

    private static void assertDenied(String reasonCode, AuthorizationDecisionEvent event, String label) {
        assertFalse(event.decision().permitted(), label + ": the decision must be a denial");
        assertEquals(reasonCode, event.decision().reasonCode(), label + ": denial reason");
    }

    private static void assertPermitted(AuthorizationDecisionEvent event, String label) {
        assertTrue(event.decision().permitted(), label + ": the decision must permit");
        assertEquals(AuthzReasonCodes.PERMITTED, event.decision().reasonCode(), label + ": permit reason");
    }

    // --- Client ---

    /** A connected WebSocket whose text frames are queued for the test thread. */
    private static final class Client {
        private final WebSocket socket;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        private Client(WebSocket socket) {
            this.socket = socket;
            socket.textMessageHandler(frames::add);
        }

        String exchange(String text) throws Exception {
            Deployment.await(socket.writeTextMessage(text));
            String reply = frames.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(reply, "no reply to " + text);
            return reply;
        }

        void close() throws Exception {
            if (!socket.isClosed()) {
                Deployment.await(socket.close());
            }
        }
    }

    /** A pending connection attempt. */
    private static final class Connection {
        private final Future<WebSocket> pending;
        private final Deployment owner;

        private Connection(Future<WebSocket> pending, Deployment owner) {
            this.pending = pending;
            this.owner = owner;
        }

        Client awaitClient() throws Exception {
            Client client = new Client(Deployment.await(pending));
            owner.register(client);
            return client;
        }

        Throwable awaitFailure() throws Exception {
            try {
                owner.register(new Client(Deployment.await(pending)));
            } catch (ExecutionException failure) {
                return failure.getCause();
            }
            throw new AssertionError("the upgrade was expected to be refused");
        }
    }

    // --- Deployment ---

    /**
     * The whole application under test: the upgrade pipeline, the registered services behind an
     * authorizing event bus consumer, and the observations the scenarios assert on. Closing it
     * releases every client, the server and every consumer before the owning Vert.x is closed.
     */
    private static final class Deployment implements AutoCloseable {
        private final WebSocketClient wsClient;
        private final HttpServer server;
        private final Resilience resilience;
        private final List<MessageConsumer<?>> consumers;
        private final List<Client> clients = new CopyOnWriteArrayList<>();

        final DefaultChannelIdentityManager channelManager;
        final GuardedEndpoint endpoint;
        final CountingContentService content;
        final OwnershipCheckingDocumentHandler documents;
        final ScriptedAuthorizer authorizer;
        final List<AuthorizationDecisionEvent> upgradeEvents;
        final List<AuthorizationDecisionEvent> serviceEvents;

        private Deployment(
                WebSocketClient wsClient,
                HttpServer server,
                Resilience resilience,
                List<MessageConsumer<?>> consumers,
                DefaultChannelIdentityManager channelManager,
                GuardedEndpoint endpoint,
                CountingContentService content,
                OwnershipCheckingDocumentHandler documents,
                ScriptedAuthorizer authorizer,
                List<AuthorizationDecisionEvent> upgradeEvents,
                List<AuthorizationDecisionEvent> serviceEvents) {
            this.wsClient = wsClient;
            this.server = server;
            this.resilience = resilience;
            this.consumers = consumers;
            this.channelManager = channelManager;
            this.endpoint = endpoint;
            this.content = content;
            this.documents = documents;
            this.authorizer = authorizer;
            this.upgradeEvents = upgradeEvents;
            this.serviceEvents = serviceEvents;
        }

        static Deployment start(Vertx vertx) throws Exception {
            List<AuthorizationDecisionEvent> upgradeEvents = new CopyOnWriteArrayList<>();
            List<AuthorizationDecisionEvent> serviceEvents = new CopyOnWriteArrayList<>();
            CountingContentService content = new CountingContentService();
            OwnershipCheckingDocumentHandler documents = new OwnershipCheckingDocumentHandler();
            ScriptedAuthorizer authorizer = new ScriptedAuthorizer();
            List<MessageConsumer<?>> consumers = new ArrayList<>();
            Resilience resilience = Resilience.create(vertx);
            WebSocketClient wsClient = vertx.createWebSocketClient();
            try {
                ServiceContractRegistry registry = registerServices(vertx, content, documents);
                startServiceConsumers(vertx, registry, authorizer, serviceEvents, consumers);
                ServiceClientFactory serviceClients = serviceClients(vertx, registry, resilience);
                GuardedEndpoint endpoint = new GuardedEndpoint(
                        vertx,
                        serviceClients.create(ContentService.class),
                        serviceClients.create(DocumentService.class));
                SecurityEventEmitter upgradeEmitter =
                        new SecurityEventEmitter(Set.of(recordingObserver(upgradeEvents)));
                HolderBackedSecurityRuntime securityRuntime = new HolderBackedSecurityRuntime((sc, secure) -> null);
                ContextHolder correlationHolder = correlationHolder();
                DefaultChannelIdentityManager channelManager =
                        new DefaultChannelIdentityManager(vertx, upgradeEmitter, correlationHolder);
                HttpServer server = await(startUpgradeServer(
                        vertx, endpoint, channelManager, upgradeEmitter, securityRuntime, correlationHolder));
                return new Deployment(
                        wsClient,
                        server,
                        resilience,
                        consumers,
                        channelManager,
                        endpoint,
                        content,
                        documents,
                        authorizer,
                        upgradeEvents,
                        serviceEvents);
            } catch (Throwable startFailure) {
                try {
                    release(null, wsClient, consumers, resilience, List.of());
                } catch (Exception suppressed) {
                    startFailure.addSuppressed(suppressed);
                }
                throw startFailure;
            }
        }

        // --- Wiring: services ---

        private static ServiceContractRegistry registerServices(
                Vertx vertx, CountingContentService content, OwnershipCheckingDocumentHandler documents) {
            registerCodec(vertx, "dispatch.envelope");
            registerCodec(vertx, "dispatch.result");
            return ServiceContractRegistry.build(
                    Set.<Object>of(content, documents), Set.of(), new JsonObject(), Map.of());
        }

        private static void registerCodec(Vertx vertx, String name) {
            try {
                vertx.eventBus().registerCodec(new LocalMessageCodec<>(name));
            } catch (IllegalStateException alreadyRegistered) {
                // The codec is shared by every registration on this Vert.x instance.
            }
        }

        private static void startServiceConsumers(
                Vertx vertx,
                ServiceContractRegistry registry,
                ScriptedAuthorizer authorizer,
                List<AuthorizationDecisionEvent> serviceEvents,
                List<MessageConsumer<?>> consumers)
                throws Exception {
            List<ServiceMethodMeta> metas = registry.entries().stream()
                    .flatMap(entry -> entry.operations().values().stream())
                    .toList();
            ActionRegistry actions = new DefaultActionRegistry(
                    Set.<ActionContributor>of(() -> List.of(new ActionDefinition(ActionRef.parse(EXECUTE_ACTION)))));
            ServiceAuthorizationInterceptor gate = new ServiceAuthorizationInterceptor(
                    Optional.of(authorizer),
                    Optional.of(actions),
                    new SecurityEventEmitter(Set.of(recordingObserver(serviceEvents))),
                    new DefaultContextHolder(),
                    Set.copyOf(metas),
                    ServiceAuthorizationConfig.defaults(),
                    TestResilience.shared());
            for (ServiceMethodMeta meta : metas) {
                ServiceMethodInvoker invoker =
                        new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(gate), null);
                MessageConsumer<DispatchEnvelope<?>> consumer = vertx.eventBus().consumer(meta.address(), invoker);
                consumers.add(consumer);
                await(consumer.completion());
            }
        }

        private static ServiceClientFactory serviceClients(
                Vertx vertx, ServiceContractRegistry registry, Resilience resilience) {
            ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
            when(supervisor.isAvailable(any())).thenReturn(true);
            ServiceRequestSender sender = new ServiceRequestSender(
                    new EventBusClient(vertx, new EventBusExceptionMapper()),
                    supervisor,
                    new ServiceResilienceConfigAdapter(
                            resilience, new ServicesConfig(null, List.of()), Map.of(), Optional.empty()));
            DispatchEnvelopeBuilder envelopes = new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                    new ServiceDispatchContextRegistry(Set.of(new SecurityContextServiceDispatchEncoder()), Set.of()),
                    new DefaultContextHolder()));
            return new ServiceClientFactory(sender, registry, envelopes);
        }

        // --- Wiring: upgrade ---

        private static Future<HttpServer> startUpgradeServer(
                Vertx vertx,
                GuardedEndpoint endpoint,
                DefaultChannelIdentityManager channelManager,
                SecurityEventEmitter upgradeEmitter,
                HolderBackedSecurityRuntime securityRuntime,
                ContextHolder correlationHolder) {
            IdentityResolutionMiddleware identity = new IdentityResolutionMiddleware(
                    Set.of(new WebSocketSecurityPipelineIT.EvidenceBasedIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    upgradeEmitter,
                    securityRuntime,
                    correlationHolder);
            SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                    Optional.of(new WebSocketSecurityPipelineIT.RoleCheckDecisionPoint()),
                    Optional.empty(),
                    Set.of(),
                    upgradeEmitter,
                    correlationHolder,
                    securityRuntime,
                    Optional.empty(),
                    Resilience.create(vertx));
            WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                    new WebSocketMessageCodec(),
                    enforcer,
                    identity.handlerFor(InvocationOrigin.of(DispatchBoundary.WEBSOCKET)),
                    securityRuntime,
                    Set.of(new WebSocketSecurityPipelineIT.StubBearerAuthHandler()),
                    null,
                    null,
                    new WebSocketChannelAdapter(channelManager, securityRuntime),
                    null,
                    null);
            Router router = Router.router(vertx);
            router.route("/*").handler(new RequestContextLifecycle());
            registrar.registerAll(Set.of(endpoint), router);
            return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1");
        }

        private static ContextHolder correlationHolder() {
            CorrelationContext correlation = new CorrelationContextFactory(Optional.empty())
                    .create(
                            new CorrelationIdentifier("test-req", "test"),
                            new CorrelationIdentifier("test-cor", "test"));
            return new ContextHolder() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<T> current(Class<T> type) {
                    return type == CorrelationContext.class ? (Optional<T>) Optional.of(correlation) : Optional.empty();
                }

                @Override
                public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                    return () -> {};
                }
            };
        }

        private static SecurityEventObserver recordingObserver(List<AuthorizationDecisionEvent> target) {
            return new SecurityEventObserver() {
                @Override
                public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                    target.add(event);
                    return Future.succeededFuture();
                }
            };
        }

        // --- Use ---

        Connection connect(String bearerToken) {
            return new Connection(
                    wsClient.connect(new WebSocketConnectOptions()
                            .setHost("127.0.0.1")
                            .setPort(server.actualPort())
                            .setURI("/ws/guarded")
                            .addHeader("Authorization", "Bearer " + bearerToken)),
                    this);
        }

        void register(Client client) {
            clients.add(client);
        }

        void resetObservations() {
            content.reset();
            documents.reset();
            endpoint.reset();
            authorizer.reset();
            upgradeEvents.clear();
            serviceEvents.clear();
        }

        int totalEffects() {
            return content.effects() + documents.effects();
        }

        void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(condition.getAsBoolean(), message);
        }

        static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        // --- Cleanup ---

        @Override
        public void close() throws Exception {
            release(server, wsClient, consumers, resilience, clients);
        }

        /** Closes every socket, the server, every consumer, then the runtime, reporting the first failure. */
        private static void release(
                HttpServer server,
                WebSocketClient wsClient,
                List<MessageConsumer<?>> consumers,
                Resilience resilience,
                List<Client> clients)
                throws Exception {
            Exception failure = null;
            List<Future<?>> closing = new ArrayList<>();
            for (Client client : clients) {
                try {
                    client.close();
                } catch (Exception e) {
                    failure = remember(failure, e);
                }
            }
            closing.add(wsClient.close());
            if (server != null) {
                closing.add(server.close());
            }
            for (MessageConsumer<?> consumer : consumers) {
                closing.add(consumer.unregister());
            }
            closing.add(resilience.close());
            for (Future<?> pending : closing) {
                try {
                    await(pending);
                } catch (Exception e) {
                    failure = remember(failure, e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static Exception remember(Exception first, Exception next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }
    }

    // --- Test doubles ---

    /** An {@link Authorizer} that counts calls and can be told to fail. */
    private static final class ScriptedAuthorizer implements Authorizer {

        /** How the authorizer answers. */
        enum Mode {
            /** Permit the callers holding the executor role. */
            DELEGATE,
            /** Throw instead of answering. */
            THROW,
            /** Return a failed future. */
            FAILED_FUTURE
        }

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DELEGATE);

        void mode(Mode next) {
            mode.set(next);
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            calls.incrementAndGet();
            return switch (mode.get()) {
                case THROW -> throw new IllegalStateException("evaluator failure");
                case FAILED_FUTURE -> Future.failedFuture("evaluator failed");
                case DELEGATE ->
                    Future.succeededFuture(
                            request.securityContext()
                                            .authorization()
                                            .valuesOf(AuthorityKind.ROLE)
                                            .contains("executor")
                                    ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                                    : AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
            };
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            return authorize(
                    new AuthorizationRequest(ctx, action.value(), resource, InvocationOrigin.unspecified(), Map.of()));
        }
    }

    /** The same caller as {@code original} but holding only the given roles. */
    private static final class RoleRestrictedContext implements SecurityContext {
        private final SecurityContext original;
        private final AuthorizationClaims claims;

        RoleRestrictedContext(SecurityContext original, String... roles) {
            this.original = original;
            Set<AuthorityClaim> held = new java.util.HashSet<>();
            for (String role : roles) {
                held.add(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()));
            }
            this.claims = new AuthorizationClaims(held, Map.of());
        }

        @Override
        public SecurityIdentity identity() {
            return original.identity();
        }

        @Override
        public AuthenticationState authentication() {
            return original.authentication();
        }

        @Override
        public AuthorizationClaims authorization() {
            return claims;
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return original.origin();
        }
    }
}
