// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The only MCP caller of {@link SecurityPolicyEnforcer#decide}: maps a generated
 * {@link McpToolDescriptor}'s {@link McpToolAccess} to the base {@link SecurityPolicy} plus an
 * optional {@link ActionRef}, supplies the tool {@link ResourceRef} and the MCP
 * {@link InvocationOrigin}, filters denied candidates out of {@code tools/list}, and maps a denied
 * or unknown {@code tools/call} to the same {@code -32602} response so absence and denial are
 * externally indistinguishable (T005; {@code contracts/authorization-and-input-pipeline.md} §
 * Frozen programmatic decision operation).
 *
 * <p><strong>Frozen descriptor mapping</strong> (the frozen authorization matrix):
 *
 * <ul>
 *   <li>{@link McpAccessMode#PERMIT_ALL} → {@link SecurityPolicy.PermitAll}, no action.
 *   <li>{@link McpAccessMode#DENY_ALL} → {@link SecurityPolicy.DenyAll}, no action.
 *   <li>{@link McpAccessMode#RESTRICTED} with at least one role → {@link SecurityPolicy.Constrained}
 *       carrying those roles (no scopes, OR semantics), plus the descriptor's optional action.
 *   <li>{@link McpAccessMode#RESTRICTED} with no role (action-only) →
 *       {@link SecurityPolicy.AuthenticatedOnly}, plus the descriptor's required action.
 * </ul>
 *
 * <p>This class never invokes a tool — denial is decided strictly before any invocation path, and it
 * exposes no invocation method at all. It stays package-private per T005's ownership bound: it adds
 * no public surface to {@code vertique-mcp-server} (enforced by the T006 per-module inventory guard).
 */
@Singleton
class McpPolicyEnforcer {

    /** The final-2026 JSON-RPC error code for both an unknown tool name and a denied tool. */
    static final int UNKNOWN_OR_UNAUTHORIZED_CODE = -32602;

    /**
     * The standard JSON-RPC error message paired with {@link #UNKNOWN_OR_UNAUTHORIZED_CODE}. Carries
     * no tool-identifying detail so a denied tool and an unknown tool name are indistinguishable at
     * the wire.
     */
    static final String UNKNOWN_OR_UNAUTHORIZED_MESSAGE = "Invalid params";

    /** The resource type recorded on every {@link ResourceRef} this enforcer builds. */
    private static final String RESOURCE_TYPE = "mcp-tool";

    private final SecurityPolicyEnforcer securityPolicyEnforcer;

    /**
     * Creates a new MCP policy enforcer backed by the shared {@link SecurityPolicyEnforcer} — the
     * same singleton the REST {@code AuthorizationContributor} consumes (T005 TP-003).
     *
     * @param securityPolicyEnforcer the shared enforcer; must not be {@code null}
     */
    @Inject
    McpPolicyEnforcer(SecurityPolicyEnforcer securityPolicyEnforcer) {
        this.securityPolicyEnforcer = Objects.requireNonNull(securityPolicyEnforcer, "securityPolicyEnforcer");
    }

    /**
     * Evaluates one tool descriptor against an already-established caller {@link SecurityContext},
     * mapping the descriptor's {@link McpToolAccess} to the frozen {@link SecurityPolicy} shape and
     * delegating to {@link SecurityPolicyEnforcer#decide}. Never invokes the tool: this method only
     * ever produces a decision, the same decision {@code tools/list} filtering and {@code tools/call}
     * denial both consume.
     *
     * @param descriptor the tool descriptor to evaluate; must not be {@code null}
     * @param caller     the already-established caller security context (anonymous or
     *                   authenticated); must not be {@code null}
     * @return a future carrying the composed {@link AuthorizationDecision}; never {@code null} and
     *     never a failed future (mirrors {@link SecurityPolicyEnforcer#decide})
     */
    Future<AuthorizationDecision> decide(McpToolDescriptor descriptor, SecurityContext caller) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(caller, "caller");
        Mapping mapping = mappingFor(descriptor.access());
        ResourceRef resource = new ResourceRef(RESOURCE_TYPE, descriptor.name(), Map.of());
        InvocationOrigin origin = InvocationOrigin.of(DispatchBoundary.MCP);
        return securityPolicyEnforcer.decide(caller, mapping.policy(), mapping.action(), resource, origin);
    }

    /**
     * Reports whether the given descriptor must be visible to the given caller in a {@code
     * tools/list} candidate set — {@code true} exactly when {@link #decide} would permit.
     *
     * @param descriptor the tool descriptor to evaluate; must not be {@code null}
     * @param caller     the already-established caller security context; must not be {@code null}
     * @return a future carrying {@code true} when the tool is visible to {@code caller}; never
     *     {@code null} and never a failed future
     */
    Future<Boolean> isVisible(McpToolDescriptor descriptor, SecurityContext caller) {
        return decide(descriptor, caller).map(AuthorizationDecision::permitted);
    }

    /**
     * Builds the bounded external JSON-RPC error payload for both a denied tool and an unknown tool
     * name — always the same code, message, and absent {@code data}, so a caller cannot distinguish
     * "no such tool" from "you may not call this tool" (the T005 carried obligation; T011/T012 wire
     * this into the {@code tools/list} and {@code tools/call} responses).
     *
     * @return the unknown-or-unauthorized {@link McpProtocolCodec.CodecError}; never {@code null} and
     *     never carries {@code data}
     */
    static McpProtocolCodec.CodecError unknownOrUnauthorizedError() {
        return new McpProtocolCodec.CodecError(UNKNOWN_OR_UNAUTHORIZED_CODE, UNKNOWN_OR_UNAUTHORIZED_MESSAGE, null);
    }

    /**
     * Returns the shared {@link SecurityPolicyEnforcer} this instance was constructed with.
     *
     * <p>Primarily exposed for testing (T005 TP-003) to verify that the REST authorization
     * contributor and this class resolve the same singleton instance — mirrors
     * {@link SecurityPolicyEnforcer#decisionPoint()}'s testing-exposure pattern.
     *
     * @return the shared enforcer; never {@code null}
     */
    SecurityPolicyEnforcer securityPolicyEnforcer() {
        return securityPolicyEnforcer;
    }

    /**
     * Maps a resolved {@link McpToolAccess} to the base {@link SecurityPolicy} plus optional
     * {@link ActionRef} the frozen authorization matrix requires.
     *
     * @param access the tool's resolved access requirement; must not be {@code null}
     * @return the mapped policy/action pair; never {@code null}
     */
    private static Mapping mappingFor(McpToolAccess access) {
        return switch (access.mode()) {
            case PERMIT_ALL -> new Mapping(new SecurityPolicy.PermitAll(), Optional.empty());
            case DENY_ALL -> new Mapping(new SecurityPolicy.DenyAll(), Optional.empty());
            case RESTRICTED ->
                access.roles().isEmpty()
                        ? new Mapping(new SecurityPolicy.AuthenticatedOnly(), Optional.of(access.action()))
                        : new Mapping(
                                new SecurityPolicy.Constrained(access.roles(), List.of(), false),
                                Optional.ofNullable(access.action()));
        };
    }

    /**
     * The base policy plus optional action gate resolved for one descriptor.
     *
     * @param policy the base {@link SecurityPolicy}
     * @param action the optional {@code @RequiresAction} gate
     */
    private record Mapping(SecurityPolicy policy, Optional<ActionRef> action) {}
}
