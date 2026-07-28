// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.context.ContextValues;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.async.Futures;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates JDK dynamic proxy clients for service contract interfaces.
 *
 * <p>Proxies delegate all transport concerns to {@link ServiceRequestSender}, which handles
 * supervisor availability checks, timeout computation, and error enrichment. The factory itself is
 * responsible only for proxy construction, parameter extraction, and dispatch-envelope construction.
 *
 * <p>Envelope construction is delegated entirely to {@link DispatchEnvelopeBuilder}, which captures
 * MDC context, reads currently bound context values via the registered
 * {@link dev.vertique.core.context.ServiceDispatchContextEncoder}s, and merges any caller-supplied
 * overrides. Security context propagation follows ambient-first precedence: if a
 * {@link SecurityContext} is bound in the current Vert.x context holder (via
 * {@link ContextValues#current(Class)}), the built-in
 * {@link dev.vertique.core.context.SecurityContextServiceDispatchEncoder} captures it into the
 * outgoing envelope automatically. If no ambient SC is bound and the contract method declares a
 * {@link SecurityContext} parameter, the caller's explicit argument is placed in
 * {@code callerOverrides} as a fallback. This gating prevents the encoder-vs-override key collision
 * that {@link ServiceDispatchContextCapturer#mergeCaptured} throws on (FR-CTX-063).
 *
 * <p>The server-side {@link dev.vertique.services.dispatch.ServiceMethodInvoker} restores the
 * security context and other dispatch-context values via the
 * {@link dev.vertique.core.context.ContextHolder}.
 *
 * <p><b>{@code create()}-time completeness guarantee (CG-015 §4.2):</b> before constructing a
 * proxy, every non-static, non-{@code Object}-declared method of the contract interface must have
 * a corresponding registered operation in the resolved {@link ServiceContractRegistry.ContractEntry}
 * — a registry superset (extra operations with no interface method) is allowed, but a missing
 * operation fails {@code create()} fast with {@link IllegalStateException} rather than only
 * surfacing per-invocation when the missing operation is actually called.
 *
 * <p>Instances are created by {@link DispatchModule} via a {@code @Provides} method.
 */
@Slf4j
public class ServiceClientFactory {

    /**
     * The §4.2 pinned protocol prefix for contract↔registry mismatch messages.
     *
     * <p>Deliberately duplicated in the codegen emitter ({@code ClientProxyEmitter}, in
     * {@code vertique-codegen-services}) and sync-tested in both modules — do not change one
     * without the other.
     */
    static final String CONTRACT_MISMATCH_PREFIX = "Service client contract mismatch: ";

    private final ServiceRequestSender sender;
    private final ServiceContractRegistry registry;
    private final DispatchEnvelopeBuilder envelopeBuilder;

    /**
     * Creates a new service client factory.
     *
     * @param sender          the sender used to dispatch event bus requests
     * @param registry        the service contract registry used to resolve operation metadata
     * @param envelopeBuilder the shared envelope builder that captures MDC and context
     *                        values into outgoing {@link DispatchEnvelope}s
     */
    public ServiceClientFactory(
            ServiceRequestSender sender, ServiceContractRegistry registry, DispatchEnvelopeBuilder envelopeBuilder) {
        this.sender = sender;
        this.registry = registry;
        this.envelopeBuilder = envelopeBuilder;
    }

    /**
     * Creates a new service client factory without MDC or context propagation.
     *
     * <p>Package-private convenience constructor for tests that do not require MDC or
     * service-dispatch context propagation. Uses an envelope builder backed by empty registries.
     *
     * @param sender   the sender used to dispatch event bus requests
     * @param registry the service contract registry used to resolve operation metadata
     */
    ServiceClientFactory(ServiceRequestSender sender, ServiceContractRegistry registry) {
        this(
                sender,
                registry,
                new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                        new ServiceDispatchContextRegistry(Set.of(), Set.of()), new DefaultContextHolder())));
    }

    /**
     * Creates a typed proxy for the given contract interface.
     *
     * <p>The proxy dispatches all interface method calls over the event bus using the registered
     * operation addresses. Availability checking, timeout computation, and error enrichment are
     * handled by the underlying {@link ServiceRequestSender}.
     *
     * @param <T>      the contract type
     * @param contract the contract interface class
     * @return a proxy instance that dispatches calls over the event bus
     * @throws IllegalArgumentException if the contract is not registered in the registry
     * @throws IllegalStateException    if any non-static, non-{@code Object}-declared contract
     *                                   method has no corresponding operation registered in the
     *                                   resolved {@link ServiceContractRegistry.ContractEntry}
     *                                   (CG-015 §4.2 completeness check)
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> contract) {
        ServiceContractRegistry.ContractEntry<T> entry = registry.resolve(contract);
        requireCompleteContract(contract, entry);

        // Pre-compute resolved targets per operation to avoid per-invocation allocation
        Map<String, ResolvedServiceTarget> operationTargets = new HashMap<>();
        for (Map.Entry<String, ServiceMethodMeta> op : entry.operations().entrySet()) {
            operationTargets.put(op.getKey(), ResolvedServiceTarget.of(contract, op.getValue()));
        }
        Map<String, ResolvedServiceTarget> cachedTargets = Map.copyOf(operationTargets);

        return (T)
                Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
                    // Delegate Object methods (equals, hashCode, toString)
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args, contract);
                    }

                    // Resolve operation metadata
                    String operationName = OperationIdResolver.resolveOperationName(method);
                    ServiceMethodMeta meta = entry.operations().get(operationName);
                    if (meta == null) {
                        // Unreachable via create(): requireCompleteContract() above already fails
                        // create() fast for any missing operation. Kept as an invariant guard.
                        return Future.failedFuture(new IllegalStateException("No operation found for method "
                                + method.getName() + " on " + contract.getSimpleName()));
                    }

                    // Extract the PAYLOAD parameter by declared position from metadata
                    Object payload = null;
                    List<ParamMeta> params = meta.params();
                    for (int i = 0; i < params.size(); i++) {
                        if (params.get(i).source() == ParamSource.PAYLOAD) {
                            payload = (args != null && i < args.length) ? args[i] : null;
                            break;
                        }
                    }

                    // Add explicit SecurityContext method-arg to callerOverrides ONLY when no
                    // ambient SC is bound, to avoid the encoder-vs-override key collision
                    // (FR-CTX-063). When an ambient SC is bound, the
                    // SecurityContextServiceDispatchEncoder captures it automatically.
                    Map<String, Object> callerOverrides = Map.of();
                    if (ContextValues.current(SecurityContext.class).isEmpty()) {
                        for (int i = 0; i < params.size(); i++) {
                            ParamMeta p = params.get(i);
                            if (p.source() == ParamSource.DISPATCH_CONTEXT
                                    && SecurityContext.class.getName().equals(p.lookupKey())) {
                                SecurityContext arg =
                                        (args != null && i < args.length) ? (SecurityContext) args[i] : null;
                                if (arg != null) {
                                    callerOverrides = Map.of(SecurityContext.class.getName(), arg);
                                }
                                break;
                            }
                        }
                    }

                    DispatchEnvelope<?> envelope =
                            envelopeBuilder.build(payload, callerOverrides, DispatchBoundary.SERVICE_DISPATCH);
                    ResolvedServiceTarget target = cachedTargets.get(operationName);

                    // One-way: fire-and-forget via sender (includes supervisor check)
                    if (meta.oneWay()) {
                        return sender.sendOneWay(target, envelope);
                    }

                    // Request-response: send via sender and unwrap Result
                    return sender.send(target, envelope).compose(Futures::toFuture);
                });
    }

    // --- Helpers ---

    /**
     * Fails fast when the contract interface declares a client-dispatchable method with no
     * corresponding operation in the resolved registry entry (CG-015 §4.2 step 2).
     *
     * <p>Enumerates {@code contract.getMethods()} minus {@code Object}-declared methods minus
     * static methods (statics are never client-dispatchable via a JDK dynamic proxy, and the
     * registry may register them as phantom operations). A registry superset — extra operations
     * with no corresponding interface method — is allowed.
     *
     * @param contract the contract interface class
     * @param entry    the resolved registry entry for {@code contract}
     * @throws IllegalStateException if any qualifying method's resolved operation name is absent
     *                                from {@code entry.operations()}
     */
    private static void requireCompleteContract(Class<?> contract, ServiceContractRegistry.ContractEntry<?> entry) {
        for (Method m : contract.getMethods()) {
            if (m.getDeclaringClass() == Object.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String op = OperationIdResolver.resolveOperationName(m);
            if (!entry.operations().containsKey(op)) {
                throw new IllegalStateException(CONTRACT_MISMATCH_PREFIX + contract.getName()
                        + " has no registered operation '" + op + "' for method " + m.getName());
            }
        }
    }

    /**
     * Handles {@link Object} methods (equals, hashCode, toString) on the proxy.
     *
     * @param proxy    the proxy instance
     * @param method   the Object method being invoked
     * @param args     the method arguments
     * @param contract the contract interface (used for toString)
     * @return the result of the Object method
     */
    private Object handleObjectMethod(Object proxy, Method method, Object[] args, Class<?> contract) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ServiceProxy[" + contract.getSimpleName() + "]";
            default ->
                throw new UnsupportedOperationException(
                        "Unsupported Object method on service proxy: " + method.getName());
        };
    }
}
