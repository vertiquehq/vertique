// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.services;

import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates transaction-scoped JDK proxy clients for service contract interfaces that write to
 * the transactional outbox instead of dispatching immediately over the event bus.
 *
 * <p>Callers use the generated proxy as if it were a normal service client. Each method
 * invocation serializes the payload and operation identity into an {@link OutboxEntry} and
 * persists it within the caller's database transaction via {@link OutboxService#publish}. The
 * relay then delivers the entry asynchronously after the transaction commits, guaranteeing
 * at-least-once delivery.
 *
 * <p>Validation is eager: {@link #create(Class, SqlClient)} iterates all declared contract
 * methods at proxy-creation time and fails immediately if any method violates the outbox
 * contract. The following constraints are enforced:
 * <ul>
 *   <li>Every non-{@link Object} method must carry {@link ServiceOperation}. Methods without the
 *       annotation are rejected outright — the contract interface must be fully annotated so that
 *       all operations have stable target ids suitable for durable persistence.</li>
 *   <li>One-way ({@code @OneWay}) operations are not supported — the outbox relay requires
 *       a request/reply round-trip to confirm delivery.</li>
 *   <li>The return type must be {@code Future<Void>} — the proxy cannot return data from a
 *       deferred dispatch.</li>
 *   <li>Exactly one payload parameter is required ({@link ServiceMethodMeta.ParamSource#PAYLOAD}).
 *       Methods with no payload or with multiple payloads are rejected.</li>
 *   <li>{@link dev.vertique.security.SecurityContext} parameters (classified as
 *       {@link ServiceMethodMeta.ParamSource#DISPATCH_CONTEXT}) are not supported — security
 *       context propagation is not preserved across the outbox relay boundary.</li>
 * </ul>
 *
 * <p>Example usage within a transactional service method:
 * <pre>{@code
 * Future<Void> placeOrder(Order order, SqlClient tx) {
 *     InventoryService proxy = clientFactory.create(InventoryService.class, tx);
 *     return orderRepository.save(order, tx)
 *         .compose(ignored -> proxy.reserve(new ReserveRequest(order.id(), order.items())));
 * }
 * }</pre>
 */
@Singleton
public class TransactionalServiceClientFactory {

    private final ServiceTargetResolver targetResolver;
    private final OutboxService outboxService;

    /**
     * Creates a new factory.
     *
     * @param targetResolver resolver used to translate contract methods into stable target ids
     * @param outboxService  outbox service used to persist the generated outbox entry
     */
    @Inject
    TransactionalServiceClientFactory(ServiceTargetResolver targetResolver, OutboxService outboxService) {
        this.targetResolver = targetResolver;
        this.outboxService = outboxService;
    }

    /**
     * Creates a typed proxy for the given service contract that writes outbox entries for each
     * method invocation within the supplied transaction.
     *
     * <p>All non-{@link Object} methods on the contract are validated at proxy-creation time.
     * Every method must carry {@link ServiceOperation}; methods without the annotation cause an
     * {@link IllegalArgumentException} to be thrown immediately.
     *
     * @param <T>      the contract type
     * @param contract the contract interface class; every non-{@link Object} method must be
     *                 annotated with {@link ServiceOperation}
     * @param tx       the open database transaction to use when persisting outbox entries
     * @return a proxy that records each method invocation as an outbox entry within {@code tx}
     * @throws IllegalArgumentException if any method is missing {@link ServiceOperation} or
     *                                  violates the outbox contract constraints
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> contract, SqlClient tx) {
        Map<Method, PrecomputedTarget> targets = validateAndPrecompute(contract);

        return (T)
                Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args, contract);
                    }

                    PrecomputedTarget target = targets.get(method);
                    if (target == null) {
                        return Future.failedFuture(new UnsupportedOperationException(
                                "Method " + method.getName() + " on " + contract.getSimpleName()
                                        + " has no @ServiceOperation and cannot be used via the transactional outbox"));
                    }

                    Object payload =
                            (args != null && args.length > target.payloadIndex()) ? args[target.payloadIndex()] : null;
                    OutboxEntry entry = OutboxEntry.builder()
                            .destinationType(DestinationType.SERVICE)
                            .destination(target.targetId())
                            .eventType(target.operationId())
                            .payload(payload)
                            .build();
                    return outboxService.publish(tx, entry).mapEmpty();
                });
    }

    // --- Validation ---

    /**
     * Iterates all methods on the contract that carry {@link ServiceOperation} and resolves each
     * to a {@link PrecomputedTarget}, enforcing outbox constraints in the process.
     *
     * @param contract the contract interface to inspect
     * @param <T>      the contract type
     * @return an unmodifiable map from contract {@link Method} to pre-computed target metadata
     * @throws IllegalArgumentException if any annotated method violates the outbox constraints
     */
    private <T> Map<Method, PrecomputedTarget> validateAndPrecompute(Class<T> contract) {
        Map<Method, PrecomputedTarget> targets = new HashMap<>();
        for (Method method : contract.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.getAnnotation(ServiceOperation.class) == null) {
                throw new IllegalArgumentException(
                        "All non-Object methods on a transactional outbox contract must have @ServiceOperation, but "
                                + contract.getSimpleName() + "." + method.getName() + " does not");
            }

            ResolvedServiceTarget resolved = targetResolver.resolve(contract, method);
            ServiceMethodMeta meta = resolved.meta();

            if (meta.oneWay()) {
                throw new IllegalArgumentException("@OneWay operations cannot be used with transactional outbox: "
                        + contract.getSimpleName() + "." + method.getName());
            }

            if (!Void.class.equals(meta.returnType()) && !void.class.equals(meta.returnType())) {
                throw new IllegalArgumentException(
                        "Only Future<Void> return types supported for transactional outbox operations, but "
                                + contract.getSimpleName() + "." + method.getName()
                                + " returns Future<" + meta.returnType().getSimpleName() + ">");
            }

            List<ServiceMethodMeta.ParamMeta> params = meta.params();
            int payloadIndex = -1;
            int payloadCount = 0;
            for (int i = 0; i < params.size(); i++) {
                ServiceMethodMeta.ParamSource source = params.get(i).source();
                if (source == ServiceMethodMeta.ParamSource.DISPATCH_CONTEXT
                        && dev.vertique.security.SecurityContext.class.isAssignableFrom(
                                params.get(i).type())) {
                    throw new IllegalArgumentException(
                            "SecurityContext parameters not supported in transactional outbox operations: "
                                    + contract.getSimpleName() + "." + method.getName());
                }
                if (source == ServiceMethodMeta.ParamSource.PAYLOAD) {
                    payloadIndex = i;
                    payloadCount++;
                }
            }

            if (payloadCount == 0) {
                throw new IllegalArgumentException(
                        "At least one payload parameter required for transactional outbox operations: "
                                + contract.getSimpleName() + "." + method.getName());
            }
            if (payloadCount > 1) {
                throw new IllegalArgumentException(
                        "At most one payload parameter allowed for transactional outbox operations: "
                                + contract.getSimpleName() + "." + method.getName());
            }

            targets.put(method, new PrecomputedTarget(resolved.targetId(), resolved.operation(), payloadIndex));
        }
        return Map.copyOf(targets);
    }

    // --- Object Method Delegation ---

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
            case "toString" -> "TransactionalOutboxProxy[" + contract.getSimpleName() + "]";
            default ->
                throw new UnsupportedOperationException(
                        "Unsupported Object method on transactional outbox proxy: " + method.getName());
        };
    }

    // --- Nested Types ---

    /**
     * Pre-computed delivery metadata for a single contract method, resolved at proxy-creation time
     * to avoid repeated resolver lookups on every invocation.
     *
     * @param targetId     the stable dot-delimited target id to persist in the outbox row
     * @param operationId  the durable operation id (used as the {@code eventType} in the outbox row)
     * @param payloadIndex zero-based index of the payload parameter in the method's argument array
     */
    private record PrecomputedTarget(String targetId, String operationId, int payloadIndex) {}
}
