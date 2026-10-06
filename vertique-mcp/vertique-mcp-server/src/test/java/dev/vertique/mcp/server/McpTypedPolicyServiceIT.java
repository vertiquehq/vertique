// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ACTION_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ALICE;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ALICE_WITHOUT_OPS;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.AUDITOR_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.AUTHENTICATED_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.BOB;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.CAROL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.EDIT_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ERIN;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.GENERATED_AUDITOR_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.GENERATED_EDIT_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.GENERATED_OPS_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.IVY;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.LEGACY_EDIT_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.OPS_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.PUBLIC_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.RENAME_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.SAM;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.SCOPED_TOOL;
import static dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.UNKNOWN_TOOL;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ComposeResult;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Composition;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Deployment;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Outcome;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.PolicyToolInvoker;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Reply;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.ScriptedAuthorizer;
import dev.vertique.mcp.server.McpTypedPolicyServiceITFixture.Stage;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceHandler;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that one typed access policy governs an MCP tool at startup, in {@code tools/list}, in {@code
 * tools/call}, and again at a policy-protected service the tool calls.
 *
 * <p>The tools are mounted behind real port-0 loopback HTTP, real identity establishment, the real
 * {@link McpRequestDispatcher} and a real {@link McpPolicyEnforcer}. The services behind the tools are
 * reached through real service dispatch guarded by the real service authorization interceptor, so an
 * MCP permit never stands in for the service decision. Every scenario asserts explicit counters:
 * business effects, tool invocations, authorizer calls and decision events per boundary. A scenario
 * that asserts a denial or a hidden tool always pairs it with a permitted or shown tool of the same
 * policy form, so a server that hides or denies everything cannot satisfy it.
 *
 * <p>Startup scenarios compose the registry and the mount without binding a socket and report how far
 * the composition got: a policy that cannot be resolved stops at registry build, a missing capability
 * stops at the mount.
 *
 * <p>Arbitrary handler code and ordinary Java calls are not enforced; only MCP dispatch and service
 * dispatch are.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpTypedPolicyServiceIT {

    static final String REPORT_ACTION = "mcp.report.run";
    private static final String UNREGISTERED_ACTION = "mcp.report.purge";
    private static final String MCP_TOOL_RESOURCE = "mcp-tool";

    // --- Policies ---

    /** The caller must hold the {@code ops} role. */
    @RolesAllowed("ops")
    public interface OpsPolicy extends AccessPolicy {}

    /** The caller must hold the {@code auditor} role. */
    @RolesAllowed("auditor")
    public interface AuditorPolicy extends AccessPolicy {}

    /** The caller must hold the {@code reports:read} scope. */
    @Authorized(scopes = "reports:read")
    public interface ReportScopePolicy extends AccessPolicy {}

    /** Any authenticated caller. */
    @Authorized
    public interface AuthenticatedPolicy extends AccessPolicy {}

    /** Every caller, authenticated or not. */
    @PermitAll
    public interface PublicPolicy extends AccessPolicy {}

    /** The configured authorizer decides the registered report action. */
    @RequiresAction(REPORT_ACTION)
    public interface ReportActionPolicy extends AccessPolicy {}

    /** Names an action that is not a canonical action string. */
    @RequiresAction("not-an-action")
    public interface MalformedActionPolicy extends AccessPolicy {}

    /** Names a well-formed action that no contributor registers. */
    @RequiresAction(UNREGISTERED_ACTION)
    public interface UnregisteredActionPolicy extends AccessPolicy {}

    /** Declares public and role access at once, which is not a valid policy. */
    @PermitAll
    @RolesAllowed("ops")
    public interface ConflictingPolicy extends AccessPolicy {}

    /** Refuses every caller. */
    @DenyAll
    public interface ClosedPolicy extends AccessPolicy {}

    /** The caller must hold the {@code ops} role and the authorizer must decide the report action. */
    @RolesAllowed("ops")
    @RequiresAction(REPORT_ACTION)
    public interface OpsReportActionPolicy extends AccessPolicy {}

    /** Service access: the caller must hold the {@code editor} role. */
    @RolesAllowed("editor")
    public interface EditorPolicy extends AccessPolicy {}

    // --- Services ---

    /** Content operation guarded by the editor role. */
    @ServiceContract(namespace = "it", value = "mcp-content")
    public interface ContentService {

        /**
         * Edits content.
         *
         * @param payload the content
         * @return the stored content
         */
        @RequiresPolicy(EditorPolicy.class)
        @ServiceOperation("edit")
        Future<String> edit(String payload);
    }

    /** Document operation guarded by the editor role; the handler also checks ownership. */
    @ServiceContract(namespace = "it", value = "mcp-documents")
    public interface DocumentService {

        /**
         * Renames a document owned by the caller.
         *
         * @param documentId the document
         * @return the renamed document
         */
        @RequiresPolicy(EditorPolicy.class)
        @ServiceOperation("rename")
        Future<String> rename(String documentId);
    }

    /** Direct implementation that counts every business effect. */
    public static final class CountingContentService implements ContentService {
        private final AtomicInteger edits = new AtomicInteger();

        @Override
        public Future<String> edit(String payload) {
            edits.incrementAndGet();
            return Future.succeededFuture("edit:" + payload);
        }

        int effects() {
            return edits.get();
        }

        void reset() {
            edits.set(0);
        }
    }

    /**
     * Handler-pattern implementation: the capability policy admits any editor, then the handler refuses
     * documents the caller does not own, before any effect.
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

    // --- Lifecycle ---

    private final Vertx vertx = Vertx.vertx();
    private Deployment deployment;

    /**
     * Closes the client, the server and the services, then the owned {@link Vertx}, so no request is
     * in flight when the event loops stop.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        Exception failure = null;
        try {
            if (deployment != null) {
                deployment.close();
            }
        } catch (Exception closeFailure) {
            failure = closeFailure;
        } finally {
            deployment = null;
        }
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(result -> {
            if (result.failed()) {
                closed.completeExceptionally(result.cause());
            } else {
                closed.complete(null);
            }
        });
        closed.get(10, TimeUnit.SECONDS);
        if (failure != null) {
            throw failure;
        }
    }

    private Deployment startManual() throws Exception {
        deployment = Deployment.startManual(vertx);
        return deployment;
    }

    // --- Discovery, invocation and the service gate ---

    @Test
    @DisplayName("shouldEnforceDiscoveryInvocationAndInnerServicePolicy")
    void shouldEnforceDiscoveryInvocationAndInnerServicePolicy() throws Exception {
        Deployment app = startManual();

        permittedCallerReachesTheServiceThroughBothGates(app);
        listShowsThePermittedToolAndHidesTheDeniedOne(app);
        callRechecksThePolicyWhenClaimsChangeAfterListing(app);
        innerServiceDeniesAfterTheOuterPermit(app);
    }

    private static void permittedCallerReachesTheServiceThroughBothGates(Deployment app) throws Exception {
        // Given a caller holding both the tool's role and the service's role
        app.resetObservations();

        // When the caller invokes the typed tool that calls the protected service
        Reply reply = app.call(BOB, EDIT_TOOL);

        // Then each boundary permits once and the business effect happens exactly once
        String scenario = "permitted at both gates";
        assertThat(reply.outcome()).as(scenario + ": outcome").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.services().effects()).as(scenario + ": effect").isEqualTo(1);
        assertThat(app.tool(EDIT_TOOL).invocations())
                .as(scenario + ": invocation")
                .isEqualTo(1);
        assertOneMcpDecision(app, EDIT_TOOL, true, scenario);
        assertThat(app.serviceEvents()).as(scenario + ": service decisions").hasSize(1);
        assertPermitted(app.serviceEvents().get(0), scenario + " (service)");
    }

    private static void listShowsThePermittedToolAndHidesTheDeniedOne(Deployment app) throws Exception {
        // Given a caller holding the ops role but not the auditor role
        app.resetObservations();

        // When the caller lists the tools
        List<String> names = app.list(ALICE).toolNames();

        // Then the tool guarded by the held role is listed and the tool guarded by the other role is not
        String scenario = "listing";
        assertThat(names).as(scenario + ": shown").contains(OPS_TOOL);
        assertThat(names).as(scenario + ": hidden").doesNotContain(AUDITOR_TOOL);
        assertThat(app.mcpEventsFor(OPS_TOOL))
                .as(scenario + ": one decision for the shown tool")
                .hasSize(1);
        assertThat(app.mcpEventsFor(AUDITOR_TOOL))
                .as(scenario + ": one decision for the hidden tool")
                .hasSize(1);
    }

    private static void callRechecksThePolicyWhenClaimsChangeAfterListing(Deployment app) throws Exception {
        // Given a caller who was shown the ops tool in a listing
        assertThat(app.list(ALICE).toolNames())
                .as("changed claims: listed before")
                .contains(OPS_TOOL);
        app.resetObservations();

        // When the same subject calls it after the ops role was withdrawn
        Reply reply = app.call(ALICE_WITHOUT_OPS, OPS_TOOL);

        // Then the call is refused on the claims it carries now, without invoking the tool
        String scenario = "changed claims";
        assertThat(reply.outcome()).as(scenario + ": outcome").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.tool(OPS_TOOL).invocations())
                .as(scenario + ": invocations")
                .isZero();
        assertOneMcpDecision(app, OPS_TOOL, false, scenario);
    }

    private static void innerServiceDeniesAfterTheOuterPermit(Deployment app) throws Exception {
        // Given a caller holding the tool's role but not the role the service requires
        app.resetObservations();

        // When the caller invokes the typed tool that calls the protected service
        Reply reply = app.call(ALICE, EDIT_TOOL);

        // Then the tool is admitted, the service refuses it, and no business effect happens
        String scenario = "inner service denial";
        assertThat(reply.outcome()).as(scenario + ": outcome").isEqualTo(Outcome.FAILED_AFTER_ADMISSION);
        assertThat(app.tool(EDIT_TOOL).invocations())
                .as(scenario + ": tool reached")
                .isEqualTo(1);
        assertThat(app.services().effects()).as(scenario + ": effect").isZero();
        assertOneMcpDecision(app, EDIT_TOOL, true, scenario);
        assertThat(app.serviceEvents()).as(scenario + ": service decisions").hasSize(1);
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.serviceEvents().get(0), scenario + " (service)");
    }

    @Test
    @DisplayName("shouldDispatchLegacyToolThroughBothGates")
    void shouldDispatchLegacyToolThroughBothGates() throws Exception {
        // Given a legacy tool that calls the protected service, and callers with and without the editor role
        Deployment app = startManual();

        // When a caller with both roles invokes it
        Reply permitted = app.call(BOB, LEGACY_EDIT_TOOL);

        // Then the MCP gate and the service gate each permit once and the effect happens once
        assertThat(permitted.outcome()).as("legacy permitted").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.services().effects()).as("legacy permitted: effect").isEqualTo(1);
        assertOneMcpDecision(app, LEGACY_EDIT_TOOL, true, "legacy permitted");
        assertThat(app.serviceEvents())
                .as("legacy permitted: service decisions")
                .hasSize(1);
        assertPermitted(app.serviceEvents().get(0), "legacy permitted (service)");

        // When a caller without the editor role invokes it
        app.resetObservations();
        Reply refused = app.call(ALICE, LEGACY_EDIT_TOOL);

        // Then the MCP gate permits and the service gate refuses without effect
        assertThat(refused.outcome()).as("legacy inner denial").isEqualTo(Outcome.FAILED_AFTER_ADMISSION);
        assertThat(app.services().effects()).as("legacy inner denial: effect").isZero();
        assertOneMcpDecision(app, LEGACY_EDIT_TOOL, true, "legacy inner denial");
        assertThat(app.serviceEvents())
                .as("legacy inner denial: service decisions")
                .hasSize(1);
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.serviceEvents().get(0), "legacy inner denial (service)");
    }

    // --- Discovery ---

    @Test
    @DisplayName("shouldHideDeniedToolsAndListPermittedOnes")
    void shouldHideDeniedToolsAndListPermittedOnes() throws Exception {
        // Given tools guarded by role, scope, authentication, action and public policies
        Deployment app = startManual();

        // When callers with different claims list the tools
        // Then each sees exactly the tools its claims satisfy
        assertThat(app.list(ALICE).toolNames())
                .as("ops caller")
                .containsExactlyInAnyOrder(
                        PUBLIC_TOOL, OPS_TOOL, AUTHENTICATED_TOOL, EDIT_TOOL, RENAME_TOOL, LEGACY_EDIT_TOOL);
        assertThat(app.list(IVY).toolNames())
                .as("auditor caller")
                .containsExactlyInAnyOrder(PUBLIC_TOOL, AUDITOR_TOOL, AUTHENTICATED_TOOL);
        assertThat(app.list(SAM).toolNames())
                .as("scoped caller")
                .containsExactlyInAnyOrder(PUBLIC_TOOL, SCOPED_TOOL, AUTHENTICATED_TOOL);
        assertThat(app.list(ERIN).toolNames())
                .as("executor caller")
                .containsExactlyInAnyOrder(
                        PUBLIC_TOOL,
                        OPS_TOOL,
                        AUTHENTICATED_TOOL,
                        EDIT_TOOL,
                        RENAME_TOOL,
                        LEGACY_EDIT_TOOL,
                        ACTION_TOOL);
        assertThat(app.list(null).toolNames()).as("anonymous caller").containsExactly(PUBLIC_TOOL);
    }

    @Test
    @DisplayName("shouldEmitOneDecisionPerRestrictiveCandidateWhenListing")
    void shouldEmitOneDecisionPerRestrictiveCandidateWhenListing() throws Exception {
        // Given a caller holding the ops role
        Deployment app = startManual();
        app.resetObservations();

        // When the caller lists the tools
        List<String> names = app.list(ALICE).toolNames();

        // Then every restrictive tool produced exactly one decision against its own resource, the public
        // tool produced none, and the permitted ones are the tools that were listed
        assertThat(names).as("a typed tool is listed").contains(OPS_TOOL);
        Map<String, Boolean> decided = decisionsByTool(app.mcpEvents());
        assertThat(app.mcpEvents()).as("one decision per restrictive tool").hasSize(8);
        assertThat(decided)
                .as("decisions by tool")
                .containsOnlyKeys(
                        OPS_TOOL,
                        AUDITOR_TOOL,
                        SCOPED_TOOL,
                        AUTHENTICATED_TOOL,
                        ACTION_TOOL,
                        EDIT_TOOL,
                        RENAME_TOOL,
                        LEGACY_EDIT_TOOL);
        assertThat(decided.entrySet().stream()
                        .filter(Map.Entry::getValue)
                        .map(Map.Entry::getKey)
                        .toList())
                .as("permitted decisions match the listing")
                .containsExactlyInAnyOrderElementsOf(
                        names.stream().filter(name -> !PUBLIC_TOOL.equals(name)).toList());
        app.mcpEvents().forEach(event -> assertMcpBoundary(event, "listing"));
    }

    // --- Invocation ---

    @Test
    @DisplayName("shouldRecheckPolicyAtCallWhenClaimsChangeAfterList")
    void shouldRecheckPolicyAtCallWhenClaimsChangeAfterList() throws Exception {
        // Given a caller shown the ops tool in a listing
        Deployment app = startManual();
        assertThat(app.list(ALICE).toolNames())
                .as("listed for the original claims")
                .contains(OPS_TOOL);
        app.resetObservations();

        // When the call carries the original claims
        Reply original = app.call(ALICE, OPS_TOOL);

        // Then it runs
        assertThat(original.outcome()).as("original claims").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.authorizer().calls())
                .as("original claims: a role policy needs no authorizer")
                .isZero();
        assertThat(app.tool(OPS_TOOL).invocations())
                .as("original claims: invocations")
                .isEqualTo(1);
        assertOneMcpDecision(app, OPS_TOOL, true, "original claims");

        // When the same subject calls it after losing the role
        app.resetObservations();
        Reply changed = app.call(ALICE_WITHOUT_OPS, OPS_TOOL);

        // Then it is refused without invoking the tool, and the listing no longer shows it either
        assertThat(changed.outcome()).as("changed claims").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.tool(OPS_TOOL).invocations())
                .as("changed claims: invocations")
                .isZero();
        assertOneMcpDecision(app, OPS_TOOL, false, "changed claims");
        assertThat(app.list(ALICE_WITHOUT_OPS).toolNames())
                .as("changed claims: listing")
                .doesNotContain(OPS_TOOL);
    }

    @Test
    @DisplayName("shouldRefuseRestrictedToolWithoutCallerAndRunPublicTool")
    void shouldRefuseRestrictedToolWithoutCallerAndRunPublicTool() throws Exception {
        // Given a request that carries no credential
        Deployment app = startManual();
        app.resetObservations();

        // When it calls the public typed tool
        Reply publicCall = app.call(null, PUBLIC_TOOL);

        // Then the public tool runs and, being public, emits no decision
        assertThat(publicCall.outcome()).as("public tool").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.tool(PUBLIC_TOOL).invocations())
                .as("public tool: invocations")
                .isEqualTo(1);
        assertThat(app.mcpEventsFor(PUBLIC_TOOL)).as("public tool: decisions").isEmpty();

        // When the same request calls the role-restricted typed tool
        app.resetObservations();
        Reply restrictedCall = app.call(null, OPS_TOOL);

        // Then it is refused without invoking the tool, with one restrictive decision
        assertThat(restrictedCall.outcome()).as("restricted tool").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.tool(OPS_TOOL).invocations())
                .as("restricted tool: invocations")
                .isZero();
        assertOneMcpDecision(app, OPS_TOOL, false, "restricted tool without caller");
    }

    @Test
    @DisplayName("shouldAnswerUnknownAndDeniedToolsWithIdenticalWireBytes")
    void shouldAnswerUnknownAndDeniedToolsWithIdenticalWireBytes() throws Exception {
        // Given a caller who may call the ops tool but not the auditor tool
        Deployment app = startManual();
        assertThat(app.call(ALICE, OPS_TOOL).outcome())
                .as("control: permitted tool")
                .isEqualTo(Outcome.SUCCEEDED);
        app.resetObservations();

        // When the caller calls the denied typed tool and a tool that does not exist
        Reply denied = app.call(ALICE, AUDITOR_TOOL);
        List<AuthorizationDecisionEvent> deniedEvents = app.mcpEvents();
        app.resetObservations();
        Reply unknown = app.call(ALICE, UNKNOWN_TOOL);
        List<AuthorizationDecisionEvent> unknownEvents = app.mcpEvents();

        // Then both answer with the same bytes and headers, each after exactly one decision, and nothing ran
        assertThat(denied.outcome()).as("denied tool").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(unknown.outcome()).as("unknown tool").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(denied.body().getBytes())
                .as("wire bytes")
                .isEqualTo(unknown.body().getBytes());
        assertThat(denied.headers()).as("headers").isEqualTo(unknown.headers());
        assertThat(deniedEvents).as("decisions for the denied tool").hasSize(1);
        assertThat(unknownEvents).as("decisions for the unknown tool").hasSize(1);
        assertThat(app.tool(AUDITOR_TOOL).invocations())
                .as("denied tool invocations")
                .isZero();
    }

    @Test
    @DisplayName("shouldResolveAccessPolicyHookOnceAtRegistration")
    void shouldResolveAccessPolicyHookOnceAtRegistration() throws Exception {
        // Given a server that has served a mix of listings and calls
        Deployment app = startManual();
        assertThat(app.list(ALICE).toolNames()).as("listing").contains(OPS_TOOL);
        assertThat(app.call(ALICE, OPS_TOOL).outcome()).as("permitted call").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.call(ALICE, OPS_TOOL).outcome()).as("repeated call").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.call(ALICE, AUDITOR_TOOL).outcome())
                .as("denied call")
                .isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.list(ALICE_WITHOUT_OPS).toolNames()).as("second listing").doesNotContain(OPS_TOOL);

        // When the hook reads are counted
        // Then every invoker's hook was read exactly once, at registration, and never per request
        for (String name : List.of(
                PUBLIC_TOOL,
                OPS_TOOL,
                AUDITOR_TOOL,
                SCOPED_TOOL,
                AUTHENTICATED_TOOL,
                ACTION_TOOL,
                EDIT_TOOL,
                RENAME_TOOL,
                LEGACY_EDIT_TOOL)) {
            assertThat(app.tool(name).hookCalls()).as("hook reads of " + name).isEqualTo(1);
        }
    }

    // --- Inner service gate ---

    @Test
    @DisplayName("shouldDenyInnerServiceAfterOuterPermitWithOneEventPerBoundary")
    void shouldDenyInnerServiceAfterOuterPermitWithOneEventPerBoundary() throws Exception {
        // Given a caller holding the tool's role and the service's role, then one holding only the tool's role
        Deployment app = startManual();
        permittedCallerReachesTheServiceThroughBothGates(app);

        // When the caller without the service role invokes the same tool
        innerServiceDeniesAfterTheOuterPermit(app);

        // Then the events belong to their own boundaries
        AuthorizationDecisionEvent mcpEvent = app.mcpEventsFor(EDIT_TOOL).get(0);
        assertMcpBoundary(mcpEvent, "inner service denial");
        assertThat(app.serviceEvents().get(0).request().resource().type())
                .as("the service decision is raised for a service resource")
                .isEqualTo("service");
    }

    @Test
    @DisplayName("shouldRefuseApplicationResourceAfterCapabilityPermit")
    void shouldRefuseApplicationResourceAfterCapabilityPermit() throws Exception {
        // Given document doc-bob owned by bob, and a second editor, carol, who does not own it
        Deployment app = startManual();
        app.resetObservations();

        // When the owner renames the document
        Reply owner = app.call(BOB, RENAME_TOOL, new JsonObject().put("documentId", "doc-bob"));

        // Then it takes effect after both capability permits
        assertThat(owner.outcome()).as("owner").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.services().documents.effects()).as("owner: effect").isEqualTo(1);
        assertOneMcpDecision(app, RENAME_TOOL, true, "owner");
        assertThat(app.serviceEvents()).as("owner: service decisions").hasSize(1);
        assertPermitted(app.serviceEvents().get(0), "owner (service)");

        // When another editor renames the same document
        app.resetObservations();
        Reply other = app.call(CAROL, RENAME_TOOL, new JsonObject().put("documentId", "doc-bob"));

        // Then both capability gates permitted, and the handler refused the resource before any effect
        assertThat(other.outcome()).as("other editor").isEqualTo(Outcome.FAILED_AFTER_ADMISSION);
        assertThat(app.services().documents.effects())
                .as("other editor: effect")
                .isZero();
        assertOneMcpDecision(app, RENAME_TOOL, true, "other editor");
        assertThat(app.serviceEvents()).as("other editor: service decisions").hasSize(1);
        assertPermitted(app.serviceEvents().get(0), "other editor (service capability)");
    }

    // --- Action policies ---

    @Test
    @DisplayName("shouldCallAuthorizerExactlyOnceForAllowedAndDeniedActionCalls")
    void shouldCallAuthorizerExactlyOnceForAllowedAndDeniedActionCalls() throws Exception {
        // Given an action-only typed tool and an authorizer that permits the executor role
        Deployment app = startManual();
        app.resetObservations();

        // When a caller holding the executor role calls it
        Reply allowed = app.call(ERIN, ACTION_TOOL);

        // Then the authorizer is asked once about the declared action and the tool runs once
        String permitted = "executor";
        assertThat(allowed.outcome()).as(permitted + ": outcome").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.authorizer().calls())
                .as(permitted + ": authorizer calls")
                .isEqualTo(1);
        assertThat(app.authorizer().askedActions())
                .as(permitted + ": the action asked about")
                .containsExactly(McpTypedPolicyServiceIT.REPORT_ACTION);
        assertThat(app.tool(ACTION_TOOL).invocations())
                .as(permitted + ": invocations")
                .isEqualTo(1);
        assertOneMcpDecision(app, ACTION_TOOL, true, permitted);

        // When a caller without the executor role calls it
        app.resetObservations();
        Reply refused = app.call(ALICE, ACTION_TOOL);

        // Then the authorizer is asked once and the tool does not run
        String denied = "no executor";
        assertThat(refused.outcome()).as(denied + ": outcome").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.authorizer().calls()).as(denied + ": authorizer calls").isEqualTo(1);
        assertThat(app.tool(ACTION_TOOL).invocations())
                .as(denied + ": invocations")
                .isZero();
        assertOneMcpDecision(app, ACTION_TOOL, false, denied);
    }

    @Test
    @DisplayName("shouldDenyActionToolWhenTheEvaluatorThrowsOrFails")
    void shouldDenyActionToolWhenTheEvaluatorThrowsOrFails() throws Exception {
        // Given an action-only typed tool whose authorizer answers normally for the executor role
        Deployment app = startManual();
        app.resetObservations();
        assertThat(app.call(ERIN, ACTION_TOOL).outcome())
                .as("answering evaluator")
                .isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.tool(ACTION_TOOL).invocations())
                .as("answering evaluator: invocations")
                .isEqualTo(1);

        // When the evaluator throws, and when it returns a failed future
        for (ScriptedAuthorizer.Mode failure :
                List.of(ScriptedAuthorizer.Mode.THROW, ScriptedAuthorizer.Mode.FAILED_FUTURE)) {
            app.authorizer().mode(failure);
            app.resetObservations();
            Reply reply = app.call(ERIN, ACTION_TOOL);

            // Then the call is refused as a denial, the tool does not run, and one denial is emitted
            String label = "evaluator " + failure;
            assertThat(reply.outcome()).as(label + ": outcome").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
            assertThat(app.authorizer().calls())
                    .as(label + ": authorizer calls")
                    .isEqualTo(1);
            assertThat(app.tool(ACTION_TOOL).invocations())
                    .as(label + ": invocations")
                    .isZero();
            assertOneMcpDecision(app, ACTION_TOOL, false, label);
        }
    }

    // --- Generated invokers ---

    @Test
    @DisplayName("shouldEnforceGeneratedTypedToolsLikeManualInvokers")
    void shouldEnforceGeneratedTypedToolsLikeManualInvokers() throws Exception {
        // Given typed tools emitted by the real annotation processor, one of which calls the protected service
        deployment = Deployment.startGenerated(vertx);
        Deployment app = deployment;

        // When a caller holding the ops role lists the tools and calls them
        // Then the tools guarded by that role are listed and the auditor tool is hidden
        assertThat(app.list(ALICE).toolNames())
                .as("generated listing")
                .containsExactlyInAnyOrder(GENERATED_OPS_TOOL, GENERATED_EDIT_TOOL);
        assertThat(app.list(IVY).toolNames())
                .as("generated listing for the auditor")
                .containsExactly(GENERATED_AUDITOR_TOOL);
        assertThat(app.list(null).toolNames())
                .as("generated listing for no caller")
                .isEmpty();
        assertThat(app.call(ALICE, GENERATED_OPS_TOOL).outcome())
                .as("generated permitted call")
                .isEqualTo(Outcome.SUCCEEDED);

        // And the denied tool answers exactly as an unknown tool does
        Reply denied = app.call(ALICE, GENERATED_AUDITOR_TOOL);
        Reply unknown = app.call(ALICE, UNKNOWN_TOOL);
        assertThat(denied.outcome()).as("generated denied call").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(denied.body().getBytes())
                .as("generated denied wire bytes")
                .isEqualTo(unknown.body().getBytes());
        assertThat(denied.headers()).as("generated denied headers").isEqualTo(unknown.headers());

        // And the service gate rechecks the caller behind the generated tool
        app.resetObservations();
        Reply innerDenied = app.call(ALICE, GENERATED_EDIT_TOOL);
        assertThat(innerDenied.outcome()).as("generated inner denial").isEqualTo(Outcome.FAILED_AFTER_ADMISSION);
        assertThat(app.services().effects())
                .as("generated inner denial: effect")
                .isZero();
        assertOneMcpDecision(app, GENERATED_EDIT_TOOL, true, "generated inner denial");
        assertThat(app.serviceEvents())
                .as("generated inner denial: service decisions")
                .hasSize(1);
        assertDenied(AuthzReasonCodes.ROLE_MISSING, app.serviceEvents().get(0), "generated inner denial (service)");

        app.resetObservations();
        Reply innerPermitted = app.call(BOB, GENERATED_EDIT_TOOL);
        assertThat(innerPermitted.outcome())
                .as("generated permitted at both gates")
                .isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.services().effects())
                .as("generated permitted at both gates: effect")
                .isEqualTo(1);
    }

    // --- Startup compositions ---

    private static Set<McpToolInvoker> tools(PolicyToolInvoker... tools) {
        return Set.of(tools);
    }

    private static PolicyToolInvoker typed(String name, Class<? extends AccessPolicy> policy) {
        return PolicyToolInvoker.typed(name, policy, McpTypedPolicyServiceITFixture.neverInvoked(name));
    }

    private static Composition registeredReportAction(Composition composition) {
        return composition.withValidatorRegistry(
                Optional.of(McpTypedPolicyServiceITFixture.actionRegistryOf(REPORT_ACTION)));
    }

    @Test
    @DisplayName("shouldMountValidTypedComposition")
    void shouldMountValidTypedComposition() throws Exception {
        // Given a role tool and an action tool, an authentication scheme, an authorizer, and a registry
        // that contains the action
        Set<McpToolInvoker> invokers =
                tools(typed(OPS_TOOL, OpsPolicy.class), typed(ACTION_TOOL, ReportActionPolicy.class));
        Composition composition =
                registeredReportAction(Composition.enabledWithScheme().withAuthorizer());

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(vertx, invokers, composition);

        // Then it mounts
        assertThat(result.failure()).as("failure").isNull();
        assertThat(result.stage()).as("stage").isEqualTo(Stage.MOUNTED);
    }

    static Stream<Class<? extends AccessPolicy>> restrictedPolicies() {
        return Stream.of(OpsPolicy.class, ReportScopePolicy.class, AuthenticatedPolicy.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restrictedPolicies")
    @DisplayName("shouldRejectTypedRestrictedToolWithoutAuthenticationScheme")
    void shouldRejectTypedRestrictedToolWithoutAuthenticationScheme(Class<? extends AccessPolicy> policy)
            throws Exception {
        // Given a typed tool whose effective policy needs an authenticated caller
        Set<McpToolInvoker> invokers = tools(typed(OPS_TOOL, policy));

        // When the server is composed with no authentication scheme
        ComposeResult withoutScheme = McpTypedPolicyServiceITFixture.compose(
                vertx, invokers, Composition.enabledWithScheme().withoutScheme());

        // Then the mount is refused; with the scheme the same tool mounts
        assertThat(withoutScheme.stage()).as("without a scheme").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(withoutScheme.failure())
                .as("without a scheme: failure")
                .isNotNull()
                .hasMessageContaining("mcp.authenticationScheme");
        ComposeResult withScheme =
                McpTypedPolicyServiceITFixture.compose(vertx, invokers, Composition.enabledWithScheme());
        assertThat(withScheme.stage()).as("with a scheme").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldMountPublicTypedToolWithoutAuthenticationScheme")
    void shouldMountPublicTypedToolWithoutAuthenticationScheme() throws Exception {
        // Given a typed public tool
        Set<McpToolInvoker> invokers = tools(typed(PUBLIC_TOOL, PublicPolicy.class));

        // When the server is composed with no authentication scheme
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(
                vertx, invokers, Composition.enabledWithScheme().withoutScheme());

        // Then it mounts, because a public policy needs no caller
        assertThat(result.stage()).as("stage").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldRejectTypedActionToolWithoutAuthorizer")
    void shouldRejectTypedActionToolWithoutAuthorizer() throws Exception {
        // Given a typed action tool and a registry that contains its action
        Set<McpToolInvoker> invokers = tools(typed(ACTION_TOOL, ReportActionPolicy.class));

        // When the server is composed without an authorizer
        ComposeResult withoutAuthorizer = McpTypedPolicyServiceITFixture.compose(
                vertx, invokers, registeredReportAction(Composition.enabledWithScheme()));

        // Then the mount is refused and names the tool; with an authorizer the same tool mounts
        assertThat(withoutAuthorizer.stage()).as("without an authorizer").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(withoutAuthorizer.failure())
                .as("without an authorizer: failure")
                .hasMessageContaining(ACTION_TOOL)
                .hasMessageContaining("no Authorizer is installed")
                .hasMessageNotContaining("no ActionRegistry");
        ComposeResult withAuthorizer = McpTypedPolicyServiceITFixture.compose(
                vertx,
                invokers,
                registeredReportAction(Composition.enabledWithScheme().withAuthorizer()));
        assertThat(withAuthorizer.stage()).as("with an authorizer").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldRejectTypedActionToolWithoutActionRegistry")
    void shouldRejectTypedActionToolWithoutActionRegistry() throws Exception {
        // Given a typed action tool, an installed authorizer and no action registry
        Set<McpToolInvoker> invokers = tools(typed(ACTION_TOOL, ReportActionPolicy.class));

        // When the server is composed with the validator that was given no registry
        ComposeResult withoutRegistry = McpTypedPolicyServiceITFixture.compose(
                vertx, invokers, Composition.enabledWithScheme().withAuthorizer());

        // Then the mount is refused and names the tool; with the registry the same tool mounts
        assertThat(withoutRegistry.stage()).as("without a registry").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(withoutRegistry.failure())
                .as("without a registry: failure")
                .hasMessageContaining(ACTION_TOOL)
                .hasMessageContaining("no ActionRegistry is installed")
                .hasMessageNotContaining("no Authorizer");
        ComposeResult withRegistry = McpTypedPolicyServiceITFixture.compose(
                vertx,
                invokers,
                registeredReportAction(Composition.enabledWithScheme().withAuthorizer()));
        assertThat(withRegistry.stage()).as("with a registry").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldRejectTypedActionToolWithUnregisteredAction")
    void shouldRejectTypedActionToolWithUnregisteredAction() throws Exception {
        // Given a typed action tool whose well-formed action no contributor registers
        Set<McpToolInvoker> invokers = tools(typed(ACTION_TOOL, UnregisteredActionPolicy.class));

        // When the server is composed with a registry that holds only another action
        ComposeResult unregistered = McpTypedPolicyServiceITFixture.compose(
                vertx,
                invokers,
                registeredReportAction(Composition.enabledWithScheme().withAuthorizer()));

        // Then the mount is refused and names the tool
        assertThat(unregistered.stage()).as("unregistered action").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(unregistered.failure())
                .as("unregistered action: failure")
                .hasMessageContaining(ACTION_TOOL)
                .hasMessageContaining(UNREGISTERED_ACTION)
                .hasMessageContaining("not registered in the ActionRegistry")
                .hasMessageNotContaining("no Authorizer")
                .hasMessageNotContaining("no ActionRegistry");

        // And a registry that holds the action mounts the same tool
        ComposeResult registered = McpTypedPolicyServiceITFixture.compose(
                vertx,
                invokers,
                Composition.enabledWithScheme()
                        .withAuthorizer()
                        .withValidatorRegistry(
                                Optional.of(McpTypedPolicyServiceITFixture.actionRegistryOf(UNREGISTERED_ACTION))));
        assertThat(registered.stage()).as("registered action").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldRejectMalformedActionAtRegistryBuild")
    void shouldRejectMalformedActionAtRegistryBuild() throws Exception {
        // Given a typed tool whose policy names a malformed action
        Set<McpToolInvoker> malformed = tools(typed(ACTION_TOOL, MalformedActionPolicy.class));

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(
                vertx, malformed, Composition.enabledWithScheme().withAuthorizer());

        // Then the registry itself refuses it, naming the tool and the policy
        assertThat(result.stage()).as("malformed action").isEqualTo(Stage.REGISTRY_REJECTED);
        assertThat(result.failure())
                .as("malformed action: failure")
                .hasMessageContaining(ACTION_TOOL)
                .hasMessageContaining(MalformedActionPolicy.class.getSimpleName());
    }

    @Test
    @DisplayName("shouldRejectPolicyThatFailsValidationAtRegistryBuild")
    void shouldRejectPolicyThatFailsValidationAtRegistryBuild() throws Exception {
        // Given a typed tool whose policy declares public and role access at once
        Set<McpToolInvoker> conflicting = tools(typed(OPS_TOOL, ConflictingPolicy.class));

        // When the server is composed
        ComposeResult result =
                McpTypedPolicyServiceITFixture.compose(vertx, conflicting, Composition.enabledWithScheme());

        // Then the registry refuses it, naming the tool and the policy
        assertThat(result.stage()).as("conflicting policy").isEqualTo(Stage.REGISTRY_REJECTED);
        assertThat(result.failure())
                .as("conflicting policy: failure")
                .hasMessageContaining(OPS_TOOL)
                .hasMessageContaining(ConflictingPolicy.class.getSimpleName());
    }

    @Test
    @DisplayName("shouldRejectNullHookValueAtRegistryBuild")
    void shouldRejectNullHookValueAtRegistryBuild() throws Exception {
        // Given an invoker whose policy hook returns null instead of an Optional
        Set<McpToolInvoker> nullHook = tools(PolicyToolInvoker.withNullHook(OPS_TOOL));

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(vertx, nullHook, Composition.enabledWithScheme());

        // Then the registry refuses it, naming the tool
        assertThat(result.stage()).as("null hook value").isEqualTo(Stage.REGISTRY_REJECTED);
        assertThat(result.failure()).as("null hook value: failure").hasMessageContaining(OPS_TOOL);
    }

    static Stream<McpToolAccess> descriptorAccessThatIsNotDenyAll() {
        return Stream.of(
                McpTypedPolicyServiceITFixture.permitAllAccess(), McpTypedPolicyServiceITFixture.rolesAccess("ops"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptorAccessThatIsNotDenyAll")
    @DisplayName("shouldRejectManualInvokerWithTypedHookAndNonDenyAllDescriptor")
    void shouldRejectManualInvokerWithTypedHookAndNonDenyAllDescriptor(McpToolAccess legacyAccess) throws Exception {
        // Given a manual invoker that declares a typed policy but publishes other legacy access, which an
        // older runtime would honor instead of the policy
        Set<McpToolInvoker> inconsistent =
                tools(PolicyToolInvoker.typedWithLegacyAccess(OPS_TOOL, OpsPolicy.class, legacyAccess));

        // When the server is composed
        ComposeResult result =
                McpTypedPolicyServiceITFixture.compose(vertx, inconsistent, Composition.enabledWithScheme());

        // Then the registry refuses it, naming the tool, while the same policy with the restrictive
        // placeholder mounts
        assertThat(result.stage()).as("inconsistent descriptor").isEqualTo(Stage.REGISTRY_REJECTED);
        assertThat(result.failure()).as("inconsistent descriptor: failure").hasMessageContaining(OPS_TOOL);
        ComposeResult consistent = McpTypedPolicyServiceITFixture.compose(
                vertx, tools(typed(OPS_TOOL, OpsPolicy.class)), Composition.enabledWithScheme());
        assertThat(consistent.stage()).as("consistent descriptor").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldMountDisabledServerWithTypedPoliciesAndNoAuthenticationOrActionEngine")
    void shouldMountDisabledServerWithTypedPoliciesAndNoAuthenticationOrActionEngine() throws Exception {
        // Given typed role and action tools, and a disabled server with no scheme, no authorizer and no registry
        Set<McpToolInvoker> invokers =
                tools(typed(OPS_TOOL, OpsPolicy.class), typed(ACTION_TOOL, ReportActionPolicy.class));

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(
                vertx, invokers, Composition.enabledWithScheme().withoutScheme().disabled());

        // Then it mounts, because a disabled server is inert
        assertThat(result.failure()).as("failure").isNull();
        assertThat(result.stage()).as("stage").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldMountRoleOnlyTypedToolWithoutAuthorizerOrActionRegistry")
    void shouldMountRoleOnlyTypedToolWithoutAuthorizerOrActionRegistry() throws Exception {
        // Given a typed role-only tool, an authentication scheme, and neither an authorizer nor a registry
        Set<McpToolInvoker> invokers = tools(typed(OPS_TOOL, OpsPolicy.class));

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(vertx, invokers, Composition.enabledWithScheme());

        // Then it mounts
        assertThat(result.failure()).as("failure").isNull();
        assertThat(result.stage()).as("stage").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldTreatNoArgValidatorAsAbsentActionRegistry")
    void shouldTreatNoArgValidatorAsAbsentActionRegistry() throws Exception {
        // Given a typed action tool and an installed authorizer
        Set<McpToolInvoker> invokers = tools(typed(ACTION_TOOL, ReportActionPolicy.class));
        Composition noArgument = Composition.enabledWithScheme().withAuthorizer();
        Composition explicitlyEmpty = noArgument.withValidatorRegistry(Optional.empty());

        // When the server is composed with the no-argument validator and with an explicitly empty registry
        ComposeResult byNoArgument = McpTypedPolicyServiceITFixture.compose(vertx, invokers, noArgument);
        ComposeResult byEmptyInput = McpTypedPolicyServiceITFixture.compose(vertx, invokers, explicitlyEmpty);

        // Then both refuse the mount the same way
        assertThat(byEmptyInput.stage()).as("explicitly empty registry").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(byNoArgument.stage()).as("no-argument validator").isEqualTo(byEmptyInput.stage());
        assertThat(byNoArgument.failure()).as("no-argument failure").hasMessageContaining(ACTION_TOOL);
        assertThat(byEmptyInput.failure()).as("explicitly empty failure").hasMessageContaining(ACTION_TOOL);
    }

    @Test
    @DisplayName("shouldRejectTypedActionOnlyToolWithoutAuthenticationScheme")
    void shouldRejectTypedActionOnlyToolWithoutAuthenticationScheme() throws Exception {
        // Given a typed action-only tool, with an authorizer and a registry that contains its action
        Set<McpToolInvoker> invokers = tools(typed(ACTION_TOOL, ReportActionPolicy.class));
        Composition equipped =
                registeredReportAction(Composition.enabledWithScheme().withAuthorizer());

        // When the server is composed with no authentication scheme
        ComposeResult withoutScheme = McpTypedPolicyServiceITFixture.compose(vertx, invokers, equipped.withoutScheme());

        // Then the mount is refused for the missing scheme, while the same tool mounts with the scheme
        assertThat(withoutScheme.stage()).as("without a scheme").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(withoutScheme.failure())
                .as("without a scheme: failure")
                .hasMessageContaining("mcp.authenticationScheme");
        ComposeResult withScheme = McpTypedPolicyServiceITFixture.compose(vertx, invokers, equipped);
        assertThat(withScheme.stage()).as("with a scheme").isEqualTo(Stage.MOUNTED);
    }

    static Stream<Throwable> hookFailures() {
        return Stream.of(
                new IllegalStateException("policy hook failed"), new NoClassDefFoundError("dev/example/MissingPolicy"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hookFailures")
    @DisplayName("shouldRejectHookThatThrowsAtRegistryBuild")
    void shouldRejectHookThatThrowsAtRegistryBuild(Throwable failure) throws Exception {
        // Given an invoker whose policy hook throws, for an ordinary failure and for a linkage error
        Set<McpToolInvoker> throwing = tools(PolicyToolInvoker.withThrowingHook(OPS_TOOL, failure));

        // When the server is composed
        ComposeResult result = McpTypedPolicyServiceITFixture.compose(vertx, throwing, Composition.enabledWithScheme());

        // Then the registry refuses it as a configuration error naming the tool and carrying the cause
        assertThat(result.stage()).as("throwing hook").isEqualTo(Stage.REGISTRY_REJECTED);
        assertThat(result.failure())
                .as("throwing hook: failure")
                .hasMessageContaining(OPS_TOOL)
                .hasCause(failure);

        // And a tool whose hook answers mounts
        ComposeResult healthy = McpTypedPolicyServiceITFixture.compose(
                vertx, tools(typed(OPS_TOOL, OpsPolicy.class)), Composition.enabledWithScheme());
        assertThat(healthy.stage()).as("healthy hook").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldApplyTheActionRegistryHandedToTheDaggerProvider")
    void shouldApplyTheActionRegistryHandedToTheDaggerProvider() throws Exception {
        // Given validators the Dagger provider builds, with and without an action registry
        Supplier<McpServerConfigValidator> withRegistry = () -> McpServerModule.configValidator(
                Optional.of(McpTypedPolicyServiceITFixture.actionRegistryOf(REPORT_ACTION)));
        Supplier<McpServerConfigValidator> withoutRegistry = () -> McpServerModule.configValidator(Optional.empty());
        Composition provided = new Composition(true, McpTypedPolicyServiceITFixture.SCHEME, true, withRegistry);
        Composition providedEmpty = new Composition(true, McpTypedPolicyServiceITFixture.SCHEME, true, withoutRegistry);
        Set<McpToolInvoker> registered = tools(typed(ACTION_TOOL, ReportActionPolicy.class));
        Set<McpToolInvoker> unregistered = tools(typed(ACTION_TOOL, UnregisteredActionPolicy.class));

        // When typed action tools are composed with the registry-backed validator
        ComposeResult accepted = McpTypedPolicyServiceITFixture.compose(vertx, registered, provided);
        ComposeResult rejected = McpTypedPolicyServiceITFixture.compose(vertx, unregistered, provided);

        // Then a registered action mounts and an unregistered one is refused
        assertThat(accepted.stage()).as("registered action").isEqualTo(Stage.MOUNTED);
        assertThat(rejected.stage()).as("unregistered action").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(rejected.failure())
                .as("unregistered action: failure")
                .hasMessageContaining(ACTION_TOOL)
                .hasMessageContaining("not registered in the ActionRegistry");

        // And with no registry the provider behaves like the no-argument validator
        ComposeResult emptyProvided = McpTypedPolicyServiceITFixture.compose(vertx, registered, providedEmpty);
        ComposeResult noArgument = McpTypedPolicyServiceITFixture.compose(
                vertx, registered, Composition.enabledWithScheme().withAuthorizer());
        assertThat(emptyProvided.stage()).as("empty registry").isEqualTo(Stage.MOUNT_REJECTED);
        assertThat(noArgument.stage()).as("no-argument validator").isEqualTo(emptyProvided.stage());
        assertThat(emptyProvided.failure())
                .as("empty registry: failure")
                .hasMessageContaining("no ActionRegistry is installed");
        assertThat(noArgument.failure())
                .as("no-argument failure")
                .hasMessage(emptyProvided.failure().getMessage());
    }

    // --- Closed and combined policies ---

    private Deployment startWith(PolicyToolInvoker... tools) throws Exception {
        Map<String, PolicyToolInvoker> byName = new LinkedHashMap<>();
        for (PolicyToolInvoker tool : tools) {
            byName.put(tool.descriptor().name(), tool);
        }
        deployment = Deployment.start(vertx, services -> Set.copyOf(byName.values()), byName);
        return deployment;
    }

    @Test
    @DisplayName("shouldDecideCallsWithTheDescriptorThatTheRegistryPinned")
    void shouldDecideCallsWithTheDescriptorThatTheRegistryPinned() throws Exception {
        // Given a tool registered as restricted to the ops role, listed and callable for an ops caller
        String pinned = "legacy.pinned";
        Deployment app = startWith(PolicyToolInvoker.legacy(
                pinned,
                McpTypedPolicyServiceITFixture.rolesAccess("ops"),
                McpTypedPolicyServiceITFixture.answering("pinned-ok")));
        assertThat(app.list(ALICE).toolNames()).as("ops caller: listed").containsExactly(pinned);
        assertThat(app.call(ALICE, pinned).outcome()).as("ops caller: call").isEqualTo(Outcome.SUCCEEDED);

        // When the invoker starts publishing public access from a later descriptor() read
        app.tool(pinned).publishLaterAccess(McpTypedPolicyServiceITFixture.permitAllAccess());
        app.resetObservations();

        // Then listing and calling both keep deciding with the registered, restricted descriptor
        assertThat(app.list(null).toolNames()).as("anonymous caller: listing").isEmpty();
        app.resetObservations();
        Reply refused = app.call(null, pinned);
        assertThat(refused.outcome()).as("anonymous caller: call").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.tool(pinned).invocations())
                .as("anonymous caller: invocations")
                .isZero();
        assertOneMcpDecision(app, pinned, false, "anonymous caller");

        // And the caller the registered descriptor admits is still admitted
        app.resetObservations();
        assertThat(app.call(ALICE, pinned).outcome())
                .as("ops caller after the change")
                .isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("shouldHideAndRefuseTypedDenyAllToolForEveryCaller")
    void shouldHideAndRefuseTypedDenyAllToolForEveryCaller() throws Exception {
        // Given a typed deny-all tool beside a typed public tool of the same server
        String closed = "typed.closed";
        Deployment app = startWith(
                PolicyToolInvoker.typed(
                        closed, ClosedPolicy.class, McpTypedPolicyServiceITFixture.neverInvoked(closed)),
                PolicyToolInvoker.typed(
                        PUBLIC_TOOL, PublicPolicy.class, McpTypedPolicyServiceITFixture.answering("public-ok")));

        // When callers with every kind of claim list and call
        for (String caller : Arrays.asList(ALICE, ERIN, IVY, SAM, null)) {
            String label = caller == null ? "anonymous" : caller;

            // Then the public tool is listed, the closed tool never is
            assertThat(app.list(caller).toolNames()).as(label + ": listing").containsExactly(PUBLIC_TOOL);

            // And the closed tool is refused like an unknown tool, with one deny-all decision and no invocation
            app.resetObservations();
            Reply refused = app.call(caller, closed);
            assertThat(refused.outcome()).as(label + ": closed call").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
            assertThat(app.tool(closed).invocations())
                    .as(label + ": invocations")
                    .isZero();
            assertThat(app.authorizer().calls())
                    .as(label + ": authorizer calls")
                    .isZero();
            assertOneMcpDecision(app, closed, false, label + ": closed call");
            assertDenied(AuthzReasonCodes.DENY_ALL, app.mcpEventsFor(closed).get(0), label + ": closed call");
        }

        // And the public tool still answers, so a server that refuses everything cannot pass
        app.resetObservations();
        assertThat(app.call(null, PUBLIC_TOOL).outcome()).as("public control").isEqualTo(Outcome.SUCCEEDED);

        // And a deny-all tool needs no authentication scheme to mount
        ComposeResult noScheme = McpTypedPolicyServiceITFixture.compose(
                vertx,
                tools(typed(closed, ClosedPolicy.class), typed(PUBLIC_TOOL, PublicPolicy.class)),
                Composition.enabledWithScheme().withoutScheme());
        assertThat(noScheme.stage()).as("no scheme").isEqualTo(Stage.MOUNTED);
    }

    @Test
    @DisplayName("shouldConsultAuthorizerOnlyAfterTheRoleOfACombinedPolicyIsHeld")
    void shouldConsultAuthorizerOnlyAfterTheRoleOfACombinedPolicyIsHeld() throws Exception {
        // Given one tool that needs the ops role and the report action together
        String combined = "typed.opsAction";
        Deployment app = startWith(PolicyToolInvoker.typed(
                combined, OpsReportActionPolicy.class, McpTypedPolicyServiceITFixture.answering("combined-ok")));
        app.resetObservations();

        // When a caller without the ops role calls it
        Reply withoutRole = app.call(IVY, combined);

        // Then it is refused on the role alone: the authorizer is never asked and one denial is emitted
        assertThat(withoutRole.outcome()).as("no role").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.authorizer().calls()).as("no role: authorizer calls").isZero();
        assertThat(app.tool(combined).invocations()).as("no role: invocations").isZero();
        assertOneMcpDecision(app, combined, false, "no role");

        // When an ops caller whom the authorizer refuses calls it
        app.resetObservations();
        Reply refusedAction = app.call(ALICE, combined);

        // Then the authorizer is asked once about the declared action and the tool does not run
        assertThat(refusedAction.outcome()).as("role without action").isEqualTo(Outcome.REFUSED_AS_INVALID_PARAMS);
        assertThat(app.authorizer().calls())
                .as("role without action: authorizer calls")
                .isEqualTo(1);
        assertThat(app.authorizer().askedActions())
                .as("role without action: the action asked about")
                .containsExactly(REPORT_ACTION);
        assertThat(app.tool(combined).invocations())
                .as("role without action: invocations")
                .isZero();
        assertOneMcpDecision(app, combined, false, "role without action");

        // When an ops caller whom the authorizer permits calls it
        app.resetObservations();
        Reply permitted = app.call(ERIN, combined);

        // Then the authorizer is asked exactly once and the tool runs once
        assertThat(permitted.outcome()).as("role and action").isEqualTo(Outcome.SUCCEEDED);
        assertThat(app.authorizer().calls())
                .as("role and action: authorizer calls")
                .isEqualTo(1);
        assertThat(app.tool(combined).invocations())
                .as("role and action: invocations")
                .isEqualTo(1);
        assertOneMcpDecision(app, combined, true, "role and action");
    }

    // --- Assertions ---

    private static Map<String, Boolean> decisionsByTool(List<AuthorizationDecisionEvent> events) {
        return events.stream()
                .collect(java.util.stream.Collectors.toMap(
                        event -> event.request().resource().id(),
                        event -> event.decision().permitted()));
    }

    private static void assertOneMcpDecision(Deployment app, String tool, boolean permitted, String label) {
        assertThat(app.mcpEvents())
                .as(label + ": exactly one MCP decision overall")
                .hasSize(1);
        List<AuthorizationDecisionEvent> events = app.mcpEventsFor(tool);
        assertThat(events).as(label + ": exactly one MCP decision for " + tool).hasSize(1);
        assertMcpBoundary(events.get(0), label);
        assertThat(events.get(0).decision().permitted())
                .as(label + ": MCP decision permits")
                .isEqualTo(permitted);
    }

    private static void assertMcpBoundary(AuthorizationDecisionEvent event, String label) {
        assertThat(event.request().resource().type())
                .as(label + ": MCP decisions are raised for a tool resource")
                .isEqualTo(MCP_TOOL_RESOURCE);
        assertThat(event.request().origin().kind())
                .as(label + ": MCP decisions carry the MCP origin")
                .isEqualTo(DispatchBoundary.MCP);
    }

    private static void assertDenied(String reasonCode, AuthorizationDecisionEvent event, String label) {
        assertThat(event.decision().permitted())
                .as(label + ": the decision must be a denial")
                .isFalse();
        assertThat(event.decision().reasonCode()).as(label + ": denial reason").isEqualTo(reasonCode);
    }

    private static void assertPermitted(AuthorizationDecisionEvent event, String label) {
        assertThat(event.decision().permitted())
                .as(label + ": the decision must permit")
                .isTrue();
        assertThat(event.decision().reasonCode()).as(label + ": permit reason").isEqualTo(AuthzReasonCodes.PERMITTED);
    }
}
