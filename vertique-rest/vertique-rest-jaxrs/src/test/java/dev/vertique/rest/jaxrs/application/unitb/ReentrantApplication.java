// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.core.router.RouterMount;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * Single-proof (TP-015) JAX-RS application fixture whose {@code @Inject} constructor takes
 * {@code Set<RouterMount>} directly — the mistake the composer's threading rule forbids ("An
 * {@code Application} must not depend on {@code Set<RouterMount>}"). Dagger accepts the resulting
 * cycle at compile time only because {@link ReentrantRegistrationModule}'s registration method
 * takes a {@code Provider<ReentrantApplication>} rather than a direct instance: the {@code Provider}
 * defers construction, so the cycle is broken at compile time and surfaces only when the composer
 * actually calls {@code registration.create()} during composition, re-entering
 * {@code Set<RouterMount>} resolution on the same thread.
 */
@ApplicationPath("/api/reentrant")
public class ReentrantApplication extends Application {

    /**
     * Constructs the application, depending directly on the very {@code Set<RouterMount>} its own
     * registration contributes to — the re-entrant dependency the composer's thread-local flag must
     * catch instead of recursing.
     *
     * @param mounts the resolved mount set (never actually used; resolving it is what re-enters
     *               composition)
     */
    @Inject
    public ReentrantApplication(Set<RouterMount> mounts) {}
}
