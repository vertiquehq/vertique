// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service implementation method as the handler for a named delayed job type.
 *
 * <p>The annotated method is discovered at startup by {@link DelayedJobHandlerRegistrar}, which
 * scans service implementations via the {@link dev.vertique.services.ServiceContractRegistry}.
 * The handler name in the annotation maps to the event bus address {@code job.delayed.<name>}.
 *
 * <p>Place this annotation on the <em>implementation</em> method, not on the contract interface.
 *
 * <p>Example:
 * <pre>{@code
 * @DelayedJobHandlerMethod("send-welcome-email")
 * public Future<Void> sendWelcomeEmail(WelcomeEmailPayload payload, JobContext ctx) {
 *     ctx.logger().info("Sending welcome email to {}", payload.email());
 *     return emailService.send(payload.email());
 * }
 * }</pre>
 *
 * <p>To enqueue a job for this handler:
 * <pre>{@code
 * delayedJobService.enqueue(DelayedJob.builder()
 *     .handler("send-welcome-email")
 *     .payload(new WelcomeEmailPayload(userId, email))
 *     .build());
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayedJobHandlerMethod {

    /**
     * The handler name. Used as the event bus address suffix: {@code job.delayed.<name>}.
     * Must be unique across all registered handlers.
     *
     * @return the handler name (must not be blank)
     */
    String value();
}
