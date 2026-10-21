// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import dev.vertique.core.util.GeneratedCompanions;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowClientProxyLinkageException;
import dev.vertique.workflow.exception.WorkflowProxyContractException;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.RegisteredDefinitionClass;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;

/**
 * Factory that creates type-safe client instances for workflow contract interfaces - preferring a
 * generated zero-reflection {@code {Contract}_WorkflowClientProxy} when present and falling back to a
 * reflective JDK dynamic proxy otherwise.
 *
 * <p>A client is always bound to one workflow definition id, so there is no way to create a client
 * from a contract alone. Create it from the class of a registered definition with
 * {@link #create(Class)}, or from a contract and a definition id with {@link #create(Class, String)};
 * {@link #client(Class)} and {@link #client(Class, String)} return a {@link WorkflowClientBuilder}
 * to select a scope or an explicit version first. Without a version selection a client starts the
 * current version of its definition.
 *
 * <p>A workflow contract interface must be annotated with
 * {@link dev.vertique.workflow.contract.WorkflowContract} and declare methods annotated with
 * {@link dev.vertique.workflow.contract.WorkflowStart}, {@link dev.vertique.workflow.contract.WorkflowSignal},
 * and/or {@link dev.vertique.workflow.contract.WorkflowQuery}.
 *
 * <p>Each client creation validates the contract's methods via {@link WorkflowProxyValidator} first,
 * then prefers a generated static {@code {Contract}_WorkflowClientProxy} (zero reflection) when one is
 * present on the classpath, falling back to a reflective JDK dynamic proxy otherwise. Because
 * validation always runs first and unconditionally, a malformed contract fails identically with or
 * without a generated proxy. A generated class that is present but cannot be instantiated fails loudly
 * with a {@link WorkflowClientProxyLinkageException} rather than silently degrading to the JDK proxy.
 * Validation failures throw {@link WorkflowProxyContractException} at client-creation time (not at
 * invocation time) so violations are surfaced at application startup. Validation looks at the contract
 * only: a start or signal payload that does not match the definition's plan is reported when the
 * client starts or signals.
 *
 * <p>Both kinds of proxy build their start command through {@link WorkflowClientBinding#startCommand},
 * so they send identical commands.
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
    private static final int MAX_DEFINITION_ID_CODE_POINTS = 255;

    private final WorkflowOperations ops;
    private final WorkflowRegistry registry;
    private final WorkflowTypeNames typeNames;

    /**
     * Creates a new {@code WorkflowClientFactory}.
     *
     * @param ops the workflow operations facade to delegate proxy calls to
     * @param registry the workflow registry that maps definition classes to their definition ids
     */
    @Inject
    public WorkflowClientFactory(WorkflowOperations ops, WorkflowRegistry registry) {
        this.ops = ops;
        this.registry = registry;
        this.typeNames = new DefaultWorkflowTypeNames();
    }

    /**
     * Creates a typed client for a registered workflow definition class. The client starts the
     * current version of that definition.
     *
     * @param <C> the contract interface type
     * @param definitionClass the class of a registered definition, registered under exactly one
     *     definition id
     * @return a client implementing the definition's contract interface - the generated proxy when
     *     present, else a JDK dynamic proxy; never null
     * @throws WorkflowProxyContractException if the class is not registered, is registered under more
     *     than one definition id, or its contract fails validation
     */
    public <C> C create(Class<? extends WorkflowDefinition<?, C>> definitionClass) {
        return client(definitionClass).create();
    }

    /**
     * Creates a typed client for a contract interface and a definition id. The client starts the
     * current version of that definition. The id need not be registered when the client is created.
     *
     * @param <C> the contract interface type
     * @param contract the contract interface class; must be annotated with
     *     {@link dev.vertique.workflow.contract.WorkflowContract}
     * @param definitionId the id of the definition the client starts; nonblank, at most 255 code points
     * @return a client implementing the contract interface - the generated proxy when present, else a
     *     JDK dynamic proxy; never null
     * @throws IllegalArgumentException if the definition id is blank or longer than 255 code points
     * @throws WorkflowProxyContractException if the contract fails validation
     */
    public <C> C create(Class<C> contract, String definitionId) {
        return client(contract, definitionId).create();
    }

    /**
     * Returns a builder for a typed client of a registered workflow definition class.
     *
     * @param <C> the contract interface type
     * @param definitionClass the class of a registered definition, registered under exactly one
     *     definition id
     * @return a builder for the client; never null
     * @throws WorkflowProxyContractException if the class is not registered or is registered under more
     *     than one definition id
     */
    @SuppressWarnings("unchecked")
    public <C> WorkflowClientBuilder<C> client(Class<? extends WorkflowDefinition<?, C>> definitionClass) {
        Objects.requireNonNull(definitionClass, "definitionClass");
        RegisteredDefinitionClass registered = registry.definitionClass(definitionClass)
                .orElseThrow(() -> new WorkflowProxyContractException("Definition class " + definitionClass.getName()
                        + " is not registered under exactly one definition id; register it once, or create the"
                        + " client from the contract and a definition id"));
        return new DefaultWorkflowClientBuilder<>(
                this, (Class<C>) registered.contract(), registered.definitionId(), registered.contractName());
    }

    /**
     * Returns a builder for a typed client of a contract interface and a definition id.
     *
     * @param <C> the contract interface type
     * @param contract the contract interface class; must be annotated with
     *     {@link dev.vertique.workflow.contract.WorkflowContract}
     * @param definitionId the id of the definition the client starts; nonblank, at most 255 code points
     * @return a builder for the client; never null
     * @throws IllegalArgumentException if the definition id is blank or longer than 255 code points
     */
    public <C> WorkflowClientBuilder<C> client(Class<C> contract, String definitionId) {
        Objects.requireNonNull(contract, "contract");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (definitionId.codePointCount(0, definitionId.length()) > MAX_DEFINITION_ID_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "definitionId must be at most " + MAX_DEFINITION_ID_CODE_POINTS + " code points");
        }
        return new DefaultWorkflowClientBuilder<>(this, contract, definitionId, typeNames.contractNameOf(contract));
    }

    /**
     * Validates the contract and creates the client bound to the given binding.
     *
     * @param <C> the contract interface type
     * @param contract the contract interface class
     * @param binding the binding the client carries
     * @return the generated proxy when present, else a JDK dynamic proxy
     */
    <C> C createClient(Class<C> contract, WorkflowClientBinding binding) {
        // Validation runs first and unconditionally - so a malformed contract fails identically with or
        // without a generated proxy.
        WorkflowProxyValidator.validate(contract);

        // Prefer the generated static proxy when present (zero reflection); fall back to the JDK dynamic
        // proxy otherwise. A present-but-broken generated class fails loudly.
        // Both proxy kinds receive the operations wrapped with the contract the client was created for, so a
        // signal or query reaches the engine together with the contract it is sent for.
        WorkflowOperations boundOps = new ContractBoundOperations(ops, binding.contractName());
        return GeneratedCompanions.instantiate(
                        contract,
                        PROXY_SUFFIX,
                        new Class<?>[] {WorkflowOperations.class, WorkflowClientBinding.class},
                        new Object[] {boundOps, binding},
                        (fqn, e) -> new WorkflowClientProxyLinkageException(
                                "Generated proxy %s is present but could not be instantiated".formatted(fqn), e))
                .orElseGet(() -> buildJdkProxy(contract, boundOps, binding));
    }

    /**
     * Builds a reflective JDK dynamic proxy for the given contract, pre-compiling per-method
     * operation handlers so invocation is handler-dispatch rather than full reflection each time.
     *
     * @param <C>      the contract interface type
     * @param contract the contract interface class; already validated by the caller
     * @param boundOps the operations the proxy's handlers call, bound to the contract
     * @param binding the binding the proxy's start handler builds its commands from
     * @return a JDK dynamic proxy implementing {@code contract}
     */
    private <C> C buildJdkProxy(Class<C> contract, WorkflowOperations boundOps, WorkflowClientBinding binding) {
        Map<Method, OperationHandler> handlers = WorkflowProxyValidator.precompileHandlers(contract, boundOps, binding);
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
