// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization;

import dagger.Module;
import dagger.Provides;
import dev.vertique.examples.localization.service.LocaleEchoService;
import dev.vertique.services.ServiceClientFactory;
import jakarta.inject.Singleton;

/**
 * Dagger module providing the typed {@link LocaleEchoService} client proxy.
 *
 * <p>Service implementation registration is handled by the codegen-generated
 * {@code GeneratedServicesModule} (from {@code vertique-codegen-services}), which discovers
 * {@link dev.vertique.examples.localization.service.LocaleEchoServiceHandler} and registers it
 * as the default unconditional handler.
 */
@Module
public class ServiceModule {

    /**
     * Provides the typed {@link LocaleEchoService} event bus proxy client.
     *
     * <p>The proxy routes calls over the event bus to the deployed service verticle.
     *
     * @param factory the service client factory
     * @return a singleton proxy instance for {@link LocaleEchoService}
     */
    @Provides
    @Singleton
    static LocaleEchoService localeEchoServiceClient(ServiceClientFactory factory) {
        return factory.create(LocaleEchoService.class);
    }
}
