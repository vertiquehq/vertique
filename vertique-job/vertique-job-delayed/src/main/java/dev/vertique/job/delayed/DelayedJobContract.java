// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface that extends {@link DelayedJobClient} as a typed delayed job contract.
 *
 * <p>The annotated interface serves as the client-side API for enqueueing jobs. A corresponding
 * server-side {@link DelayedJobExecutor} implementation processes the jobs. The framework
 * generates a JDK dynamic proxy for the annotated interface via {@link DelayedJobClientFactory}.
 *
 * <p>Configuration defaults from this annotation can be overridden at runtime via the
 * application config under {@code delayedJob.contracts.{name}.*}:
 * <pre>{@code
 * delayedJob:
 *   contracts:
 *     deliver-webhook:
 *       maxAttempts: 5
 *       queue: priority
 *       priority: 10
 * }</pre>
 *
 * <p>Priority: config &gt; annotation &gt; framework defaults.
 *
 * <p>Example:
 * <pre>{@code
 * @DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
 * interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}
 * }</pre>
 *
 * @see DelayedJobClient
 * @see DelayedJobExecutor
 * @see DelayedJobClientFactory
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayedJobContract {

    /**
     * The unique handler name for this job type. Must match the pattern
     * {@code [a-zA-Z0-9._-]{1,128}}. This name is used as the event bus address
     * segment and the lookup key in the handler registry.
     *
     * @return the handler name
     */
    String name();

    /**
     * Maximum number of attempts including the initial attempt. Must be in the range
     * [1, 1000]. Default is 3.
     *
     * @return the maximum number of attempts
     */
    int maxAttempts() default 3;

    /**
     * The logical queue name. Default is {@code "default"}.
     *
     * @return the queue name
     */
    String queue() default "default";

    /**
     * The job priority. Higher values are claimed first. Default is 0.
     *
     * @return the priority value
     */
    int priority() default 0;
}
