// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a service contract with a stable logical identity.
 *
 * <p>The interface defines the operations (methods) that the service exposes. Runtime event bus
 * addresses are derived from the {@link #namespace()} and {@link #value()} attributes combined with
 * the operation id: {@code services/{namespace}/{value}/{operationId}} (or
 * {@code services/{value}/{operationId}} when namespace is absent).
 *
 * <p>The stable target identity is derived separately and uses dot-delimited segments:
 * {@code {namespace}.{value}.{operationId}} (or {@code {value}.{operationId}} when namespace is
 * absent). This identity is suitable for durable persistence (e.g., outbox rows) because it does not
 * depend on the runtime transport address format.
 *
 * <p>Policy annotations ({@link dev.vertique.resilience.annotation.CircuitBreaker},
 * {@link dev.vertique.resilience.annotation.Retry}, {@link dev.vertique.resilience.annotation.Timeout})
 * on the interface methods define resilience SLAs as part of the contract.
 *
 * <p>Example:
 * <pre>{@code
 * @ServiceContract(namespace = "integration", value = "user-service")
 * public interface UserService {
 *     @ServiceOperation("get-user")
 *     @CircuitBreaker(maxFailures = 5)
 *     Future<UserResponse> getUser(String userId);
 * }
 * }</pre>
 *
 * <p><b>Durability note:</b> Changing {@link #namespace()} or {@link #value()} is a contract-breaking
 * change for any durable system that persists stable target ids (e.g., Transactional Messaging).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ServiceContract {

    /**
     * Durable service name. Required.
     *
     * <p>Combined with {@link #namespace()} and the operation id to form both the stable target
     * identity and the runtime event bus address.
     *
     * @return the durable service name
     */
    String value();

    /**
     * Optional namespace segment for service categorization.
     *
     * <p>When non-empty, prepended to both the stable target id and the runtime address.
     * When empty (the default), the namespace segment is absent from both. There is no implicit
     * default namespace — an empty string means the service has no namespace categorization.
     *
     * <p>Maps to the audit event type (e.g., {@code "integration"}, {@code "business"}).
     *
     * @return the optional namespace segment, or empty string for no namespace
     */
    String namespace() default "";
}
