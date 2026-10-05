// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import dev.vertique.core.codegen.MethodMetadata;
import java.util.Objects;

/**
 * The REST client operation behind an outbound call: the client interface the application
 * registered and built, and the operation it invoked.
 *
 * <p>{@code method.declaringType()} is the interface that <em>declares</em> the operation, which for
 * an operation inherited from a super-interface is not the client interface the application built.
 * {@link #clientType()} is that client interface, so a capturer reads type-level annotations from it
 * (and its super-interfaces) and method-level annotations from {@link #method()}.
 *
 * @param clientType the client interface the proxy was built for; never {@code null}
 * @param clientName the client's logical name ({@code @RestClient(name=...)}, or the interface's
 *                   simple name when blank); never {@code null}
 * @param method     the dispatcher-owned metadata of the invoked operation; never {@code null}
 */
public record RestClientOperation(Class<?> clientType, String clientName, MethodMetadata method) {

    /** Rejects a missing component. */
    public RestClientOperation {
        Objects.requireNonNull(clientType, "clientType");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(method, "method");
    }
}
