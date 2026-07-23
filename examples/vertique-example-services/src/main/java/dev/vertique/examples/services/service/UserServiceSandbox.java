// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.codegen.ConditionalOnProperty;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Sandbox mock implementation of {@link UserService} returning canned responses.
 *
 * <p>Activated when {@code sandboxEnabled=true} in the application configuration via
 * the {@link ConditionalOnProperty} annotation. The generated
 * {@code UserService_ContractContributor} from {@code vertique-codegen-services} reads this
 * annotation at compile time and emits runtime selection logic that picks this implementation
 * over the unconditional default {@link UserServiceHandler} when the property matches.
 *
 * <p>Useful for development and testing without external dependencies.
 */
@ConditionalOnProperty(name = "sandboxEnabled")
public class UserServiceSandbox implements UserService {

    /**
     * Creates a new sandbox user service instance.
     */
    @Inject
    UserServiceSandbox() {}

    @Override
    public Future<UserResponse> getUser(String userId) {
        return Future.succeededFuture(new UserResponse(userId, "Sandbox User", "sandbox@example.com"));
    }

    @Override
    public Future<List<UserResponse>> listUsers() {
        return Future.succeededFuture(List.of(
                new UserResponse("sandbox-1", "Sandbox Alice", "alice@sandbox.com"),
                new UserResponse("sandbox-2", "Sandbox Bob", "bob@sandbox.com")));
    }

    @Override
    public Future<Void> deleteUser(String userId) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> notifyUserAction(String userId) {
        return Future.succeededFuture();
    }
}
