// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.codegen.NoAutoWire;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@link UserService} demonstrating the direct-implementation
 * pattern (the contract is implemented directly, no {@code ServiceHandler} wrapper).
 *
 * <p>This class is marked {@link NoAutoWire} so it is excluded from codegen-driven
 * registration: the active default in this example is {@link UserServiceHandler} (handler
 * pattern). {@code UserServiceImpl} remains in the source tree as a reference for
 * comparison between the two implementation styles.
 *
 * <p>Pre-seeded with three sample users on construction. All mutations are applied to
 * the in-memory map and take effect immediately.
 */
@Slf4j
@NoAutoWire
public class UserServiceImpl implements UserService {

    private final Map<String, UserResponse> users = new ConcurrentHashMap<>();

    /**
     * Creates a new in-memory user service and seeds sample data.
     */
    @Inject
    UserServiceImpl() {
        users.put("1", new UserResponse("1", "Alice", "alice@example.com"));
        users.put("2", new UserResponse("2", "Bob", "bob@example.com"));
        users.put("3", new UserResponse("3", "Charlie", "charlie@example.com"));
    }

    @Override
    public Future<UserResponse> getUser(String userId) {
        UserResponse user = users.get(userId);
        if (user == null) {
            return Future.failedFuture(new NotFoundException("User not found: " + userId));
        }
        return Future.succeededFuture(user);
    }

    @Override
    public Future<List<UserResponse>> listUsers() {
        return Future.succeededFuture(List.copyOf(users.values()));
    }

    @Override
    public Future<Void> deleteUser(String userId) {
        users.remove(userId);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> notifyUserAction(String userId) {
        log.info("User action notification for {}", userId);
        return Future.succeededFuture();
    }
}
