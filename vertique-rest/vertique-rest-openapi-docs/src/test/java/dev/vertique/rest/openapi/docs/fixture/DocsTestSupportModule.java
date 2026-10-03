// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import jakarta.annotation.Nullable;

/**
 * The binding every test component over {@code RestModule} needs besides its listed modules: the
 * unsecured {@link SecurityPolicyValidator} stand-in {@code JaxRsRouterMount.Factory} requires. The
 * canonical {@code ConfigParser} comes from {@code ConfigParsingModule}, never from here.
 */
@Module
public final class DocsTestSupportModule {

    private DocsTestSupportModule() {}

    /**
     * Provides no {@link SecurityPolicyValidator}: {@code JaxRsRouterMount.Factory}'s parameter is
     * {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }
}
