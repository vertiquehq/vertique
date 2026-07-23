// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

/**
 * Marker interface for service handlers that implement a {@link ServiceContract} contract
 * without directly implementing the contract interface.
 *
 * <p>Instead of {@code class Impl implements Contract}, implement
 * {@code ServiceHandler<Contract>}. Handler methods match contract operations by Java method name
 * and may declare additional framework-injectable parameters (e.g.,
 * {@link dev.vertique.core.SecurityContext}) that the framework resolves automatically during
 * dispatch.
 *
 * <p>The type parameter {@code C} must be an interface annotated with {@link ServiceContract}.
 * The framework resolves {@code C} at runtime via reflection on the generic interfaces.
 *
 * <h3>Method Matching Rules</h3>
 * <ol>
 *   <li>Handler method name must match the contract method's Java symbol name (not the
 *       {@link ServiceOperation} value, which is the durable address identity).</li>
 *   <li>Exactly one handler method per name — overloads are rejected.</li>
 *   <li>Payload parameters (non-injectable types) must match the contract's payload parameters
 *       in order and type.</li>
 *   <li>Extra parameters must be framework-injectable types
 *       ({@link dev.vertique.core.SecurityContext} in v1).</li>
 *   <li>Return type must match the contract method's generic return type exactly.</li>
 * </ol>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @ServiceContract(namespace = "integration", value = "user-service")
 * public interface UserService {
 *     @ServiceOperation("get-user")
 *     Future<UserResponse> getUser(String userId);
 *
 *     @ServiceOperation("list-users")
 *     Future<List<UserResponse>> listUsers();
 * }
 *
 * public class UserServiceHandler implements ServiceHandler<UserService> {
 *     public Future<UserResponse> getUser(String userId, SecurityContext sc) {
 *         // SecurityContext auto-injected from DispatchContext — NOT part of the contract
 *     }
 *
 *     public Future<List<UserResponse>> listUsers() {
 *         return Future.succeededFuture(List.of());
 *     }
 * }
 * }</pre>
 *
 * <p>Both patterns work side-by-side in the same application: direct implementation
 * ({@code implements Contract}) and handler implementation ({@code implements ServiceHandler<Contract>}).
 *
 * @param <C> the contract interface type (must be annotated with {@link ServiceContract})
 * @see ServiceRegistrar
 * @see ServiceContract
 */
public interface ServiceHandler<C> {}
