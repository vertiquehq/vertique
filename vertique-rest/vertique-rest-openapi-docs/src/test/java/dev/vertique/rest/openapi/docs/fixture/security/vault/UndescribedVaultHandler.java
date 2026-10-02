// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.vault;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The handler of {@value #VAULT_AUTH}: its authentication handler rejects every request with {@code
 * 401}, and its description is empty. Every call of {@link #openApiDescription()}, on any instance,
 * is counted in one process-wide counter, which a test resets before each deployment.
 */
public final class UndescribedVaultHandler implements SecuritySchemeHandler {

    /** The scheme this handler registers. */
    public static final String VAULT_AUTH = "vaultAuth";

    private static final AtomicInteger DESCRIPTION_CALLS = new AtomicInteger();

    /** Creates the handler. */
    public UndescribedVaultHandler() {}

    /** Resets the description call counter to zero. */
    public static void resetDescriptionCalls() {
        DESCRIPTION_CALLS.set(0);
    }

    /**
     * Returns how many times {@link #openApiDescription()} was called since the last reset.
     *
     * @return the call count
     */
    public static int descriptionCalls() {
        return DESCRIPTION_CALLS.get();
    }

    @Override
    public String schemeName() {
        return VAULT_AUTH;
    }

    @Override
    public void configure(SecuritySchemeRegistry registry) {
        AuthenticationHandler rejectAll = ctx -> ctx.fail(401);
        registry.authenticationHandler(rejectAll);
    }

    /**
     * Counts the call and describes nothing.
     *
     * @return {@link Optional#empty()}
     */
    @Override
    public Optional<SecuritySchemeDescription> openApiDescription() {
        DESCRIPTION_CALLS.incrementAndGet();
        return Optional.empty();
    }
}
