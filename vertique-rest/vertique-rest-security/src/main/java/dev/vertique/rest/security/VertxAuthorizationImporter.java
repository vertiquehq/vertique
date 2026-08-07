// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Future;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import java.util.Set;

/** Placeholder skeleton for the Vert.x authorization-provider importer (red-slice; behavior lands in the green slice). */
final class VertxAuthorizationImporter {

    static final String EXCLUDED_JWT_CLAIMS_PROVIDER_ID = "jwt-claims";

    private final Set<AuthorizationProvider> providers;
    private final Set<String> excludedProviderIds;

    VertxAuthorizationImporter(Set<AuthorizationProvider> providers) {
        this(providers, Set.of(EXCLUDED_JWT_CLAIMS_PROVIDER_ID));
    }

    VertxAuthorizationImporter(Set<AuthorizationProvider> providers, Set<String> excludedProviderIds) {
        this.providers = providers;
        this.excludedProviderIds = excludedProviderIds;
    }

    Future<AuthorizationClaims> importInto(User user, AuthorizationClaims base) {
        return Future.succeededFuture(base);
    }
}
