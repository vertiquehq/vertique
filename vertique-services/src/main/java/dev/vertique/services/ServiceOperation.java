// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a durable operation identity for a service contract method.
 *
 * <p>This annotation is <b>optional</b> for plain service dispatch. When absent, the Java
 * method name is used as the runtime event bus address segment. When present, its
 * {@link #value()} is used instead, and the operation becomes eligible for stable-target
 * resolution.
 *
 * <p>The two behaviors are:
 * <ul>
 *   <li><b>Without {@code @ServiceOperation}:</b> runtime address uses the method name;
 *       no stable target id is generated; the operation cannot be referenced by durable
 *       integrations (Transactional Messaging, cron {@code service:} targets, etc.).</li>
 *   <li><b>With {@code @ServiceOperation("id")}:</b> runtime address uses the annotation
 *       value; a stable target id is generated; the operation is eligible for all
 *       stable-target integrations.</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @ServiceContract(namespace = "integration", value = "user-service")
 * public interface UserService {
 *
 *     // Stable operation — eligible for durable references
 *     @ServiceOperation("get-user")
 *     Future<UserResponse> getUser(String userId);
 *
 *     // Plain operation — runtime dispatch only, no stable target id
 *     Future<Void> ping();
 * }
 * }</pre>
 *
 * <p><b>Durability note:</b> Changing the operation id is a contract-breaking change for
 * any durable system that persists stable target ids. Renaming the Java method of an
 * unannotated operation changes its address — add {@code @ServiceOperation} before
 * relying on address stability.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServiceOperation {

    /**
     * Durable operation id. Must be non-blank when specified.
     *
     * <p>Used as both the runtime event bus address segment and the operation component
     * of the stable target identity.
     *
     * @return the durable operation id
     */
    String value();
}
