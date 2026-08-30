// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.job.delayed.dagger.DelayedJobs;
import dev.vertique.rest.client.RestClientFactory;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

/**
 * Dagger module providing webhook infrastructure: the REST client and the delayed job executor
 * (server-side).
 *
 * <p>The client-side {@link DeliverWebhookJob} proxy is <em>not</em> bound here — the
 * {@code vertique-codegen-delayed-job} processor emits {@code GeneratedDelayedJobClientsModule} with
 * a factory-delegating binding per {@code @DelayedJobContract}, and {@code AppComponent} installs
 * it. Adding a hand-written provider back would make the binding a Dagger duplicate.
 */
@Module
public abstract class WebhookModule {

    /**
     * Contributes the {@link DeliverWebhookJobImpl} executor to the {@link DelayedJobs} multibinding
     * so the framework registers its event bus address during startup.
     *
     * @param impl the executor implementation
     * @return the executor cast to {@link Object} for the multibinding
     */
    @Provides
    @IntoSet
    @DelayedJobs
    static Object deliverWebhookExecutor(DeliverWebhookJobImpl impl) {
        return impl;
    }

    /**
     * Provides the {@link WebhookClient} REST proxy with a 10-second read timeout.
     *
     * @param factory the REST client factory pre-seeded with global interceptors and config
     * @return the singleton REST client proxy
     */
    @Provides
    @Singleton
    static WebhookClient webhookClient(RestClientFactory factory) {
        return factory.builder().readTimeout(10, TimeUnit.SECONDS).build(WebhookClient.class);
    }
}
