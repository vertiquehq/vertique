// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver;
import dev.vertique.rest.core.security.RequiresActionResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyResolver;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.RequiresAction;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans a WebSocket endpoint class for lifecycle annotations and produces
 * {@link WebSocketEndpointMeta} describing the endpoint's routing, security, and
 * parameter bindings.
 *
 * <p>Validates the following constraints at startup:
 * <ul>
 *   <li>The class must be annotated with {@link WebSocketEndpoint}</li>
 *   <li>At most one method per lifecycle annotation ({@link OnOpen}, {@link OnMessage},
 *       {@link OnClose}, {@link OnError})</li>
 *   <li>Lifecycle methods must return {@code void} or {@code Future<Void>}</li>
 *   <li>{@link PathParam} names must match placeholders in the path template</li>
 *   <li>{@link RequiresAction} is supported at <strong>endpoint/class level only</strong> and
 *       enforced once at upgrade; a {@link RequiresAction} on any lifecycle method fails startup
 *       (FR-AUTHZ-048, ADR-0115). A class-level {@link RequiresAction} must parse as a canonical
 *       {@link ActionRef} and be registered in the {@link ActionRegistry}, or startup fails
 *       (fail-closed).</li>
 * </ul>
 */
@Slf4j
class WebSocketEndpointScanner {

    private final SecurityPolicyResolver securityPolicyResolver;

    /** Resolves a class-level {@link RequiresAction} into a canonical {@link ActionRef}. */
    private final RequiresActionResolver requiresActionResolver;

    /**
     * The framework action registry used to validate a class-level {@code @RequiresAction} at startup,
     * or {@code null} when the authorization engine is not installed. A present {@code @RequiresAction}
     * with a {@code null} registry fails startup (fail-closed): the gate cannot be enforced.
     */
    private final @Nullable ActionRegistry actionRegistry;

    /** Sentinel method with no annotations used to force class-level security resolution. */
    private static final Method SENTINEL_METHOD;

