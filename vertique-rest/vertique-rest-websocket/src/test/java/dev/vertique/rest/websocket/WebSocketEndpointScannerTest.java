// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.validation.ValidateWith;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketEndpointScanner}, verifying lifecycle method discovery,
 * validation constraints, message type resolution, path parameter binding, and security
 * policy scanning.
 */
@DisplayName("WebSocketEndpointScanner")
class WebSocketEndpointScannerTest {

    private WebSocketEndpointScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new WebSocketEndpointScanner();
    }

    // --- Valid endpoint fixtures ---

    @WebSocketEndpoint("/ws/test")
    static class SimpleEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}

        @OnMessage
        void onMessage(WebSocketSession session, String msg) {}

        @OnClose
        void onClose(WebSocketSession session) {}

        @OnError
        void onError(WebSocketSession session, Throwable error) {}
    }

    @WebSocketEndpoint("/ws/{type}/{id}")
    static class MultiParamEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session, @PathParam("type") String type, @PathParam("id") String id) {}
    }

    @WebSocketEndpoint("/ws/binary")
    static class BinaryEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, Buffer data) {}
    }

    @WebSocketEndpoint("/ws/custom")
    static class CustomMessageEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, CustomMessage msg) {}

        record CustomMessage(String text, int count) {}
    }

    @WebSocketEndpoint("/ws/future")
    static class FutureReturnEndpoint {

        @OnMessage
        Future<Void> onMessage(WebSocketSession session, String msg) {
            return Future.succeededFuture();
        }
    }

    /** Test validation group marker interface. */
    interface TestValidationGroup {}

    @WebSocketEndpoint("/ws/validated")
    static class ValidateWithEndpoint {

        @OnMessage
        @ValidateWith({TestValidationGroup.class})
        void handle(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/no-validate")
    static class NoValidateWithEndpoint {

        @OnMessage
        void handle(WebSocketSession session, String msg) {}
    }

    // --- Invalid endpoint fixtures ---

    @WebSocketEndpoint("/ws/test")
    static class DuplicateOnOpen {

        @OnOpen
        void one(WebSocketSession session) {}

        @OnOpen
        void two(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/test")
    static class BadReturnType {

        @OnMessage
        String handle(WebSocketSession session, String msg) {
            return "";
        }
    }

    @WebSocketEndpoint("/ws/{id}")
    static class WrongPathParam {

        @OnOpen
        void onOpen(WebSocketSession session, @PathParam("wrong") String x) {}
    }

    static class NotAnnotated {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/multi-payload")
    static class MultiPayloadEndpoint {

        @OnMessage
        void onMessage(String a, @Sanitize(A.class) String b) {}
    }

    @WebSocketEndpoint("/ws/string-and-throwable")
    static class StringAndThrowableEndpoint {

        @OnMessage
        void onMessage(String payload, Throwable error) {}
    }

    // --- Typed access policies ---

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    /** Local role predicate. */
    @RolesAllowed("editor")
    public interface EditorRolePolicy extends AccessPolicy {}

    /** A different local role predicate, used to build distinct policy references. */
    @RolesAllowed("viewer")
    public interface ViewerRolePolicy extends AccessPolicy {}

    /** Authenticated caller only. */
    @Authorized
    public interface AuthenticatedPolicy extends AccessPolicy {}

    /** Deny every caller. */
    @DenyAll
    public interface DenyEveryonePolicy extends AccessPolicy {}

    /** Public: no authentication or authorization. */
    @PermitAll
    public interface PublicPolicy extends AccessPolicy {}

    /** Registered action only. */
    @RequiresAction("cms.content.read")
    public interface ContentReadActionPolicy extends AccessPolicy {}

    /** Local role predicate plus the registered action. */
    @RolesAllowed("editor")
    @RequiresAction("cms.content.read")
    public interface EditorAndActionPolicy extends AccessPolicy {}

    /** Action that parses but is not registered. */
    @RequiresAction("cms.unknown.read")
    public interface UnregisteredActionPolicy extends AccessPolicy {}

    @WebSocketEndpoint("/ws/policy-roles")
    @RequiresPolicy(EditorRolePolicy.class)
    static class RolePolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-authenticated")
    @RequiresPolicy(AuthenticatedPolicy.class)
    static class AuthenticatedPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-deny")
    @RequiresPolicy(DenyEveryonePolicy.class)
    static class DenyPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-public")
    @RequiresPolicy(PublicPolicy.class)
    static class PublicPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-action")
    @RequiresPolicy(ContentReadActionPolicy.class)
    static class ActionPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-roles-action")
    @RequiresPolicy(EditorAndActionPolicy.class)
    static class RoleAndActionPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-unregistered-action")
    @RequiresPolicy(UnregisteredActionPolicy.class)
    static class UnregisteredActionPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-mixed")
    @RequiresPolicy(EditorRolePolicy.class)
    @RolesAllowed("viewer")
    static class PolicyMixedWithInlineRolesEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @RequiresPolicy(EditorRolePolicy.class)
    static class ParentPolicyEndpoint {}

    @WebSocketEndpoint("/ws/policy-hierarchy")
    @RequiresPolicy(ViewerRolePolicy.class)
    static class ChildPolicyEndpoint extends ParentPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    /** Not public, so it is not a valid policy type. */
    @RolesAllowed("editor")
    interface NonPublicPolicy extends AccessPolicy {}

    @WebSocketEndpoint("/ws/policy-invalid")
    @RequiresPolicy(NonPublicPolicy.class)
    static class InvalidPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-inherited")
    static class InheritingPolicyEndpoint extends ParentPolicyEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @RequiresPolicy(EditorRolePolicy.class)
    interface PolicyCarrier {}

    @WebSocketEndpoint("/ws/policy-interface")
    static class InterfacePolicyEndpoint implements PolicyCarrier {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-mixed-action")
    @RequiresPolicy(EditorRolePolicy.class)
    @RequiresAction("cms.content.read")
    static class PolicyMixedWithInlineActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-open")
    static class PolicyOnOpenMethodEndpoint {

        @OnOpen
        @RequiresPolicy(EditorRolePolicy.class)
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-message")
    static class PolicyOnMessageMethodEndpoint {

        @OnMessage
        @RequiresPolicy(EditorRolePolicy.class)
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/policy-close")
    static class PolicyOnCloseMethodEndpoint {

        @OnClose
        @RequiresPolicy(EditorRolePolicy.class)
        void onClose(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/policy-error")
    static class PolicyOnErrorMethodEndpoint {

        @OnError
        @RequiresPolicy(EditorRolePolicy.class)
        void onError(WebSocketSession session, Throwable error) {}
    }

    // --- Tests ---

    @Test
    @DisplayName("endpoint type policies resolve to the existing policy variants; lifecycle policies fail startup")
    void shouldAcceptTypePoliciesAndRejectLifecyclePolicies() {
        // Given a scanner whose action registry knows cms.content.read
        WebSocketEndpointScanner registryScanner = new WebSocketEndpointScanner(
                new WebSocketEndpointScannerRequiresActionTest.StubActionRegistry(Set.of(CONTENT_READ)));

        // When endpoints that reference a policy on the type are scanned, and lifecycle policies are
        // scanned, every check runs so that one failing check cannot mask the others
        assertAll(
                // Then each type policy resolves to the policy variant and action an inline declaration
                // would produce
                () -> {
                    WebSocketEndpointMeta roles = registryScanner.scan(new RolePolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.Constrained(List.of("editor"), List.of(), false),
                            roles.securityPolicy(),
                            "a role policy must resolve to its role constraint");
                    assertFalse(roles.requiredAction().isPresent(), "a role policy declares no action");
                },
                () -> {
                    WebSocketEndpointMeta authenticated = registryScanner.scan(new AuthenticatedPolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.AuthenticatedOnly(),
                            authenticated.securityPolicy(),
                            "an authenticated policy must resolve to authenticated-only");
                },
                () -> {
                    WebSocketEndpointMeta deny = registryScanner.scan(new DenyPolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.DenyAll(),
                            deny.securityPolicy(),
                            "a deny policy must deny every caller");
                },
                () -> {
                    WebSocketEndpointMeta open = registryScanner.scan(new PublicPolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.PermitAll(),
                            open.securityPolicy(),
                            "a public policy must permit every caller");
                },
                () -> {
                    WebSocketEndpointMeta actionOnly = registryScanner.scan(new ActionPolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.None(),
                            actionOnly.securityPolicy(),
                            "an action-only policy adds no local predicate");
                    assertEquals(
                            Optional.of(CONTENT_READ),
                            actionOnly.requiredAction(),
                            "an action-only policy must resolve its registered action");
                },
                () -> {
                    WebSocketEndpointMeta rolesAndAction = registryScanner.scan(new RoleAndActionPolicyEndpoint());
                    assertEquals(
                            new SecurityPolicy.Constrained(List.of("editor"), List.of(), false),
                            rolesAndAction.securityPolicy(),
                            "a role-and-action policy keeps its role constraint");
                    assertEquals(
                            Optional.of(CONTENT_READ),
                            rolesAndAction.requiredAction(),
                            "a role-and-action policy must resolve its registered action");
                },
                // And a policy reference on any lifecycle method fails startup, naming the method and the
                // class-level placement the policy belongs on
                () -> assertLifecyclePolicyRejected(registryScanner, new PolicyOnOpenMethodEndpoint(), "onOpen"),
                () -> assertLifecyclePolicyRejected(registryScanner, new PolicyOnMessageMethodEndpoint(), "onMessage"),
                () -> assertLifecyclePolicyRejected(registryScanner, new PolicyOnCloseMethodEndpoint(), "onClose"),
                () -> assertLifecyclePolicyRejected(registryScanner, new PolicyOnErrorMethodEndpoint(), "onError"));
    }

    @Test
    @DisplayName("a type policy inherited from an endpoint's superclass resolves like a direct declaration")
    void shouldResolveTypePolicyInheritedFromSuperclass() {
        WebSocketEndpointMeta meta = scanner.scan(new InheritingPolicyEndpoint());

        assertEquals(
                new SecurityPolicy.Constrained(List.of("editor"), List.of(), false),
                meta.securityPolicy(),
                "a class-level policy declared on the parent endpoint class must apply to the subclass");
    }

    @Test
    @DisplayName("a type policy declared on an implemented interface resolves like a direct declaration")
    void shouldResolveTypePolicyDeclaredOnImplementedInterface() {
        WebSocketEndpointMeta meta = scanner.scan(new InterfacePolicyEndpoint());

        assertEquals(
                new SecurityPolicy.Constrained(List.of("editor"), List.of(), false),
                meta.securityPolicy(),
                "a policy declared on an implemented interface must apply to the endpoint");
    }

    @Test
    @DisplayName("a type policy mixed with an inline action fails startup")
    void shouldRejectTypePolicyMixedWithInlineAction() {
        PolicyMixedWithInlineActionEndpoint endpoint = new PolicyMixedWithInlineActionEndpoint();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains("Conflicting security annotations"), thrown.getMessage());
    }

    @Test
    @DisplayName("a type policy that is not a valid policy type fails startup naming the endpoint")
    void shouldRejectInvalidTypePolicyNamingEndpoint() {
        InvalidPolicyEndpoint endpoint = new InvalidPolicyEndpoint();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains(InvalidPolicyEndpoint.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("policy must be a public interface"), thrown.getMessage());
    }

    @Test
    @DisplayName("a type policy mixed with an inline role annotation fails startup")
    void shouldRejectTypePolicyMixedWithInlineRoles() {
        PolicyMixedWithInlineRolesEndpoint endpoint = new PolicyMixedWithInlineRolesEndpoint();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains("Conflicting security annotations"), thrown.getMessage());
    }

    @Test
    @DisplayName("distinct policy references across an endpoint hierarchy fail startup")
    void shouldRejectDistinctPolicyReferencesAcrossEndpointHierarchy() {
        ChildPolicyEndpoint endpoint = new ChildPolicyEndpoint();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains("Conflicting security annotations"), thrown.getMessage());
    }

    @Test
    @DisplayName("a type policy naming an unregistered action fails startup")
    void shouldRejectTypePolicyNamingUnregisteredAction() {
        WebSocketEndpointScanner registryScanner = new WebSocketEndpointScanner(
                new WebSocketEndpointScannerRequiresActionTest.StubActionRegistry(Set.of(CONTENT_READ)));
        UnregisteredActionPolicyEndpoint endpoint = new UnregisteredActionPolicyEndpoint();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> registryScanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains("cms.unknown.read"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not registered"), thrown.getMessage());
    }

    @Test
    @DisplayName("a type policy carrying an action fails startup when no action registry is installed")
    void shouldRejectTypeActionPolicyWithoutActionRegistry() {
        ActionPolicyEndpoint endpoint = new ActionPolicyEndpoint();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));

        assertTrue(thrown.getMessage().contains("cms.content.read"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cannot be enforced"), thrown.getMessage());
    }

    private static void assertLifecyclePolicyRejected(
            WebSocketEndpointScanner registryScanner, Object endpoint, String lifecycleMethod) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> registryScanner.scan(endpoint));
        assertTrue(thrown.getMessage().contains("@RequiresPolicy"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(lifecycleMethod), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class-level"), thrown.getMessage());
    }

    @Nested
    @DisplayName("valid endpoint scanning")
    class ValidEndpoints {

        @Test
        @DisplayName("scans all lifecycle methods on SimpleEndpoint")
        void scansAllLifecycleMethods() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());

            assertNotNull(meta.onOpen(), "onOpen must be discovered");
            assertNotNull(meta.onMessage(), "onMessage must be discovered");
            assertNotNull(meta.onClose(), "onClose must be discovered");
            assertNotNull(meta.onError(), "onError must be discovered");
        }

        @Test
        @DisplayName("path is taken from @WebSocketEndpoint value")
        void pathFromAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertEquals("/ws/test", meta.path());
        }

        @Test
        @DisplayName("endpoint instance is stored in meta")
        void endpointInstanceStored() {
            SimpleEndpoint endpoint = new SimpleEndpoint();
            WebSocketEndpointMeta meta = scanner.scan(endpoint);
            assertEquals(endpoint, meta.instance());
        }

        @Test
        @DisplayName("message type defaults to String when param is String")
        void messageTypeDefaultsToString() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertEquals(String.class, meta.messageType());
            assertFalse(meta.binaryMessage());
        }

        @Test
        @DisplayName("message type is Buffer for binary endpoint")
        void messageTypeIsBufferForBinary() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());
            assertEquals(Buffer.class, meta.messageType());
            assertTrue(meta.binaryMessage());
        }

        @Test
        @DisplayName("message type resolves to custom record type")
        void messageTypeResolvesToCustomType() {
            WebSocketEndpointMeta meta = scanner.scan(new CustomMessageEndpoint());
            assertEquals(CustomMessageEndpoint.CustomMessage.class, meta.messageType());
            assertFalse(meta.binaryMessage());
        }

        @Test
        @DisplayName("Future<Void> return type is accepted")
        void futureVoidReturnTypeAccepted() {
            WebSocketEndpointMeta meta = scanner.scan(new FutureReturnEndpoint());
            assertNotNull(meta.onMessage());
        }

        @Test
        @DisplayName("null lifecycle methods when not declared")
        void nullLifecycleMethodsWhenNotDeclared() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());
            assertNull(meta.onOpen(), "onOpen must be null when not declared");
            assertNull(meta.onClose(), "onClose must be null when not declared");
            assertNull(meta.onError(), "onError must be null when not declared");
        }
    }

    @Nested
    @DisplayName("path parameter binding")
    class PathParameterBinding {

        @Test
        @DisplayName("@PathParam names matching template are collected")
        void pathParamsCollectedFromLifecycleMethod() {
            WebSocketEndpointMeta meta = scanner.scan(new MultiParamEndpoint());
            assertEquals(2, meta.pathParams().size());
            assertTrue(meta.pathParams().stream().anyMatch(p -> p.name().equals("type")));
            assertTrue(meta.pathParams().stream().anyMatch(p -> p.name().equals("id")));
        }

        @Test
        @DisplayName("@PathParam name not in template throws IllegalArgumentException")
        void pathParamNotInTemplateThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new WrongPathParam()));
        }
    }

    @Nested
    @DisplayName("duplicate lifecycle annotation detection")
    class DuplicateAnnotations {

        @Test
        @DisplayName("duplicate @OnOpen throws IllegalArgumentException")
        void duplicateOnOpenThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new DuplicateOnOpen()));
        }
    }

    @Nested
    @DisplayName("return type validation")
    class ReturnTypeValidation {

        @Test
        @DisplayName("invalid return type throws IllegalArgumentException")
        void invalidReturnTypeThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new BadReturnType()));
        }
    }

    @Nested
    @DisplayName("payload parameter cardinality")
    class PayloadParameterCardinality {

        @Test
        @DisplayName("second payload-eligible @OnMessage parameter fails scan naming the method")
        void secondPayloadParameterRejected() {
            ConfigurationException failure =
                    assertThrows(ConfigurationException.class, () -> scanner.scan(new MultiPayloadEndpoint()));
            assertTrue(
                    failure.getMessage().contains("MultiPayloadEndpoint.onMessage"),
                    "message must name the method: " + failure.getMessage());
            assertTrue(
                    failure.getMessage().contains("more than one payload parameter"),
                    "message must describe the cardinality violation: " + failure.getMessage());
        }

        @Test
        @DisplayName("String plus Throwable @OnMessage parameters fail scan like other multi-payload shapes")
        void stringPlusThrowableRejected() {
            ConfigurationException failure =
                    assertThrows(ConfigurationException.class, () -> scanner.scan(new StringAndThrowableEndpoint()));
            assertTrue(failure.getMessage().contains("StringAndThrowableEndpoint.onMessage"), failure.getMessage());
            assertTrue(failure.getMessage().contains("more than one payload parameter"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("missing @WebSocketEndpoint annotation")
    class MissingAnnotation {

        @Test
        @DisplayName("class without @WebSocketEndpoint throws IllegalArgumentException")
        void missingAnnotationThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new NotAnnotated()));
        }
    }

    @Nested
    @DisplayName("@ValidateWith resolution")
    class ValidateWithResolution {

        @Test
        @DisplayName("@ValidateWith on @OnMessage method is captured in validationGroups")
        void validateWithGroupsAreCaptured() {
            WebSocketEndpointMeta meta = scanner.scan(new ValidateWithEndpoint());

            assertNotNull(meta.validationGroups(), "validationGroups must not be null when @ValidateWith is present");
            assertArrayEquals(
                    new Class<?>[] {TestValidationGroup.class},
                    meta.validationGroups(),
                    "validationGroups must contain the groups declared in @ValidateWith");
        }

        @Test
        @DisplayName("validationGroups is null when @ValidateWith is absent from @OnMessage")
        void validationGroupsIsNullWhenAnnotationAbsent() {
            WebSocketEndpointMeta meta = scanner.scan(new NoValidateWithEndpoint());

            assertNull(meta.validationGroups(), "validationGroups must be null when @ValidateWith is not present");
        }

        @Test
        @DisplayName("validationGroups is null when there is no @OnMessage method")
        void validationGroupsIsNullWhenNoOnMessage() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());

            assertNull(meta.validationGroups(), "validationGroups must be null when there is no @OnMessage");
        }
    }

    @Nested
    @DisplayName("security policy resolution")
    class SecurityPolicyResolution {

        @WebSocketEndpoint("/ws/secure")
        @jakarta.annotation.security.PermitAll
        class PermitAllEndpoint {

            @OnOpen
            void onOpen(WebSocketSession session) {}
        }

        @WebSocketEndpoint("/ws/denied")
        @jakarta.annotation.security.DenyAll
        class DenyAllEndpoint {

            @OnOpen
            void onOpen(WebSocketSession session) {}
        }

        @Test
        @DisplayName("@PermitAll on class resolves to PermitAll security policy variant")
        void permitAllResolvedFromClassAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new PermitAllEndpoint());
            assertNotNull(meta.securityPolicy(), "Security policy must not be null");
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.PermitAll,
                    "Expected PermitAll policy variant but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }

        @Test
        @DisplayName("@DenyAll on class resolves to DenyAll security policy variant")
        void denyAllResolvedFromClassAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new DenyAllEndpoint());
            assertNotNull(meta.securityPolicy());
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.DenyAll,
                    "Expected DenyAll policy variant but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }

        @Test
        @DisplayName("no security annotation produces None security policy variant")
        void noAnnotationProducesNonePolicy() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertNotNull(meta.securityPolicy());
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.None,
                    "Expected None policy variant for unannotated endpoint but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }
    }
}
