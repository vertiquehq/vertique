// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.reentry;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.jaxrs.application.manual.ReentrantResource;

/**
 * TP-015 (G-06 (b)): an explicit-mode application listing {@link ReentrantResource} — a manual
 * resource whose {@code @Inject} constructor depends directly on this component's own
 * {@code Set<RouterMount>} — so the composer's manual-resource resolution (not the
 * zero-declaration body) is the path that re-enters composition.
 */
@RestApplication(name = "reentrant-explicit", path = "/api/reentrant-explicit", resources = ReentrantResource.class)
public interface ReentrantExplicitApi {}
