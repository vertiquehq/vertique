// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.management.ManagementVerticle;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.localization.LocaleSource;
import jakarta.annotation.Nullable;
import jakarta.inject.Provider;

/**
 * Application-specific Dagger module providing configuration and optional security bindings,
 * and contributing the custom {@link QueryParamLocaleSource} to the locale source chain.
 *
 * <p>This example does not use authentication or authorization. {@code AuthModule} and
 * {@code SecurityModule} are not included in the component; {@link SecurityPolicyValidator}
 * is provided as {@code null}. {@code SecurityRuntime} resolves to {@code Optional.empty()}
 * automatically via {@code @BindsOptionalOf} in {@code RestCoreModule}.
 */
@Module
public class AppModule {

    /**
     * Provides a {@code null} {@link SecurityPolicyValidator} since this example does not use auth.
     *
     * <p>{@code JaxRsRouterMount.Factory} accepts a nullable {@code SecurityPolicyValidator}
     * and skips policy validation at startup when it is absent.
     *
     * @return always {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    /**
     * Registers the management verticle for deployment in the {@link LifecyclePhase#INFRA} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    /**
     * Registers the HTTP verticle for deployment in the {@link LifecyclePhase#EDGE} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }

    /**
     * Contributes the custom query-param locale source to the {@code Set<LocaleSource>} multibinding.
     *
     * <p>This source runs at priority {@code 0} (the default), which is lower than the built-in
     * {@link dev.vertique.rest.localization.AcceptLanguageLocaleSource#PRIORITY} ({@code 1000}),
     * so it takes precedence over the {@code Accept-Language} header.
     *
     * @param source the query-param locale source instance
     * @return the source, contributed into {@code Set<LocaleSource>}
     */
    @Provides
    @IntoSet
    static LocaleSource queryParamLocaleSource(QueryParamLocaleSource source) {
        return source;
    }
}
