// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;

/**
 * TP-016 (R-004) case (d)'s fixture: registered by
 * {@link DisabledOnlyApplicationRegistrationModule#disabledOnlyApplicationRegistration}, listing
 * only {@link DisabledResource}, whose catalog entry stays disabled by default ({@code
 * unita.disabledResource.enabled} unset). Composing this registration mounts with zero resources
 * (TP-003's {@code disabledCatalogEntryAcceptedWithNoFallback} builds the same shape), so case (d)
 * proves {@link dev.vertique.rest.jaxrs.JaxRsRouterMount#createRouter}'s unvalidated-mount refusal
 * runs before the empty-mount early return, not merely for a mount that also happens to carry
 * resources.
 */
@RestApplication(name = "disabled-only", path = "/api/disabled-only", resources = DisabledResource.class)
public interface DisabledOnlyApi {}
