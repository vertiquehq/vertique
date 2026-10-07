// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.core.security.AnnotationSecurityPolicyResolver;
import dev.vertique.rest.core.security.RequiresActionResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyResolver;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
 *   <li>{@link OnMessage} may declare at most one payload-eligible parameter</li>
 *   <li>Security annotations ({@link DenyAll}, {@link RolesAllowed}, {@link PermitAll},
 *       {@link Authorized}, {@link RequiresAction}, {@link RequiresPolicy}) are supported at
 *       <strong>endpoint/class level only</strong> and enforced once at upgrade; any of them on a
 *       lifecycle method fails startup rather than being silently ignored. A class-level
 *       {@link RequiresAction} must parse as a canonical {@link ActionRef} and be registered in the
 *       {@link ActionRegistry}, or startup fails (fail-closed; FR-AUTHZ-048, ADR-0115). A class-level
 *       {@link RequiresPolicy} resolves to the same security policy and action gate its inline
 *       declarations would, and its action is validated the same way.</li>
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
     * @throws ConfigurationException if {@link OnMessage} declares more than one payload-eligible
     *     parameter
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
        int messageParameterIndex = -1;
        if (onMessage != null) {
            MessageInfo msgInfo = resolveMessageType(onMessage);
            messageType = msgInfo.type();
            binaryMessage = msgInfo.binary();
            messageParameterIndex = msgInfo.index();
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
        // Reject lifecycle-method security annotations first: the class-level resolution below ignores
        // them, so leaving them in place would register an unauthenticated route with a false "I
        // annotated it" model. WebSocket authorizes once at upgrade — class-level placement only.
        rejectLifecycleMethodSecurityAnnotations(clazz, onOpen, onMessage, onClose, onError);
        rejectLifecycleMethodRequiresAction(clazz, onOpen, onMessage, onClose, onError);
        // Validate conflicting annotations (including a policy reference mixed with an inline security
        // annotation) before resolving
        if (securityPolicyResolver.hasConflictingAnnotations(clazz, SENTINEL_METHOD)) {
            throw new IllegalArgumentException("Conflicting security annotations on " + clazz.getName() + ": "
                    + securityPolicyResolver.describeConflict(clazz, SENTINEL_METHOD));
        }
        if (securityPolicyResolver.hasEmptyRolesAllowed(clazz, SENTINEL_METHOD)) {
            throw new IllegalArgumentException("@RolesAllowed with empty value on " + clazz.getName()
                    + "; use @DenyAll to deny all access, or specify at least one role");
        }
        SecurityPolicy securityPolicy;
        Optional<ActionRef> resolvedAction;
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(clazz);
        if (selectsTypedPolicy(classAnnotations)) {
            // A typed policy is read from the complete class annotations with an empty method list; the
            // sentinel method never takes part in typed method collection.
            securityPolicy = securityPolicyResolver.resolveFromAnnotations(List.of(), classAnnotations);
            resolvedAction = requiresActionResolver.resolve(List.of(), classAnnotations);
        } else {
            // The sentinel method carries no security annotations so the resolver
            // always falls back to class-level annotation scanning.
            securityPolicy = securityPolicyResolver.resolve(SENTINEL_METHOD, clazz);
            resolvedAction = resolveSentinelRequiredAction(clazz);
        }

        // --- Validate the action gate against the policy and registry (class-level only) ---
        Optional<ActionRef> requiredAction = resolveClassLevelRequiredAction(clazz, securityPolicy, resolvedAction);

        return new WebSocketEndpointMeta(
                path,
                endpoint,
                onOpen,
                onMessage,
                onClose,
                onError,
                messageType,
                binaryMessage,
                messageParameterIndex,
                pathParamMetas,
                securityPolicy,
                authScheme,
                pathMatcher,
                validationGroups,
                requiredAction);
    }

    // --- Security annotation placement (class-level only; WebSocket authorizes once at upgrade) ---

    /**
     * Jakarta/framework security annotations that resolve into a {@link SecurityPolicy}. Present on a
     * lifecycle method they would be silently ignored by the class-level resolver, so they must be
     * rejected at scan time. {@link RequiresPolicy} is included: a policy reference names the whole
     * endpoint's admission, never a single lifecycle callback.
     */
    @SuppressWarnings("unchecked")
    private static final Class<? extends Annotation>[] LIFECYCLE_FORBIDDEN_SECURITY_ANNOTATIONS =
            new Class[] {DenyAll.class, RolesAllowed.class, PermitAll.class, Authorized.class, RequiresPolicy.class};

    /**
     * Fails startup if {@link DenyAll}, {@link RolesAllowed}, {@link PermitAll}, {@link Authorized},
     * or {@link RequiresPolicy} is present on any WebSocket lifecycle method.
     *
     * <p>WebSocket authorization happens once at upgrade, and the scanner resolves the endpoint's
     * {@link SecurityPolicy} from class-level annotations only (via a sentinel method). A method-level
     * security annotation is therefore unenforceable and must be rejected rather than silently ignored.
     *
     * @param clazz     the endpoint class, used for the error message
     * @param onOpen    the {@link OnOpen} method, or {@code null}
     * @param onMessage the {@link OnMessage} method, or {@code null}
     * @param onClose   the {@link OnClose} method, or {@code null}
     * @param onError   the {@link OnError} method, or {@code null}
     * @throws IllegalArgumentException if any lifecycle method declares a forbidden security annotation
     */
    private void rejectLifecycleMethodSecurityAnnotations(
            Class<?> clazz,
            @Nullable Method onOpen,
            @Nullable Method onMessage,
            @Nullable Method onClose,
            @Nullable Method onError) {
        for (Method m : new Method[] {onOpen, onMessage, onClose, onError}) {
            if (m == null) {
                continue;
            }
            for (Class<? extends Annotation> annotationType : LIFECYCLE_FORBIDDEN_SECURITY_ANNOTATIONS) {
                if (m.isAnnotationPresent(annotationType)) {
                    String annotationName = "@" + annotationType.getSimpleName();
                    throw new IllegalArgumentException(annotationName + " on WebSocket lifecycle method "
                            + clazz.getSimpleName() + "." + m.getName()
                            + " is not supported: WebSocket authorizes once at upgrade, so " + annotationName
                            + " is endpoint/class-level only. Move it to the " + clazz.getSimpleName()
                            + " class, or remove it.");
                }
            }
        }
    }

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
     * Whether the endpoint's complete class annotations select a typed access policy.
     *
     * <p>Invalid, conflicting or mixed policy declarations have already been rejected by the
     * conflicting-annotation check that runs before this selection.
     *
     * @param classAnnotations the complete class-level annotations of the endpoint
     * @return {@code true} when a {@link RequiresPolicy} reference is selected
     */
    private static boolean selectsTypedPolicy(List<Annotation> classAnnotations) {
        return AccessPolicyResolver.select(List.of(), classAnnotations).isPresent();
    }

    /**
     * Resolves the class-level inline {@link RequiresAction} through the sentinel method, which
     * carries no annotations so {@link RequiresActionResolver} reads the class-level annotation only.
     *
     * @param clazz the endpoint class to scan
     * @return the parsed {@link ActionRef}, or {@link Optional#empty()} when none is declared
     * @throws IllegalArgumentException if a present {@code @RequiresAction} is not a canonical action
     */
    private Optional<ActionRef> resolveSentinelRequiredAction(Class<?> clazz) {
        try {
            return requiresActionResolver.resolve(SENTINEL_METHOD, clazz);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("@RequiresAction on WebSocket endpoint " + clazz.getName()
                    + " is not a canonical action: " + e.getMessage());
        }
    }

    /**
     * Validates the endpoint's resolved action gate against the resolved {@link SecurityPolicy} and
     * the {@link ActionRegistry} at startup (fail-closed). The action is either the class-level
     * {@link RequiresAction} or the action carried by the endpoint's typed policy; it has already been
     * parsed as a canonical {@link ActionRef} by the time it reaches this method. Validation rules,
     * in order:
     * <ol>
     *   <li>absent → {@link Optional#empty()} (no action gate);</li>
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
     * @param clazz          the endpoint class, used for error messages
     * @param securityPolicy the resolved class-level {@link SecurityPolicy}, used to reject the
     *                       {@code @RequiresAction} + {@code @PermitAll}/{@code @DenyAll} conflict
     * @param resolved       the already-resolved action gate, or {@link Optional#empty()}
     * @return the resolved, registered {@link ActionRef}, or {@link Optional#empty()} when the
     *     endpoint declares no action gate
     * @throws IllegalArgumentException if a present action gate conflicts with
     *     {@code @PermitAll}/{@code @DenyAll}, cannot be enforced because no registry is installed, or
     *     names an unregistered action
     */
    private Optional<ActionRef> resolveClassLevelRequiredAction(
            Class<?> clazz, SecurityPolicy securityPolicy, Optional<ActionRef> resolved) {
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
     * Holds the resolved message type, whether it represents binary data, and which parameter
     * carries it.
     *
     * @param type   the message payload type
     * @param binary {@code true} if the type is {@link Buffer}
     * @param index  the zero-based index of the message parameter, or {@code -1} when the method
     *               declares none
     */
    private record MessageInfo(Class<?> type, boolean binary, int index) {}

    /**
     * Resolves the message payload type and its parameter position from the {@link OnMessage}
     * method's parameters. The message parameter is the first parameter that is not a
     * {@link WebSocketSession}, {@link PathParam}, or {@link SecurityContext}. {@link Throwable}
     * parameters are payload-eligible here so scan-time counting matches {@code buildArgs} on
     * {@link OnMessage}, where {@code error} is always {@code null} and a {@code Throwable} slot
     * receives the decoded message payload.
     *
     * <p>The index is resolved here, by the same predicate that picks the type, so the registrar
     * never re-derives which parameter is the payload when it applies that parameter's own
     * invocation policies. A second payload-eligible parameter is rejected: the registrar would
     * otherwise deliver the same decoded payload to that position without resolving its own policy
     * chain.
     *
     * @param method the {@link OnMessage} method
     * @return the resolved message info; defaults to {@code String.class} at index {@code -1} if no
     *     message param found
     * @throws ConfigurationException if the method declares more than one payload-eligible parameter
     */
    private MessageInfo resolveMessageType(Method method) {
        var params = method.getParameters();
        MessageInfo found = null;
        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            if (!isPayloadEligible(param)) {
                continue;
            }
            if (found != null) {
                throw new ConfigurationException("@OnMessage method "
                        + method.getDeclaringClass().getSimpleName()
                        + "."
                        + method.getName()
                        + " declares more than one payload parameter; declare exactly one");
            }
            if (Buffer.class.isAssignableFrom(param.getType())) {
                found = new MessageInfo(Buffer.class, true, i);
            } else {
                found = new MessageInfo(param.getType(), false, i);
            }
        }
        return found != null ? found : new MessageInfo(String.class, false, -1);
    }

    /**
     * Whether the parameter can carry the decoded {@link OnMessage} payload.
     *
     * @param param the method parameter
     * @return {@code true} when the parameter is not a session, path param, or security context
     */
    private static boolean isPayloadEligible(Parameter param) {
        if (WebSocketSession.class.isAssignableFrom(param.getType())) {
            return false;
        }
        if (param.isAnnotationPresent(PathParam.class)) {
            return false;
        }
        if (SecurityContext.class.isAssignableFrom(param.getType())) {
            return false;
        }
        return true;
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
