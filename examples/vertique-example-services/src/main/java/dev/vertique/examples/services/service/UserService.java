// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;
import java.util.List;

/**
 * Contract interface for the user service.
 *
 * <p>Methods are annotated with resilience policies ({@link Timeout}, {@link CircuitBreaker})
 * that are enforced server-side by the dispatch framework. The policies form part of the
 * contract — callers are guaranteed at-most the declared timeout.
 *
 * <p>Note: {@code @Retry} is intentionally absent from {@code getUser} because
 * "user not found" is a deterministic client error, not a transient failure. Retrying it
 * would waste resources and delay the 404 response.
 */
@ServiceContract(namespace = "integration", value = "user-service")
public interface UserService {

    /**
     * Retrieves a single user by identifier.
     *
     * @param userId the unique user identifier
     * @return a future containing the user, or a failed future with {@link jakarta.ws.rs.NotFoundException}
     *         if no user exists for the given id
     */
    @ServiceOperation("getUser")
    @Timeout(5000)
    @CircuitBreaker(maxFailures = 5, timeoutMs = 3000)
    Future<UserResponse> getUser(String userId);

    /**
     * Lists all users.
     *
     * @return a future containing the list of all users; never {@code null}
     */
    @ServiceOperation("listUsers")
    @CircuitBreaker
    Future<List<UserResponse>> listUsers();

    /**
     * Deletes a user by identifier.
     *
     * <p>If no user exists with the given id, the operation succeeds silently.
     *
     * @param userId the unique user identifier
     * @return a future that completes with {@code null} on success
     */
    @ServiceOperation("deleteUser")
    @CircuitBreaker
    Future<Void> deleteUser(String userId);

    /**
     * Notifies that a user action occurred. This is a fire-and-forget operation —
     * the caller does not wait for processing to complete.
     *
     * @param userId the user identifier
     * @return a future that completes immediately after the message is sent
     */
    @ServiceOperation("notifyUserAction")
    @OneWay
    Future<Void> notifyUserAction(String userId);
}
