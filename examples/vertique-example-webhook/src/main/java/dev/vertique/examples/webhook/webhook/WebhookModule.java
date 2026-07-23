// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import dev.vertique.job.delayed.dagger.DelayedJobs;
import dev.vertique.rest.client.RestClientFactory;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

/**
 * Dagger module providing webhook infrastructure: the REST client, the delayed job executor
 * (server-side), and the delayed job client proxy (client-side).
 */
@Module
public class WebhookModule {

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
     * Provides the {@link DeliverWebhookJob} client proxy created by the framework's
     * {@link DelayedJobClientFactory}.
     *
     * @param factory the delayed job client factory
     * @return the singleton proxy implementing {@link DeliverWebhookJob}
     */
    @Provides
    @Singleton
    static DeliverWebhookJob deliverWebhookClient(DelayedJobClientFactory factory) {
        return factory.create(DeliverWebhookJob.class);
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
