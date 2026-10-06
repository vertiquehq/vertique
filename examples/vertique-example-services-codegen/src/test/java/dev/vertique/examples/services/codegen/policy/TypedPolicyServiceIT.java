// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Caller;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.Effect;
import dev.vertique.examples.services.codegen.policy.PolicyFixtures.RecordingAuthorizer.Mode;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves that typed access policies declared on service contracts protect real dispatch, whether
 * the generated client is called directly or a permitted REST route calls it.
 *
 * <p>The fixture application ({@link PolicyTestComponent}) boots with the registration modules the
 * annotation processors generate for the test compilation unit: one direct-implementation contract
 * and one handler contract, both reached only through their generated clients, behind three REST
 * routes with different outer policies. Business effects are counted inside the service bodies, so
 * a denial is proved by an effect count that stays at zero; every authorization decision event and
 * every action evaluation is recorded.
 *
 * <p>Callers: a REST caller is a JWT. A marked REST caller is a JWT whose {@code sid} claim makes
 * the identity resolver add a subject or a delegation context. A direct caller is a
 * {@link SecurityContext} bound on a duplicated Vert.x context before the generated client is
 * called, or no context at all.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class TypedPolicyServiceIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(
                    new PolicyTestComponentVertiqueComponentFactory())
            .withConfig(
                    new JsonObject().put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1")));

    private static final long CALL_TIMEOUT_SECONDS = 5;

    private static PolicyTestComponent component;
    private static WebClient client;
    private static JWTAuth jwt;

    /** The two service implementation patterns, each reached through its generated client. */
    private enum Contract {
        DIRECT("direct"),
        HANDLER("handler");

        private final String path;

        Contract(String path) {
            this.path = path;
        }

        Function<String, Future<String>> operation(String name) {
            Map<String, Function<String, Future<String>>> operations = this == DIRECT
                    ? PolicyFixtures.operations(component.policyService())
                    : PolicyFixtures.operations(component.policyHandlerService());
            return operations.get(name);
        }

        String result(String operation, String resourceId) {
            return path + ":" + operation + ":" + resourceId;
        }
    }

    /** The outcome of a call through a generated client. */
    private record Outcome(boolean succeeded, String value, Throwable failure) {}

    /** The outcome of an HTTP call. */
    private record Reply(int status, String body) {}

    @BeforeAll
    static void startClients() {
        component = app.component();
        client = WebClient.create(app.vertx(), new WebClientOptions().setFollowRedirects(false));
        jwt = JwtAuthFactory.fromSymmetricKey(app.vertx(), "HS256", PolicyFixtures.JWT_KEY);
    }

    @AfterAll
    static void closeClients() {
        try {
            resetState();
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    @DisplayName("typed service policies hold for generated clients and for the REST to service hop")
    void shouldEnforceGeneratedClientAndRestToServiceBoundaries() {
        List<Executable> checks = new ArrayList<>();
        for (Contract contract : Contract.values()) {
            checks.add(scenario(
                    "outer REST denial never reaches the service",
                    contract,
                    this::outerRestDenialNeverReachesTheService));
            checks.add(scenario(
                    "public outer route still meets the inner service denial",
                    contract,
                    this::publicOuterMeetsInnerDenial));
            checks.add(scenario(
                    "permit and unrestricted work run without authorization", contract, this::permitWorkRunsUnguarded));
            checks.add(scenario(
                    "permitted outer route then inner role denial", contract, this::outerPermitThenInnerRoleDenial));
            checks.add(scenario(
                    "permitted work keeps caller, resource and origin",
                    contract,
                    this::permittedWorkKeepsCallerResourceOrigin));
            checks.add(scenario(
                    "marked REST caller keeps its REST result and fails the service",
                    contract,
                    this::markedRestCaller));
            checks.add(scenario(
                    "action-only policy still reaches the authorizer",
                    contract,
                    this::actionOnlyPolicyReachesTheAuthorizer));
            checks.add(scenario(
                    "combined policy checks locally first, then the action once",
                    contract,
                    this::combinedPolicyActionOnce));
            checks.add(scenario(
                    "evaluator throw, null and failure deny without effect", contract, this::evaluatorFailuresDeny));
            checks.add(scenario(
                    "allowed capability then ownership denial", contract, this::allowedCapabilityThenOwnershipDenial));
            checks.add(
                    scenario("missing and anonymous direct callers", contract, this::missingAndAnonymousDirectCallers));
            checks.add(scenario("marked direct callers", contract, this::markedDirectCallers));
            checks.add(scenario("scope predicate over direct callers", contract, this::scopePredicate));
            checks.add(scenario("concurrent callers stay isolated", contract, this::concurrentCallersStayIsolated));
        }
        assertAll(checks);
    }

    // --- Scenarios ---

    private void outerRestDenialNeverReachesTheService(Contract contract) throws Exception {
        // Given an operator who lacks the admin role the outer route requires
        resetState();
        String operator = token("oscar", null, List.of(PolicyFixtures.OPERATOR_ROLE), null);

        // When the operator calls the admin route that forwards to the operator operation
        Reply reply = get("admin", contract, PolicyFixtures.OP_OPERATOR, PolicyFixtures.ELIGIBLE_RESOURCE, operator);

        // Then the route refuses, the service is never reached and no business effect occurs
        String at = "";
        assertEquals(403, reply.status(), at + "the outer route must refuse");
        assertNoEffects(at);
        assertSingleDecision(at + "REST", component.decisions().rest(), false, AuthzReasonCodes.ROLE_MISSING);
        assertEquals(0, component.decisions().service().size(), at + "no service event expected");
        assertEquals(0, component.authorizer().calls(), at + "no action evaluation expected");
    }

    private void publicOuterMeetsInnerDenial(Contract contract) throws Exception {
        List<String> restrictedOperations = List.of(
                PolicyFixtures.OP_AUTHENTICATED,
                PolicyFixtures.OP_OPERATOR,
                PolicyFixtures.OP_SCOPED,
                PolicyFixtures.OP_ACTION,
                PolicyFixtures.OP_OPERATOR_ACTION,
                PolicyFixtures.OP_OWNED);
        for (String operation : restrictedOperations) {
            // Given a public outer route and no caller
            resetState();
            String at = contract + "/" + operation + ": ";

            // When the route forwards to a restricted service operation
            Reply reply = get("public", contract, operation, PolicyFixtures.ELIGIBLE_RESOURCE, null);

            // Then the service denies for lack of a caller and no effect or action evaluation occurs
            assertDeniedReply(at, reply, contract, operation);
            assertNoEffects(at);
            assertEquals(0, component.decisions().rest().size(), at + "a public route emits no REST event");
            assertSingleDecision(
                    at + "service", component.decisions().service(), false, AuthzReasonCodes.AUTHENTICATION_REQUIRED);
            assertEquals(0, component.authorizer().calls(), at + "no action evaluation without a caller");
            assertNoRecoveredDenial(at);
        }

        // Given a public outer route and a policy that denies everyone
        resetState();
        String at = contract + "/deny: ";

        // When the route forwards to the deny-all operation
        Reply reply = get("public", contract, PolicyFixtures.OP_DENY, PolicyFixtures.ELIGIBLE_RESOURCE, null);

        // Then the service denies even without a caller
        assertDeniedReply(at, reply, contract, PolicyFixtures.OP_DENY);
        assertNoEffects(at);
        assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.DENY_ALL);
    }

    private void permitWorkRunsUnguarded(Contract contract) throws Exception {
        for (String operation : List.of(PolicyFixtures.OP_PERMIT, PolicyFixtures.OP_UNRESTRICTED)) {
            // Given a public outer route and no caller
            resetState();
            String at = contract + "/" + operation + ": ";

            // When the route forwards to an operation that permits everyone or declares nothing
            Reply reply = get("public", contract, operation, PolicyFixtures.ELIGIBLE_RESOURCE, null);

            // Then the work runs, with no authorization work and no event on either boundary
            assertEquals(200, reply.status(), at + "the work must run");
            assertTrue(
                    reply.body().contains(contract.result(operation, PolicyFixtures.ELIGIBLE_RESOURCE)),
                    at + "the work result is returned, got " + reply.body());
            assertEquals(1, component.effects().total(), at + "exactly one effect expected");
            assertEquals(
                    0,
                    component.decisions().all().size(),
                    at + "no event expected, got "
                            + describe(component.decisions().all()));
            assertEquals(0, component.authorizer().calls(), at + "no action evaluation expected");
        }
    }

    private void outerPermitThenInnerRoleDenial(Contract contract) throws Exception {
        for (String operation : List.of(PolicyFixtures.OP_OPERATOR, PolicyFixtures.OP_OWNED)) {
            // Given an authenticated caller without the operator role
            resetState();
            String at = contract + "/" + operation + ": ";
            String viewer = token("vera", null, List.of("viewer"), null);

            // When the permitting outer route forwards to the operator operation
            Reply reply = get("authenticated", contract, operation, PolicyFixtures.ELIGIBLE_RESOURCE, viewer);

            // Then each boundary decides once, the service denies the role and no effect occurs
            assertDeniedReply(at, reply, contract, operation);
            assertNoEffects(at);
            assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
            assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.ROLE_MISSING);
            assertNoRecoveredDenial(at);
        }

        // Given an executor who holds the action but not the operator role the local predicate requires
        resetState();
        String at = contract + "/operatorAction: ";
        String executor = token("erin", null, List.of(PolicyFixtures.EXECUTOR_ROLE), null);

        // When the outer route forwards to the operator-and-action operation
        Reply reply = get(
                "authenticated",
                contract,
                PolicyFixtures.OP_OPERATOR_ACTION,
                PolicyFixtures.ELIGIBLE_RESOURCE,
                executor);

        // Then the local predicate denies before the action is evaluated
        assertDeniedReply(at, reply, contract, PolicyFixtures.OP_OPERATOR_ACTION);
        assertNoEffects(at);
        assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.ROLE_MISSING);
        assertEquals(0, component.authorizer().calls(), at + "local checks come before the action");
    }

    private void permittedWorkKeepsCallerResourceOrigin(Contract contract) throws Exception {
        // Given an unmarked operator
        resetState();
        String at = "";
        String operator = token("olga", null, List.of(PolicyFixtures.OPERATOR_ROLE), null);

        // When the authenticated route forwards to the operator operation
        Reply reply =
                get("authenticated", contract, PolicyFixtures.OP_OPERATOR, PolicyFixtures.ELIGIBLE_RESOURCE, operator);

        // Then the work runs once as the caller, and both boundaries record the same caller
        assertEquals(200, reply.status(), at + "the work must run, got " + reply.status() + " " + reply.body());
        assertEquals(
                List.of(new Effect(contract.path, PolicyFixtures.OP_OPERATOR, "olga")),
                component.effects().all(),
                at + "one effect performed as the REST caller");
        assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
        assertSingleDecision(at + "service", component.decisions().service(), true, null);
        AuthorizationDecisionEvent restEvent = component.decisions().rest().get(0);
        AuthorizationDecisionEvent serviceEvent =
                component.decisions().service().get(0);
        assertEquals(
                "olga",
                serviceEvent.request().securityContext().identity().actor().id(),
                at + "the service event names the REST caller");
        assertEquals(
                restEvent.request().securityContext().identity(),
                serviceEvent.request().securityContext().identity(),
                at + "the same identity crosses the hop");
        assertEquals(restEvent.origin(), serviceEvent.origin(), at + "the request origin survives the hop");
        assertEquals(
                DispatchBoundary.REST,
                restEvent.request().origin().kind(),
                at + "the REST event carries the REST origin");
        assertEquals(
                DispatchBoundary.SERVICE_DISPATCH,
                serviceEvent.request().origin().kind(),
                at + "the service event carries the dispatch origin");
        assertEquals("service", serviceEvent.request().resource().type(), at + "the service event names the service");
        assertEquals(
                PolicyFixtures.OP_OPERATOR,
                serviceEvent.request().resource().attributes().get("operation"),
                at + "the service event names the operation");
        assertFalse(serviceEvent.request().action().isBlank(), at + "the descriptive label is not blank");
        assertEquals(0, component.authorizer().calls(), at + "the descriptive label is never authorized");
    }

    private void markedRestCaller(Contract contract) throws Exception {
        List<String> markers = List.of(PolicyFixtures.SID_SUBJECT, PolicyFixtures.SID_DELEGATION);
        List<String> operations =
                List.of(PolicyFixtures.OP_AUTHENTICATED, PolicyFixtures.OP_OPERATOR, PolicyFixtures.OP_OPERATOR_ACTION);
        for (String marker : markers) {
            for (String operation : operations) {
                // Given a marked REST caller that satisfies every claim the service requires
                resetState();
                String at = contract + "/" + marker + "/" + operation + ": ";
                String marked = token(
                        "mara", marker, List.of(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE), null);

                // When the permitting outer route forwards to the adopted service operation
                Reply reply = get("authenticated", contract, operation, PolicyFixtures.ELIGIBLE_RESOURCE, marked);

                // Then the REST route keeps its result and the service refuses the caller shape
                assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
                AuthorizationDecisionEvent restEvent =
                        component.decisions().rest().get(0);
                SecurityIdentity identity =
                        restEvent.request().securityContext().identity();
                assertTrue(
                        PolicyFixtures.SID_SUBJECT.equals(marker)
                                ? identity.subject().isPresent()
                                : identity.delegation().isPresent(),
                        at + "the fixture must produce a marked REST caller");
                assertDeniedReply(at, reply, contract, operation);
                assertNoEffects(at);
                assertSingleDecision(
                        at + "service",
                        component.decisions().service(),
                        false,
                        PolicyFixtures.UNSUPPORTED_POLICY_CALLER);
                assertEquals(0, component.authorizer().calls(), at + "the marked denial precedes the action");
                assertNoRecoveredDenial(at);
            }
        }
    }

    private void actionOnlyPolicyReachesTheAuthorizer(Contract contract) throws Exception {
        // Given an executor who holds the action
        resetState();
        String at = "";
        String executor = token("eve", null, List.of(PolicyFixtures.EXECUTOR_ROLE), null);

        // When the authenticated route forwards to the action-only operation
        Reply reply =
                get("authenticated", contract, PolicyFixtures.OP_ACTION, PolicyFixtures.ELIGIBLE_RESOURCE, executor);

        // Then the configured authorizer decides exactly once, with the declared action only
        assertEquals(200, reply.status(), at + "the work must run, got " + reply.status() + " " + reply.body());
        assertEquals(1, component.effects().total(), at + "exactly one effect expected");
        assertEquals(1, component.authorizer().calls(), at + "the action is evaluated exactly once");
        AuthorizationRequest request = component.authorizer().requests().get(0);
        assertEquals(PolicyFixtures.ACTION_VALUE, request.action(), at + "only the declared action is evaluated");
        assertEquals("service", request.resource().type(), at + "the service resource is evaluated");
        assertEquals(
                DispatchBoundary.SERVICE_DISPATCH, request.origin().kind(), at + "the dispatch origin is evaluated");
        assertEquals("eve", request.securityContext().identity().actor().id(), at + "the REST caller is evaluated");
        assertSingleDecision(at + "service", component.decisions().service(), true, null);

        // Given a marked caller who holds the action
        resetState();
        String marked = token("maya", PolicyFixtures.SID_SUBJECT, List.of(PolicyFixtures.EXECUTOR_ROLE), null);

        // When the same route is called
        reply = get("authenticated", contract, PolicyFixtures.OP_ACTION, PolicyFixtures.ELIGIBLE_RESOURCE, marked);

        // Then the action-only policy follows the existing action path and still reaches the authorizer
        assertEquals(1, component.authorizer().calls(), at + "a marked caller still reaches the authorizer");
        assertEquals(1, component.decisions().service().size(), at + "one service event expected");
        assertTrue(
                component.decisions().service().get(0).decision().reasonCode() != null
                        && !PolicyFixtures.UNSUPPORTED_POLICY_CALLER.equals(component
                                .decisions()
                                .service()
                                .get(0)
                                .decision()
                                .reasonCode()),
                at + "an action-only policy does not apply the local caller-shape check");

        // Given a caller without the action
        resetState();
        String viewer = token("vic", null, List.of("viewer"), null);

        // When the same route is called
        reply = get("authenticated", contract, PolicyFixtures.OP_ACTION, PolicyFixtures.ELIGIBLE_RESOURCE, viewer);

        // Then the authorizer denies once and no effect occurs
        assertDeniedReply(at, reply, contract, PolicyFixtures.OP_ACTION);
        assertNoEffects(at);
        assertEquals(1, component.authorizer().calls(), at + "the action is evaluated exactly once");
        assertSingleDecision(at + "service", component.decisions().service(), false, null);
    }

    private void combinedPolicyActionOnce(Contract contract) throws Exception {
        String at = "";
        Function<String, Future<String>> call = contract.operation(PolicyFixtures.OP_OPERATOR_ACTION);

        // Given a direct caller who holds the role and the action
        resetState();
        Outcome permitted = callAs(
                Caller.user("dora").withRoles(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE),
                call,
                PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the local check passes, the action is evaluated once and the work runs once
        assertTrue(permitted.succeeded(), at + "permitted work must succeed: " + permitted.failure());
        assertEquals(1, component.effects().total(), at + "exactly one effect expected");
        assertEquals(1, component.authorizer().calls(), at + "the action is evaluated exactly once");
        assertEquals(
                PolicyFixtures.ACTION_VALUE,
                component.authorizer().requests().get(0).action(),
                at + "only the declared action is evaluated");
        assertSingleDecision(at + "service", component.decisions().service(), true, null);

        // Given a direct caller who holds the role but not the action
        resetState();
        Outcome withoutAction = callAs(
                Caller.user("dan").withRoles(PolicyFixtures.OPERATOR_ROLE), call, PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the action decides, once, and denies
        assertFalse(withoutAction.succeeded(), at + "the missing action must deny");
        assertNoEffects(at);
        assertEquals(1, component.authorizer().calls(), at + "the action is evaluated exactly once");
        assertSingleDecision(at + "service", component.decisions().service(), false, null);

        // Given a direct caller who holds the action but not the role
        resetState();
        Outcome withoutRole = callAs(
                Caller.user("dina").withRoles(PolicyFixtures.EXECUTOR_ROLE), call, PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the local predicate denies and the action is never evaluated
        assertFalse(withoutRole.succeeded(), at + "the missing role must deny");
        assertNoEffects(at);
        assertEquals(0, component.authorizer().calls(), at + "local checks come before the action");
        assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.ROLE_MISSING);

        // Given a permitted REST caller whose route and service both hold the caller
        resetState();
        String full = token("fay", null, List.of(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE), null);
        Reply reply = get(
                "authenticated", contract, PolicyFixtures.OP_OPERATOR_ACTION, PolicyFixtures.ELIGIBLE_RESOURCE, full);

        // Then the action is evaluated once across the REST to service hop, never twice
        assertEquals(200, reply.status(), at + "the work must run, got " + reply.status() + " " + reply.body());
        assertEquals(1, component.authorizer().calls(), at + "no duplicate action evaluation across the hop");
        assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
        assertSingleDecision(at + "service", component.decisions().service(), true, null);
    }

    private void evaluatorFailuresDeny(Contract contract) throws Exception {
        for (Mode mode : List.of(Mode.THROW, Mode.NULL_FUTURE, Mode.FAILED_FUTURE, Mode.NULL_DECISION)) {
            for (String operation : List.of(PolicyFixtures.OP_ACTION, PolicyFixtures.OP_OPERATOR_ACTION)) {
                // Given an authorizer that misbehaves and a caller who passes every local check
                resetState();
                String at = contract + "/" + mode + "/" + operation + ": ";
                component.authorizer().answer(mode);

                // When the generated client dispatches the action-bearing operation
                Outcome outcome = callAs(
                        Caller.user("ewan").withRoles(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE),
                        contract.operation(operation),
                        PolicyFixtures.ELIGIBLE_RESOURCE);

                // Then the dispatch is denied once, without a business effect and without recovery
                assertFalse(outcome.succeeded(), at + "the evaluator failure must deny");
                assertNoEffects(at);
                assertEquals(1, component.authorizer().calls(), at + "the action is evaluated exactly once");
                assertSingleDecision(
                        at + "service", component.decisions().service(), false, AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
                assertNoRecoveredDenial(at);
            }
        }

        // Given a throwing authorizer behind a permitting REST route
        resetState();
        String at = contract + "/REST: ";
        component.authorizer().answer(Mode.THROW);
        String full = token("fern", null, List.of(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE), null);

        // When the route forwards to the combined operation
        Reply reply = get(
                "authenticated", contract, PolicyFixtures.OP_OPERATOR_ACTION, PolicyFixtures.ELIGIBLE_RESOURCE, full);

        // Then the REST decision stands and the service denies without effect
        assertDeniedReply(at, reply, contract, PolicyFixtures.OP_OPERATOR_ACTION);
        assertNoEffects(at);
        assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
        assertSingleDecision(
                at + "service", component.decisions().service(), false, AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
    }

    private void allowedCapabilityThenOwnershipDenial(Contract contract) throws Exception {
        // Given an operator and a resource the application finds ineligible
        resetState();
        String at = "";
        String operator = token("otto", null, List.of(PolicyFixtures.OPERATOR_ROLE), null);

        // When the authenticated route forwards to the owned operation
        Reply denied =
                get("authenticated", contract, PolicyFixtures.OP_OWNED, PolicyFixtures.INELIGIBLE_RESOURCE, operator);

        // Then the capability is allowed, the application refuses before the effect, and nothing runs
        assertDeniedReply(at, denied, contract, PolicyFixtures.OP_OWNED);
        assertNoEffects(at);
        assertSingleDecision(at + "REST", component.decisions().rest(), true, null);
        assertSingleDecision(at + "service capability", component.decisions().service(), true, null);

        // Given the same operator and an eligible resource
        resetState();

        // When the same route is called
        Reply allowed =
                get("authenticated", contract, PolicyFixtures.OP_OWNED, PolicyFixtures.ELIGIBLE_RESOURCE, operator);

        // Then the work runs once
        assertEquals(
                200, allowed.status(), at + "eligible work must run, got " + allowed.status() + " " + allowed.body());
        assertEquals(1, component.effects().total(), at + "exactly one effect expected");

        // Given a direct caller and an ineligible resource
        resetState();
        Outcome direct = callAs(
                Caller.user("dirk").withRoles(PolicyFixtures.OPERATOR_ROLE),
                contract.operation(PolicyFixtures.OP_OWNED),
                PolicyFixtures.INELIGIBLE_RESOURCE);

        // Then the direct dispatch fails after the allowed capability and without effect
        assertFalse(direct.succeeded(), at + "ownership denial must fail the dispatch");
        assertNoEffects(at);
        assertSingleDecision(at + "service capability", component.decisions().service(), true, null);
    }

    private void missingAndAnonymousDirectCallers(Contract contract) throws Exception {
        List<String> restricted = List.of(
                PolicyFixtures.OP_AUTHENTICATED,
                PolicyFixtures.OP_OPERATOR,
                PolicyFixtures.OP_SCOPED,
                PolicyFixtures.OP_ACTION,
                PolicyFixtures.OP_OPERATOR_ACTION);
        for (Caller caller : new Caller[] {null, Caller.anonymous()}) {
            String who = caller == null ? "no caller" : "anonymous caller";
            for (String operation : restricted) {
                // Given no bound caller, or an anonymous one
                resetState();
                String at = contract + "/" + who + "/" + operation + ": ";

                // When the generated client dispatches a restricted operation
                Outcome outcome = callAs(caller, contract.operation(operation), PolicyFixtures.ELIGIBLE_RESOURCE);

                // Then it is denied for lack of authentication, with an anonymous actor and no effect
                assertFalse(outcome.succeeded(), at + "must be denied");
                assertNoEffects(at);
                assertSingleDecision(
                        at + "service",
                        component.decisions().service(),
                        false,
                        AuthzReasonCodes.AUTHENTICATION_REQUIRED);
                assertEquals(
                        PrincipalType.ANONYMOUS,
                        component
                                .decisions()
                                .service()
                                .get(0)
                                .request()
                                .securityContext()
                                .identity()
                                .actor()
                                .type(),
                        at + "the event actor is anonymous");
                assertEquals(0, component.authorizer().calls(), at + "the action is never evaluated");
                assertNoRecoveredDenial(at);
            }

            // Given the same caller shape and a policy that denies everyone
            resetState();
            String at = contract + "/" + who + "/deny: ";

            // When the generated client dispatches the deny-all operation
            Outcome denied =
                    callAs(caller, contract.operation(PolicyFixtures.OP_DENY), PolicyFixtures.ELIGIBLE_RESOURCE);

            // Then it is denied without a caller requirement
            assertFalse(denied.succeeded(), at + "must be denied");
            assertNoEffects(at);
            assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.DENY_ALL);

            // Given the same caller shape and operations that permit everyone or declare nothing
            for (String operation : List.of(PolicyFixtures.OP_PERMIT, PolicyFixtures.OP_UNRESTRICTED)) {
                resetState();
                String permitAt = contract + "/" + who + "/" + operation + ": ";

                // When the generated client dispatches it
                Outcome permitted = callAs(caller, contract.operation(operation), PolicyFixtures.ELIGIBLE_RESOURCE);

                // Then the work runs with no authorization work and no event
                assertTrue(permitted.succeeded(), permitAt + "must run: " + permitted.failure());
                assertEquals(1, component.effects().total(), permitAt + "exactly one effect expected");
                assertEquals(0, component.decisions().all().size(), permitAt + "no event expected");
                assertEquals(0, component.authorizer().calls(), permitAt + "no action evaluation expected");
            }
        }
    }

    private void markedDirectCallers(Contract contract) throws Exception {
        Map<String, Caller> marked = Map.of(
                "subject", fullyClaimed("sam").withSubject(),
                "delegation", fullyClaimed("dee").withDelegation(),
                "reconstruction", fullyClaimed("rae").reconstructed());
        List<String> localOperations = List.of(
                PolicyFixtures.OP_AUTHENTICATED,
                PolicyFixtures.OP_OPERATOR,
                PolicyFixtures.OP_SCOPED,
                PolicyFixtures.OP_OPERATOR_ACTION);
        for (Map.Entry<String, Caller> entry : marked.entrySet()) {
            for (String operation : localOperations) {
                // Given a marked caller who satisfies every claim
                resetState();
                String at = contract + "/" + entry.getKey() + "/" + operation + ": ";

                // When the generated client dispatches an adopted local-predicate operation
                Outcome outcome =
                        callAs(entry.getValue(), contract.operation(operation), PolicyFixtures.ELIGIBLE_RESOURCE);

                // Then the caller shape is refused before claims or the action, and no effect occurs
                assertFalse(outcome.succeeded(), at + "a marked caller must be refused");
                assertNoEffects(at);
                assertSingleDecision(
                        at + "service",
                        component.decisions().service(),
                        false,
                        PolicyFixtures.UNSUPPORTED_POLICY_CALLER);
                assertEquals(0, component.authorizer().calls(), at + "the action is never evaluated");
                assertNoRecoveredDenial(at);
            }
        }

        // Given a reconstructed caller who holds the action
        resetState();
        String at = contract + "/reconstruction/action: ";

        // When the generated client dispatches the action-only operation
        callAs(
                marked.get("reconstruction"),
                contract.operation(PolicyFixtures.OP_ACTION),
                PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the existing action path still reaches the authorizer
        assertEquals(1, component.authorizer().calls(), at + "the action path still evaluates the action");
        assertEquals(1, component.decisions().service().size(), at + "one service event expected");

        // Given the same claims on an unmarked caller
        for (String operation : localOperations) {
            resetState();
            String controlAt = contract + "/unmarked/" + operation + ": ";

            // When the generated client dispatches the same operations
            Outcome outcome =
                    callAs(fullyClaimed("una"), contract.operation(operation), PolicyFixtures.ELIGIBLE_RESOURCE);

            // Then the work runs once
            assertTrue(outcome.succeeded(), controlAt + "an unmarked caller must be permitted: " + outcome.failure());
            assertEquals(1, component.effects().total(), controlAt + "exactly one effect expected");
            assertSingleDecision(controlAt + "service", component.decisions().service(), true, null);
        }
    }

    private void scopePredicate(Contract contract) throws Exception {
        Function<String, Future<String>> call = contract.operation(PolicyFixtures.OP_SCOPED);

        // Given a caller who holds only one of the two required scopes
        resetState();
        String at = "";
        Outcome partial = callAs(
                Caller.user("pia").withScopes(PolicyFixtures.READ_SCOPE), call, PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the scope predicate denies
        assertFalse(partial.succeeded(), at + "one scope is not enough");
        assertNoEffects(at);
        assertSingleDecision(at + "service", component.decisions().service(), false, AuthzReasonCodes.SCOPE_MISSING);

        // Given a caller who holds both scopes
        resetState();
        Outcome both = callAs(
                Caller.user("bo").withScopes(PolicyFixtures.READ_SCOPE, PolicyFixtures.WRITE_SCOPE),
                call,
                PolicyFixtures.ELIGIBLE_RESOURCE);

        // Then the work runs once
        assertTrue(both.succeeded(), at + "both scopes must be enough: " + both.failure());
        assertEquals(1, component.effects().total(), at + "exactly one effect expected");
        assertSingleDecision(at + "service", component.decisions().service(), true, null);
    }

    private void concurrentCallersStayIsolated(Contract contract) throws Exception {
        // Given interleaved callers, half with the operator role and half without
        resetState();
        String at = "";
        Function<String, Future<String>> call = contract.operation(PolicyFixtures.OP_OPERATOR);
        List<Future<String>> started = new ArrayList<>();
        Set<String> allowedActors = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            boolean operator = i % 2 == 0;
            Caller caller = operator
                    ? Caller.user("caller-" + i).withRoles(PolicyFixtures.OPERATOR_ROLE)
                    : Caller.user("caller-" + i);
            if (operator) {
                allowedActors.add("caller-" + i);
            }
            started.add(startCall(caller, call, PolicyFixtures.ELIGIBLE_RESOURCE));
        }

        // When all of them dispatch before any is awaited
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<String> future : started) {
            outcomes.add(await(future));
        }

        // Then each caller gets its own decision, effect and event
        for (int i = 0; i < outcomes.size(); i++) {
            assertEquals(i % 2 == 0, outcomes.get(i).succeeded(), at + "caller-" + i + " got the wrong outcome");
        }
        assertEquals(
                allowedActors,
                new HashSet<>(
                        component.effects().all().stream().map(Effect::actorId).toList()),
                at + "only the permitted callers performed work");
        assertEquals(10, component.effects().total(), at + "ten effects expected");
        Map<String, Boolean> decided = new HashMap<>();
        for (AuthorizationDecisionEvent event : component.decisions().service()) {
            decided.put(
                    event.request().securityContext().identity().actor().id(),
                    event.decision().permitted());
        }
        assertEquals(20, component.decisions().service().size(), at + "one event per caller expected");
        for (int i = 0; i < 20; i++) {
            assertEquals(
                    i % 2 == 0,
                    decided.get("caller-" + i),
                    at + "caller-" + i + " must be attributed its own decision");
        }
    }

    // --- Callers and requests ---

    private static Caller fullyClaimed(String id) {
        return Caller.user(id)
                .withRoles(PolicyFixtures.OPERATOR_ROLE, PolicyFixtures.EXECUTOR_ROLE)
                .withScopes(PolicyFixtures.READ_SCOPE, PolicyFixtures.WRITE_SCOPE);
    }

    private static String token(String subject, String marker, List<String> roles, String scope) {
        JsonObject claims = new JsonObject().put("sub", subject);
        if (marker != null) {
            claims.put("sid", marker);
        }
        if (roles != null && !roles.isEmpty()) {
            claims.put("roles", new JsonArray(roles));
        }
        if (scope != null) {
            claims.put("scope", scope);
        }
        return jwt.generateToken(claims);
    }

    private static Reply get(String outer, Contract contract, String operation, String resourceId, String token)
            throws Exception {
        HttpRequest<Buffer> request = client.get(
                        app.httpPort(), "127.0.0.1", "/policy/" + outer + "/" + contract.path + "/" + operation)
                .addQueryParam("id", resourceId);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        HttpResponse<Buffer> response =
                request.send().toCompletionStage().toCompletableFuture().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String body = response.bodyAsString();
        return new Reply(response.statusCode(), body == null ? "" : body);
    }

    /**
     * Calls a generated client with the caller bound as the ambient security context, or with no
     * context bound when {@code caller} is {@code null}.
     */
    private static Outcome callAs(SecurityContext caller, Function<String, Future<String>> call, String resourceId)
            throws Exception {
        return await(startCall(caller, call, resourceId));
    }

    private static Future<String> startCall(
            SecurityContext caller, Function<String, Future<String>> call, String resourceId) {
        Promise<String> outcome = Promise.promise();
        ContextInternal duplicated = ((ContextInternal) app.vertx().getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            try (ContextHolder.Scope ignored =
                    caller == null ? null : ContextValues.bind(SecurityContext.class, caller)) {
                call.apply(resourceId).onComplete(outcome);
            } catch (Throwable t) {
                outcome.tryFail(t);
            }
        });
        return outcome.future();
    }

    private static Outcome await(Future<String> future) throws Exception {
        try {
            String value = future.toCompletionStage().toCompletableFuture().get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Outcome(true, value, null);
        } catch (ExecutionException e) {
            return new Outcome(false, null, e.getCause());
        }
    }

    // --- Assertions and state ---

    private static void assertDeniedReply(String at, Reply reply, Contract contract, String operation) {
        assertTrue(
                reply.status() >= 400, at + "the request must be refused, got " + reply.status() + " " + reply.body());
        assertFalse(
                reply.body().contains(contract.result(operation, PolicyFixtures.ELIGIBLE_RESOURCE)),
                at + "the work result must not be returned");
    }

    private static void assertNoEffects(String at) {
        assertEquals(
                0,
                component.effects().total(),
                at + "denied work must not run, got " + component.effects().all());
    }

    private static void assertNoRecoveredDenial(String at) {
        assertEquals(0, component.denialRecovery().attempts(), at + "a denial must not reach application recovery");
    }

    private static void assertSingleDecision(
            String boundary, List<AuthorizationDecisionEvent> events, boolean permitted, String reasonCode) {
        assertEquals(1, events.size(), boundary + " must emit exactly one event, got " + describe(events));
        AuthorizationDecisionEvent event = events.get(0);
        assertNotNull(event.decision());
        assertEquals(permitted, event.decision().permitted(), boundary + " decision, got " + describe(events));
        if (reasonCode != null) {
            assertEquals(reasonCode, event.decision().reasonCode(), boundary + " reason code");
        }
    }

    private static String describe(List<AuthorizationDecisionEvent> events) {
        return events.stream()
                .map(event -> event.request().origin().kind() + ":"
                        + (event.decision().permitted() ? "permit" : "deny") + ":"
                        + event.decision().reasonCode())
                .toList()
                .toString();
    }

    private static void resetState() {
        component.effects().reset();
        component.eligibility().reset();
        component.authorizer().reset();
        component.decisions().reset();
        component.denialRecovery().reset();
    }

    /** One scenario run against one service implementation pattern. */
    @FunctionalInterface
    private interface ScenarioBody {
        void run(Contract contract) throws Exception;
    }

    /** Names a scenario and its contract in the failure message and starts it from a clean state. */
    private static Executable scenario(String name, Contract contract, ScenarioBody body) {
        return () -> {
            resetState();
            try {
                body.run(contract);
            } catch (AssertionError e) {
                throw new AssertionError("[" + name + " / " + contract + "] " + e.getMessage(), e);
            } finally {
                resetState();
            }
        };
    }
}
