// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import jakarta.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Resolves the operation segment and optional stable operation id for a service contract method.
 *
 * <p>There are two distinct concepts:
 * <ul>
 *   <li><b>Operation name</b> — the segment used in the runtime event bus address. Always available:
 *       {@link ServiceOperation#value()} if the annotation is present and non-blank, otherwise
 *       the Java method name.</li>
 *   <li><b>Stable operation id</b> — the durable identifier used for stable target resolution.
 *       Only available when {@link ServiceOperation} is explicitly present with a non-blank value.
 *       Operations without {@code @ServiceOperation} are not eligible for stable-target
 *       integrations (Transactional Messaging, cron {@code service:} targets, etc.).</li>
 * </ul>
 *
 * <p>This is the single source of truth for operation resolution, used by both
 * {@link ServiceRegistrar} (at registration time) and {@link ServiceClientFactory}
 * (at proxy invocation time).
 */
final class OperationIdResolver {

    private OperationIdResolver() {}

    /**
     * Resolves the operation name for the runtime event bus address.
     *
     * <p>Returns {@link ServiceOperation#value()} if the annotation is present and non-blank,
     * otherwise falls back to the Java method name.
     *
     * @param method the method to resolve
     * @return the operation name for address construction (never null or blank)
     */
    static String resolveOperationName(Method method) {
        ServiceOperation op = method.getAnnotation(ServiceOperation.class);
        if (op != null && !op.value().isBlank()) {
            return op.value();
        }
        return method.getName();
    }

    /**
     * Resolves the stable operation id for durable target references.
     *
     * <p>Returns the {@link ServiceOperation#value()} if the annotation is present and non-blank.
     * Returns {@code null} if the annotation is absent, signaling that this operation is not
     * eligible for stable-target integrations.
     *
     * @param method the method to resolve
     * @return the stable operation id, or {@code null} if not explicitly annotated
     * @throws IllegalStateException if {@link ServiceOperation} is present but its value is blank
     */
    @Nullable
    static String resolveStableOperationId(Method method) {
        ServiceOperation op = method.getAnnotation(ServiceOperation.class);
        if (op == null) {
            return null;
        }
        if (op.value().isBlank()) {
            throw new IllegalStateException("Method '" + method.getName() + "' on "
                    + method.getDeclaringClass().getSimpleName()
                    + " has @ServiceOperation with a blank value — the operation id must be non-blank");
        }
        return op.value();
    }
}