    static {
        try {
            SENTINEL_METHOD = WebSocketEndpointScanner.class.getDeclaredMethod("sentinelMethod");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Sentinel with no security annotations so the resolver reads class-level annotations. */
    @SuppressWarnings("unused")
    private void sentinelMethod() {}

    /**
     * Creates a new scanner with no {@link ActionRegistry}, so any endpoint declaring
     * {@code @RequiresAction} fails startup (fail-closed). Used when the authorization engine is
     * absent.
     */
    WebSocketEndpointScanner() {
        this(null);
    }

    /**
     * Creates a new scanner using the default {@link AnnotationSecurityPolicyResolver}.
     *
     * @param actionRegistry the framework action registry used to validate a class-level
     *                       {@code @RequiresAction}, or {@code null} when the authorization engine is
     *                       not installed (in which case any {@code @RequiresAction} fails startup)
     */
    WebSocketEndpointScanner(@Nullable ActionRegistry actionRegistry) {
        this.securityPolicyResolver = new AnnotationSecurityPolicyResolver();
        this.requiresActionResolver = new RequiresActionResolver();
        this.actionRegistry = actionRegistry;
    }

    /**
     * Scans the given endpoint object and returns its metadata.
     *
     * @param endpoint the endpoint instance; must be annotated with {@link WebSocketEndpoint}
     * @return the scanned metadata; never {@code null}
     * @throws IllegalArgumentException if the endpoint violates any validation constraint
     */
    WebSocketEndpointMeta scan(Object endpoint) {
        Class<?> clazz = endpoint.getClass();
        WebSocketEndpoint annotation = clazz.getAnnotation(WebSocketEndpoint.class);
        if (annotation == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @WebSocketEndpoint");
        }

        String path = annotation.value();
        String authScheme = annotation.authScheme();

        // --- Discover lifecycle methods ---
        Method onOpen = null;
        Method onMessage = null;
        Method onClose = null;
        Method onError = null;

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnOpen.class)) {
                if (onOpen != null) throw duplicateAnnotation("@OnOpen", clazz);
                validateReturnType(method, "@OnOpen");
                onOpen = method;
                onOpen.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnMessage.class)) {
                if (onMessage != null) throw duplicateAnnotation("@OnMessage", clazz);
                validateReturnType(method, "@OnMessage");
                onMessage = method;
                onMessage.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnClose.class)) {
                if (onClose != null) throw duplicateAnnotation("@OnClose", clazz);
                validateReturnType(method, "@OnClose");
                onClose = method;
                onClose.setAccessible(true);
            }
            if (method.isAnnotationPresent(OnError.class)) {
                if (onError != null) throw duplicateAnnotation("@OnError", clazz);
                validateReturnType(method, "@OnError");
                onError = method;
                onError.setAccessible(true);
            }
        }

        // --- Resolve message type from @OnMessage parameter ---
        Class<?> messageType = String.class;
        boolean binaryMessage = false;
        if (onMessage != null) {
            MessageInfo msgInfo = resolveMessageType(onMessage);
            messageType = msgInfo.type();
            binaryMessage = msgInfo.binary();
        }

        // --- Resolve @ValidateWith from @OnMessage method ---
        Class<?>[] validationGroups = null;
        if (onMessage != null) {
            dev.vertique.core.validation.ValidateWith vw =
                    onMessage.getAnnotation(dev.vertique.core.validation.ValidateWith.class);
            if (vw != null) {
                validationGroups = vw.value();
            }
        }

        // --- Collect path parameter bindings from all lifecycle methods ---
        WebSocketPathMatcher pathMatcher = new WebSocketPathMatcher(path);
        List<PathParamMeta> pathParamMetas = new ArrayList<>();
        for (Method m : new Method[] {onOpen, onMessage, onClose, onError}) {
            if (m == null) continue;
            collectPathParams(m, pathMatcher, pathParamMetas, clazz);
        }

        // --- Resolve security policy from class-level annotations ---
        // Validate conflicting annotations before resolving
        if (securityPolicyResolver.hasConflictingAnnotations(clazz, SENTINEL_METHOD)) {
            throw new IllegalArgumentException("Conflicting security annotations on " + clazz.getName() + ": "
                    + securityPolicyResolver.describeConflict(clazz, SENTINEL_METHOD));
        }
        if (securityPolicyResolver.hasEmptyRolesAllowed(clazz, SENTINEL_METHOD)) {
            throw new IllegalArgumentException("@RolesAllowed with empty value on " + clazz.getName()
                    + "; use @DenyAll to deny all access, or specify at least one role");
        }
        // The sentinel method carries no security annotations so the resolver
        // always falls back to class-level annotation scanning.
        SecurityPolicy securityPolicy = securityPolicyResolver.resolve(SENTINEL_METHOD, clazz);

        // --- Resolve and validate the @RequiresAction action gate (class-level only; ADR-0115) ---
        // Reject a lifecycle-method @RequiresAction first: WebSocket authorizes once at upgrade, so a
        // per-method annotation is unenforceable and must never be silently ignored (FR-AUTHZ-048).
        rejectLifecycleMethodRequiresAction(clazz, onOpen, onMessage, onClose, onError);
        Optional<ActionRef> requiredAction = resolveClassLevelRequiredAction(clazz, securityPolicy);

        return new WebSocketEndpointMeta(
                path,
                endpoint,
                onOpen,
                onMessage,
                onClose,
                onError,
                messageType,
                binaryMessage,
                pathParamMetas,
                securityPolicy,
                authScheme,
                pathMatcher,
                validationGroups,
                requiredAction);
    }

    // --- @RequiresAction resolution (class-level only; FR-AUTHZ-048, ADR-0115) ---

    /**
     * Fails startup if a {@link RequiresAction} is present on any WebSocket lifecycle method.
     *
     * <p>WebSocket authorization happens once at upgrade, so a method-level {@code @RequiresAction}
     * (which would imply per-message/lifecycle enforcement) cannot be honored and must be rejected
     * rather than silently ignored — preserving the fail-closed invariant on
     * {@link RequiresAction}. Per-message enforcement is a deferred future PEP (ADR-0115).
     *
     * @param clazz     the endpoint class, used for the error message
     * @param onOpen    the {@link OnOpen} method, or {@code null}
     * @param onMessage the {@link OnMessage} method, or {@code null}
     * @param onClose   the {@link OnClose} method, or {@code null}
     * @param onError   the {@link OnError} method, or {@code null}
     * @throws IllegalArgumentException if any lifecycle method declares {@link RequiresAction}
     */
    private void rejectLifecycleMethodRequiresAction(
            Class<?> clazz,
            @Nullable Method onOpen,
            @Nullable Method onMessage,
            @Nullable Method onClose,
            @Nullable Method onError) {
        for (Method m : new Method[] {onOpen, onMessage, onClose, onError}) {
            if (m != null && m.isAnnotationPresent(RequiresAction.class)) {
                throw new IllegalArgumentException("@RequiresAction on WebSocket lifecycle method "
                        + clazz.getSimpleName() + "." + m.getName()
                        + " is not supported: WebSocket authorizes once at upgrade, so @RequiresAction is"
                        + " endpoint/class-level only (FR-AUTHZ-048, ADR-0115). Move it to the "
                        + clazz.getSimpleName() + " class, or remove it.");
            }
        }
    }

    /**
     * Resolves the class-level {@link RequiresAction} into a canonical {@link ActionRef} and validates
     * it against the resolved {@link SecurityPolicy} and the {@link ActionRegistry} at startup
     * (fail-closed).
     *
     * <p>The sentinel method carries no annotations, so {@link RequiresActionResolver} falls back to
     * the class-level annotation. Validation rules, in order:
     * <ol>
     *   <li>absent → {@link Optional#empty()} (no action gate);</li>
     *   <li>present but unparseable as a canonical {@link ActionRef} → startup failure;</li>
     *   <li>present together with a blanket {@link SecurityPolicy.PermitAll} or
     *       {@link SecurityPolicy.DenyAll} → startup failure: {@code @RequiresAction} composes only with
     *       {@code @RolesAllowed}/{@code @Authorized}, so a blanket allow/deny is a contradiction. This
     *       mirrors the REST registrar ({@code JaxRsRouteRegistrar}) and the compile-time codegen check,
     *       and makes the combination that {@code SecurityPolicyEnforcer} documents as unreachable
     *       actually unreachable on the WebSocket path;</li>
     *   <li>present but the {@link ActionRegistry} is not installed → startup failure (the gate cannot
     *       be enforced);</li>
     *   <li>present but the parsed action is not registered → startup failure.</li>
     * </ol>
     *
     * @param clazz          the endpoint class to scan for a class-level {@link RequiresAction}
     * @param securityPolicy the resolved class-level {@link SecurityPolicy}, used to reject the
     *                       {@code @RequiresAction} + {@code @PermitAll}/{@code @DenyAll} conflict
     * @return the resolved, registered {@link ActionRef}, or {@link Optional#empty()} when the
     *     endpoint declares no action gate
     * @throws IllegalArgumentException if a present {@code @RequiresAction} is unparseable, conflicts
     *     with {@code @PermitAll}/{@code @DenyAll}, cannot be enforced because no registry is installed,
     *     or names an unregistered action
     */
    private Optional<ActionRef> resolveClassLevelRequiredAction(Class<?> clazz, SecurityPolicy securityPolicy) {
        Optional<ActionRef> resolved;
        try {
            // Sentinel has no annotations → resolver reads the class-level @RequiresAction only.
            resolved = requiresActionResolver.resolve(SENTINEL_METHOD, clazz);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("@RequiresAction on WebSocket endpoint " + clazz.getName()
                    + " is not a canonical action: " + e.getMessage());
        }

        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        // @RequiresAction AND-composes only with @RolesAllowed/@Authorized; pairing it with a blanket
        // @PermitAll/@DenyAll is a conflict (mirrors JaxRsRouteRegistrar and the codegen check).
        if (securityPolicy instanceof SecurityPolicy.PermitAll || securityPolicy instanceof SecurityPolicy.DenyAll) {
            throw new IllegalArgumentException("@RequiresAction on WebSocket endpoint " + clazz.getName()
                    + " conflicts with "
                    + (securityPolicy instanceof SecurityPolicy.PermitAll ? "@PermitAll" : "@DenyAll")
                    + "; @RequiresAction composes only with @RolesAllowed/@Authorized");
        }

        ActionRef action = resolved.get();
        if (actionRegistry == null) {
            throw new IllegalArgumentException("@RequiresAction('" + action.value() + "') on WebSocket endpoint "
                    + clazz.getName() + " cannot be enforced: the authorization engine is not installed");
        }
        if (!actionRegistry.contains(action)) {
            throw new IllegalArgumentException("@RequiresAction('" + action.value() + "') on WebSocket endpoint "
                    + clazz.getName() + " is not registered in the ActionRegistry");
        }
        return resolved;
    }

    // --- Validation helpers ---

    /**
     * Validates that the lifecycle method return type is {@code void} or {@code Future}.
     *
     * @param method     the method to validate
     * @param annotation the annotation name for error messages
     * @throws IllegalArgumentException if the return type is invalid
     */
    private void validateReturnType(Method method, String annotation) {
        Class<?> returnType = method.getReturnType();
        if (returnType != void.class && returnType != Void.class && !Future.class.isAssignableFrom(returnType)) {
            throw new IllegalArgumentException(annotation
                    + " method "
                    + method.getDeclaringClass().getSimpleName()
                    + "."
                    + method.getName()
                    + " must return void or Future<Void>, got "
                    + returnType.getName());
        }
    }

    /**
     * Holds the resolved message type and whether it represents binary data.
     *
     * @param type   the message payload type
     * @param binary {@code true} if the type is {@link Buffer}
     */
    private record MessageInfo(Class<?> type, boolean binary) {}

    /**
     * Resolves the message payload type from the {@link OnMessage} method's parameters.
     * The message parameter is the first parameter that is not a {@link WebSocketSession},
     * {@link PathParam}, {@link Throwable}, or {@link SecurityContext}.
     *
     * @param method the {@link OnMessage} method
     * @return the resolved message info; defaults to {@code String.class} if no message param found
     */
    private MessageInfo resolveMessageType(Method method) {
        for (var param : method.getParameters()) {
            if (WebSocketSession.class.isAssignableFrom(param.getType())) continue;
            if (param.isAnnotationPresent(PathParam.class)) continue;
            if (Throwable.class.isAssignableFrom(param.getType())) continue;
            if (SecurityContext.class.isAssignableFrom(param.getType())) continue;

            if (Buffer.class.isAssignableFrom(param.getType())) {
                return new MessageInfo(Buffer.class, true);
            }
            return new MessageInfo(param.getType(), false);
        }
        return new MessageInfo(String.class, false);
    }

    /**
     * Collects {@link PathParamMeta} entries from the given method and adds them to the list,
     * avoiding duplicates for the same name+index combination.
     *
     * @param method          the lifecycle method to inspect
     * @param pathMatcher     the compiled path matcher for the endpoint
     * @param collected       the accumulator list of path param metadata
     * @param endpointClass   the endpoint class (used for error messages)
     * @throws IllegalArgumentException if a {@link PathParam} name does not exist in the path template
     */
    private void collectPathParams(
            Method method, WebSocketPathMatcher pathMatcher, List<PathParamMeta> collected, Class<?> endpointClass) {
        var params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            PathParam pp = params[i].getAnnotation(PathParam.class);
            if (pp == null) continue;
            String name = pp.value();
            if (!pathMatcher.paramNames().contains(name)) {
                throw new IllegalArgumentException("@PathParam(\""
                        + name
                        + "\") on "
                        + endpointClass.getSimpleName()
                        + "."
                        + method.getName()
                        + " does not match any placeholder in path \""
                        + pathMatcher.paramNames()
                        + "\"");
            }
            int idx = i;
            if (collected.stream().noneMatch(p -> p.name().equals(name) && p.parameterIndex() == idx)) {
                collected.add(new PathParamMeta(name, i, params[i].getType()));
            }
        }
    }

    /**
     * Creates an exception for a duplicate lifecycle annotation.
     *
     * @param annotation the annotation name
     * @param clazz      the endpoint class
     * @return the exception to throw
     */
    private IllegalArgumentException duplicateAnnotation(String annotation, Class<?> clazz) {
        return new IllegalArgumentException(
                "Multiple " + annotation + " methods found on " + clazz.getName() + "; only one is allowed");
    }
}
