// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.core.util.GeneratedCompanions;
import dev.vertique.workflow.exception.WorkflowClientProxyLinkageException;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Factory that creates type-safe client instances for workflow contract interfaces — preferring a
 * generated zero-reflection {@code {Contract}_WorkflowClientProxy} when present and falling back to a
 * reflective JDK dynamic proxy otherwise (see {@link #create(Class)}).
 *
 * <p>A workflow contract interface must be annotated with
 * {@link dev.vertique.workflow.contract.WorkflowContract} and declare methods annotated with
 * {@link dev.vertique.workflow.contract.WorkflowStart}, {@link dev.vertique.workflow.contract.WorkflowSignal},
 * and/or {@link dev.vertique.workflow.contract.WorkflowQuery}.
 *
 * <p>Each call to {@link #create(Class)} validates the contract via {@link WorkflowProxyValidator}
 * first, then prefers a generated static {@code {Contract}_WorkflowClientProxy} (zero reflection)
 * when one is present on the classpath, falling back to a reflective JDK dynamic proxy otherwise.
 * Because validation always runs first and unconditionally, a malformed contract fails identically
 * with or without a generated proxy, and the registry/plan checks are never bypassed by the
 * generated path. A generated class that is present but cannot be instantiated fails loudly with a
 * {@link WorkflowClientProxyLinkageException} rather than silently degrading to the JDK proxy. See
 * ADR-0073. Validation failures throw {@link WorkflowProxyContractException} at proxy-creation time
 * (not at invocation time) so violations are surfaced at application startup.
 *
 * <p>Proxy {@link Object} method behavior:
 * <ul>
 *   <li>{@code toString()} returns {@code "WorkflowProxy[" + contract.getName() + "]"}</li>
 *   <li>{@code equals()} uses identity comparison</li>
 *   <li>{@code hashCode()} returns {@code System.identityHashCode(proxy)}</li>
 * </ul>
 */
@Singleton
public final class WorkflowClientFactory {

    private static final String PROXY_SUFFIX = "_WorkflowClientProxy";

    private final WorkflowOperations ops;
    private final WorkflowRegistry registry;

    /**
     * Creates a new {@code WorkflowClientFactory}.
     *
     * @param ops the workflow operations facade to delegate proxy calls to
     * @param registry the workflow registry used for contract validation and plan lookup
     */
    @Inject
    public WorkflowClientFactory(WorkflowOperations ops, WorkflowRegistry registry) {
        this.ops = ops;
        this.registry = registry;
    }

    /**
     * Creates a typed client for the given workflow contract interface.
     *
     * <p>The contract interface is validated via {@link WorkflowProxyValidator} first (always,
     * unconditionally). The factory then prefers the generated zero-reflection
     * {@code {Contract}_WorkflowClientProxy} when one is present on the classpath, falling back to a
     * reflective JDK dynamic proxy (whose per-method operation handlers are pre-compiled) otherwise.
     *
     * @param <C> the contract interface type
     * @param contract the contract interface class; must be annotated with
     *     {@link dev.vertique.workflow.contract.WorkflowContract}
     * @return a client implementing the contract interface — the generated proxy when present, else a
     *     JDK dynamic proxy; never null
     * @throws WorkflowProxyContractException if the contract fails validation
     */
    public <C> C create(Class<C> contract) {
        // Validation runs first and unconditionally — so a malformed contract fails identically with or
        // without a generated proxy, and the registry/plan checks never bypassed by the generated path.
        WorkflowProxyValidator.validate(contract, registry);

        // Prefer the generated static proxy when present (zero reflection); fall back to the JDK dynamic
        // proxy otherwise. A present-but-broken generated class fails loudly. See ADR-0073.
        return GeneratedCompanions.instantiate(
                        contract,
                        PROXY_SUFFIX,
                        new Class<?>[] {WorkflowOperations.class},
                        new Object[] {ops},
                        (fqn, e) -> new WorkflowClientProxyLinkageException(
                                "Generated proxy %s is present but could not be instantiated".formatted(fqn), e))
                .orElseGet(() -> buildJdkProxy(contract));
    }

    /**
     * Builds a reflective JDK dynamic proxy for the given contract, pre-compiling per-method
     * operation handlers so invocation is handler-dispatch rather than full reflection each time.
     *
     * @param <C>      the contract interface type
     * @param contract the contract interface class; already validated by the caller
     * @return a JDK dynamic proxy implementing {@code contract}
     */
    private <C> C buildJdkProxy(Class<C> contract) {
        Map<Method, OperationHandler> handlers = WorkflowProxyValidator.precompileHandlers(contract, ops, registry);
        return contract.cast(
                Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args, contract);
                    }
                    OperationHandler h = handlers.get(method);
                    if (h == null) {
                        throw new WorkflowProxyContractException("Unhandled method: " + method);
                    }
                    return h.invoke(args);
                }));
    }

    /**
     * Handles {@link Object} method calls on the proxy with documented semantics.
     *
     * @param proxy the proxy instance
     * @param method the {@code Object} method being called
     * @param args the method arguments
     * @param contract the contract interface class (used for {@code toString})
     * @return the result per the documented proxy Object-method behavior
     */
    private static Object handleObjectMethod(Object proxy, Method method, Object[] args, Class<?> contract) {
        return switch (method.getName()) {
            case "toString" -> "WorkflowProxy[" + contract.getName() + "]";
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException("Unhandled Object method: " + method.getName());
        };
    }
}
