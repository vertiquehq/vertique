// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler-pattern implementation of {@link UserService} demonstrating
 * {@link ServiceHandler} with auto-injected {@link SecurityContext}.
 *
 * <p>Unlike {@link UserServiceImpl} which directly implements the contract interface,
 * this handler implements {@code ServiceHandler<UserService>} and declares
 * {@link SecurityContext} as an extra parameter on handler methods. The framework
 * injects the security context automatically from {@link dev.vertique.services.DispatchContext}
 * during dispatch — it is not part of the client contract.
 *
 * <p>Pre-seeded with three sample users on construction. All mutations are applied to
 * the in-memory map and take effect immediately.
 *
 * @see ServiceHandler
 * @see UserServiceImpl
 */
@Slf4j
public class UserServiceHandler implements ServiceHandler<UserService> {

    private final Map<String, UserResponse> users = new ConcurrentHashMap<>();

    /**
     * Creates a new handler-pattern user service and seeds sample data.
     */
    @Inject
    UserServiceHandler() {
        users.put("1", new UserResponse("1", "Alice", "alice@example.com"));
        users.put("2", new UserResponse("2", "Bob", "bob@example.com"));
        users.put("3", new UserResponse("3", "Charlie", "charlie@example.com"));
    }

    /**
     * Retrieves a user by identifier. The {@code SecurityContext} is auto-injected
     * by the framework and can be used for authorization or operational logging.
     *
     * @param userId the user identifier
     * @param sc the security context (auto-injected, may be {@code null})
     * @return a future containing the user response
     */
    public Future<UserResponse> getUser(String userId, SecurityContext sc) {
        if (sc != null) {
            log.debug(
                    "getUser called by {} for userId={}", sc.identity().actor().id(), userId);
        }
        UserResponse user = users.get(userId);
        if (user == null) {
            return Future.failedFuture(new NotFoundException("User not found: " + userId));
        }
        return Future.succeededFuture(user);
    }

    /**
     * Lists all users.
     *
     * @return a future containing the list of all users
     */
    public Future<List<UserResponse>> listUsers() {
        return Future.succeededFuture(List.copyOf(users.values()));
    }

    /**
     * Deletes a user by identifier.
     *
     * @param userId the user identifier
     * @param sc the security context (auto-injected, may be {@code null})
     * @return a future that completes with {@code null} on success
     */
    public Future<Void> deleteUser(String userId, SecurityContext sc) {
        if (sc != null) {
            log.info("User {} deleted by {}", userId, sc.identity().actor().id());
        }
        users.remove(userId);
        return Future.succeededFuture();
    }

    /**
     * Handles user action notification (one-way, fire-and-forget).
     *
     * @param userId the user identifier
     * @param sc the security context (auto-injected, may be {@code null})
     * @return a future that completes when notification processing finishes
     */
    public Future<Void> notifyUserAction(String userId, SecurityContext sc) {
        if (sc != null) {
            log.info(
                    "User action notification for {} by {}",
                    userId,
                    sc.identity().actor().id());
        } else {
            log.info("User action notification for {}", userId);
        }
        return Future.succeededFuture();
    }
}
