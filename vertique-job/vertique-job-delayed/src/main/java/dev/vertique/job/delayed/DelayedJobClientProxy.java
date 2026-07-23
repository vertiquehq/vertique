// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * JDK {@link InvocationHandler} that backs a typed {@link DelayedJobClient} proxy.
 *
 * <p>Constructed by {@link DelayedJobClientFactory} and receives all method calls on the
 * proxy. Routes every {@code enqueue} variant to {@link DelayedJobService}, resolving
 * effective configuration values in priority order:
 * <ol>
 *   <li>Per-enqueue {@link DelayedJobOptions} (highest)</li>
 *   <li>Application config under {@code delayedJob.contracts.{name}.*}</li>
 *   <li>{@link DelayedJobContract} annotation defaults</li>
 * </ol>
 *
 * <p>Standard {@link Object} methods ({@code toString}, {@code hashCode}, {@code equals}) are
 * handled directly without delegating to the job service.
 */
class DelayedJobClientProxy implements InvocationHandler {

    // --- State ---

    private final DelayedJobService jobService;
    private final String handlerName;
    private final int effectiveMaxAttempts;
    private final String effectiveQueue;
    private final int effectivePriority;

    /**
     * Creates a new proxy handler.
     *
     * @param jobService     the service used to enqueue jobs
     * @param annotation     the contract annotation providing name and annotation-level defaults
     * @param contractConfig the per-contract config section from the application config;
     *                       values here override annotation defaults
     */
    DelayedJobClientProxy(DelayedJobService jobService, DelayedJobContract annotation, JsonObject contractConfig) {
        this.jobService = jobService;
        this.handlerName = annotation.name();
        // Config values take precedence over annotation values
        this.effectiveMaxAttempts = contractConfig.getInteger("maxAttempts", annotation.maxAttempts());
        this.effectiveQueue = contractConfig.getString("queue", annotation.queue());
        this.effectivePriority = contractConfig.getInteger("priority", annotation.priority());
    }

    // --- InvocationHandler ---

    /**
     * Dispatches a proxy method invocation to the appropriate handler.
     *
     * @param proxy  the proxy instance
     * @param method the interface method being invoked
     * @param args   the arguments passed to the method
     * @return the result of dispatching the method, typically a {@link Future}
     * @throws Throwable if the underlying invocation fails
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        if ("enqueue".equals(method.getName())) {
            return handleEnqueue(method, args);
        }

        throw new UnsupportedOperationException(
                "Method " + method.getName() + " is not supported on the delayed job client proxy");
    }

    // --- Enqueue Dispatch ---

    /**
     * Routes an {@code enqueue} call to {@link DelayedJobService} by inspecting the parameter
     * types of the invoked method to determine which overload was called.
     *
     * @param method the enqueue method being invoked
     * @param args   the arguments passed; {@code args[0]} is always the payload
     * @return a {@link Future} of the job execution ID
     */
    private Future<UUID> handleEnqueue(Method method, Object[] args) {
        Object payload = args[0];
        Class<?>[] paramTypes = method.getParameterTypes();

        Instant runAt = null;
        SqlClient tx = null;
        DelayedJobOptions options = null;

        // Inspect remaining arguments by declared parameter type to identify overload.
        // Using paramTypes (not instanceof) so that null arguments are treated as absent
        // rather than falling through to the error branch.
        for (int i = 1; i < paramTypes.length; i++) {
            Object arg = args[i];
            Class<?> paramType = paramTypes[i];
            if (paramType == Instant.class) {
                runAt = (Instant) arg;
            } else if (paramType == Duration.class) {
                runAt = arg != null ? Instant.now().plus((Duration) arg) : null;
            } else if (SqlClient.class.isAssignableFrom(paramType)) {
                tx = (SqlClient) arg;
            } else if (DelayedJobOptions.class.isAssignableFrom(paramType)) {
                options = (DelayedJobOptions) arg;
            } else {
                throw new UnsupportedOperationException(
                        "Unrecognized enqueue parameter type at index " + i + ": " + paramType.getName());
            }
        }

        // Apply per-enqueue overrides on top of effective contract defaults
        String queue = effectiveQueue;
        int priority = effectivePriority;
        int maxAttempts = effectiveMaxAttempts;
        String jobId = null;
        DurableMetadata premergedMetadata = null;

        if (options != null) {
            if (options.runAt() != null) {
                runAt = options.runAt();
            }
            if (options.queue() != null) {
                queue = options.queue();
            }
            if (options.priority() != null) {
                priority = options.priority();
            }
            if (options.maxAttempts() != null) {
                maxAttempts = options.maxAttempts();
            }
            jobId = options.jobId();
            premergedMetadata = options.premergedMetadata();
        }

        DelayedJob.DelayedJobBuilder jobBuilder = DelayedJob.builder()
                .handler(handlerName)
                .payload(payload)
                .runAt(runAt)
                .queue(queue)
                .priority(priority)
                .maxAttempts(maxAttempts)
                .jobId(jobId);

        // When premergedMetadata is non-null, the caller has already captured and merged durable
        // context. Thread the map onto the job and route to the no-recapture enqueue variant so
        // mergeCaptured is not invoked a second time (which would trigger collision detection on
        // keys already present in the map).
        if (premergedMetadata != null) {
            DelayedJob job = jobBuilder.metadata(premergedMetadata).build();
            if (tx != null) {
                return jobService.enqueuePremerged(job, tx);
            }
            for (int i = 1; i < paramTypes.length; i++) {
                if (SqlClient.class.isAssignableFrom(paramTypes[i]) && args[i] == null) {
                    return Future.failedFuture(new NullPointerException(
                            "SqlClient argument must not be null on transactional enqueue overload"));
                }
            }
            return jobService.enqueuePremerged(job);
        }

        DelayedJob job = jobBuilder.build();

        if (tx != null) {
            return jobService.enqueue(job, tx);
        }
        // If the declared signature includes SqlClient but the value is null, that's a caller bug —
        // fail fast rather than silently degrading from transactional to non-transactional.
        for (int i = 1; i < paramTypes.length; i++) {
            if (SqlClient.class.isAssignableFrom(paramTypes[i]) && args[i] == null) {
                return Future.failedFuture(new NullPointerException(
                        "SqlClient argument must not be null on transactional enqueue overload"));
            }
        }
        return jobService.enqueue(job);
    }

    // --- Object Method Handling ---

    /**
     * Handles standard {@link Object} methods without delegating to the job service.
     *
     * @param proxy  the proxy instance
     * @param method the {@code Object} method being invoked
     * @param args   the arguments
     * @return the appropriate result for the given Object method
     */
    private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "DelayedJobClient[" + handlerName + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Object method not supported: " + method.getName());
        };
    }
}
