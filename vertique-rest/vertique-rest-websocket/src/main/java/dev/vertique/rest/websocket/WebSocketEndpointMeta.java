// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.authz.ActionRef;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Immutable metadata for a single WebSocket endpoint class discovered at startup.
 *
 * @param path             the path template (e.g. {@code "/ws/chat/{roomId}"})
 * @param instance         the endpoint object instance
 * @param onOpen           the method annotated with {@link OnOpen}, or {@code null} if not declared
 * @param onMessage        the method annotated with {@link OnMessage}, or {@code null} if not declared
 * @param onClose          the method annotated with {@link OnClose}, or {@code null} if not declared
 * @param onError          the method annotated with {@link OnError}, or {@code null} if not declared
 * @param messageType      the resolved message payload type for deserialization
 * @param binaryMessage    {@code true} if the message type is {@link io.vertx.core.buffer.Buffer}
 * @param messageParameterIndex the zero-based index of the {@link OnMessage} method's message
 *                         parameter — the one carrying the payload rather than the session, a
 *                         {@link jakarta.ws.rs.PathParam}, a {@link Throwable}, or a
 *                         {@link dev.vertique.security.SecurityContext} — or {@code -1} when the
 *                         endpoint declares no {@link OnMessage} method or that method takes no
 *                         payload parameter. Resolved once by the scanner alongside
 *                         {@code messageType} so the registrar has a single source of truth for
 *                         which parameter the payload's own policies belong to
 * @param pathParams       metadata for all path parameter bindings across lifecycle methods
 * @param securityPolicy   the resolved security policy for the endpoint
 * @param authScheme       the preferred auth scheme name from {@link WebSocketEndpoint#authScheme()}
 * @param pathMatcher      pre-compiled path template matcher for extracting path parameter values
 * @param validationGroups the Bean Validation groups to apply when validating incoming messages,
 *                         resolved from {@code @ValidateWith} on the {@link OnMessage} method;
 *                         {@code null} means use the default validation group
 * @param requiredAction   the canonical action gate resolved from a class-level
 *                         {@code @RequiresAction}, AND-composed with {@code securityPolicy} and
 *                         enforced once at upgrade (FR-AUTHZ-048, ADR-0115); {@link Optional#empty()}
 *                         when the endpoint declares no action gate
 */
record WebSocketEndpointMeta(
        String path,
        Object instance,
        @Nullable Method onOpen,
        @Nullable Method onMessage,
        @Nullable Method onClose,
        @Nullable Method onError,
        Class<?> messageType,
        boolean binaryMessage,
        int messageParameterIndex,
        List<PathParamMeta> pathParams,
        SecurityPolicy securityPolicy,
        String authScheme,
        WebSocketPathMatcher pathMatcher,
        @Nullable Class<?>[] validationGroups,
        Optional<ActionRef> requiredAction) {}
