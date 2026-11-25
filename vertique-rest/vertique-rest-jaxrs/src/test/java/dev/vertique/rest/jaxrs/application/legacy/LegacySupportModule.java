// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.legacy;

import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import jakarta.annotation.Nullable;

/**
 * Support bindings every characterization component needs: the real {@link DefaultConfigParser}
 * (over the lenient default config mapper), since {@code RestApplications}'s provider calls
 * {@link ConfigParser#parseKeyedObject} on the zero-registration branch too, which a
 * throwing stub cannot serve; and the unsecured {@link SecurityPolicyValidator} stand-in {@code
 * JaxRsRouterMount.Factory} requires. The security-validator binding shape compiles and
 * resolves with a full {@code HttpVerticle} graph.
 */
@Module
final class LegacySupportModule {

    private LegacySupportModule() {}

    /**
     * Provides the unsecured {@link SecurityPolicyValidator} stand-in. {@code null} is a legal
     * value here: {@code JaxRsRouterMount.Factory}'s constructor parameter is {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    /**
     * Provides the real {@link DefaultConfigParser} over the lenient default config mapper.
     *
     * @return the config parser
     */
    @Provides
    static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }
}
