// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable, startup-only collector of {@link AuthenticationHandler}s keyed by security scheme name.
 *
 * <p>As each {@link SecuritySchemeHandler} is configured during mount startup, the
 * {@link CollectingSecuritySchemeRegistry} scoped to its scheme records the
 * {@link AuthenticationHandler} here. The mount then looks the handler up by scheme name for every
 * operation whose {@link dev.vertique.rest.core.routing.RestOperationDescriptor#securityRequirementSets()}
 * reference that scheme, installing the same shared handler instance on each such route (or composing
 * alternatives into a {@code ChainAuthHandler.any()} when an operation declares more than one).
 *
 * <p>This is the plain-{@link io.vertx.ext.web.Router Router} replacement for the OpenAPI
 * {@code RouterBuilder.security(name).httpHandler(...)} surface; it is populated once at startup on the
 * event-loop thread that builds the router and read during the same build, so it needs no
 * synchronization.
 *
 * <p><strong>Fail-closed on duplicate schemes.</strong> Each {@link SecuritySchemeHandler} is expected
 * to own a distinct {@link SecuritySchemeHandler#schemeName()}. Two handlers claiming the same scheme
 * name are a configuration error: silently keeping whichever the Dagger {@code Set} iteration recorded
 * last would make it non-deterministic which authentication handler guards the scheme. The collector
 * therefore <em>detects</em> a duplicate and fails startup with a {@link RestConfigurationException}
 * naming the conflicting scheme and the contributing handler classes (finding W5).
 */
final class SecuritySchemeHandlerCollector {

    private final Map<String, AuthenticationHandler> handlers = new HashMap<>();
    private final Map<String, SecuritySchemeHandler> contributors = new HashMap<>();

    /**
     * Records the {@link AuthenticationHandler} for the scheme owned by {@code contributor}. Fails
     * startup with a {@link RestConfigurationException} if a handler for the same
     * {@link SecuritySchemeHandler#schemeName()} was already recorded by a different contributor — two
     * handlers claiming the same scheme would otherwise silently overwrite, with the Dagger set
     * iteration order deciding which authentication handler wins (finding W5).
     *
     * @param contributor the scheme handler that owns the scheme name; must not be {@code null}
     * @param handler     the authentication handler to record; must not be {@code null}
     * @throws RestConfigurationException if a handler for the same scheme name was already recorded by
     *     a different contributing {@link SecuritySchemeHandler}
     */
    void record(SecuritySchemeHandler contributor, AuthenticationHandler handler) {
        Objects.requireNonNull(contributor, "contributor");
        Objects.requireNonNull(handler, "handler");
        String schemeName = Objects.requireNonNull(contributor.schemeName(), "schemeName");
        SecuritySchemeHandler existing = contributors.putIfAbsent(schemeName, contributor);
        if (existing != null) {
            throw new RestConfigurationException("Duplicate security scheme '" + schemeName
                    + "' is configured by multiple SecuritySchemeHandlers: "
                    + existing.getClass().getName() + " and "
                    + contributor.getClass().getName()
                    + "; each security scheme must be configured by exactly one handler so it is "
                    + "deterministic which authentication handler guards the scheme (fail-closed).");
        }
        handlers.put(schemeName, handler);
    }

    /**
     * Returns the recorded {@link AuthenticationHandler} for a scheme, if one was registered.
     *
     * @param schemeName the security scheme name to look up
     * @return the recorded handler, or {@link Optional#empty()} when no handler was registered for the
     *     scheme (e.g. the operation references a scheme with no configured handler)
     */
    Optional<AuthenticationHandler> handlerFor(String schemeName) {
        return Optional.ofNullable(handlers.get(schemeName));
    }
}
