// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.job.JobContext;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ServiceContractContributor} that registers typed delayed job executors as service
 * contract entries.
 *
 * <p>At startup, this contributor inspects each {@link DelayedJobExecutor} instance in the
 * {@code @DelayedJobs} multibinding set, resolves its generic type arguments {@code P} (payload)
 * and {@code C} (client contract interface), reads the {@link DelayedJobContract} annotation
 * from {@code C}, and builds a {@link ContractEntry} using the
 * {@link ServiceContractEntries} builder.
 *
 * <p>The resulting event bus address for the {@code execute} operation follows the canonical
 * delayed-job namespace pattern {@code jobs/delayed/{name}/execute}, where {@code name} is the
 * value of {@link DelayedJobContract#name()}. This address is used by the
 * {@link DelayedJobHandlerRegistrar} to route job dispatch to the correct executor.
 *
 * <p>Validation performed at startup:
 * <ul>
 *   <li>Each executor must have concrete type arguments for {@code P} and {@code C}.</li>
 *   <li>The client contract interface {@code C} must carry {@link DelayedJobContract}.</li>
 *   <li>The contract name must match {@code [a-zA-Z0-9._-]{1,128}}.</li>
 *   <li>An {@code execute(P, JobContext)} method (or its type-erased form) must exist on the
 *       executor class.</li>
 * </ul>
 */
@Slf4j
public class DelayedJobContractContributor implements ServiceContractContributor {

    // --- Constants ---

    /**
     * Pattern that every {@link DelayedJobContract#name()} must match.
     */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");

    // --- Dependencies ---

    private final Set<Object> executors;

    /**
     * Creates a contributor with the given set of executor instances.
     *
     * @param executors the set of {@link DelayedJobExecutor} instances to register
     */
    public DelayedJobContractContributor(Set<Object> executors) {
        this.executors = executors;
    }

    // --- ServiceContractContributor ---

    /**
     * Produces one {@link ContractEntry} per registered executor.
     *
     * <p>All executors are processed before any error is thrown. If multiple executors fail
     * validation, all error messages are collected and thrown together as a single
     * {@link IllegalStateException}.
     *
     * @param config the application configuration (used for deployment option resolution)
     * @return list of contract entries; never {@code null}
     * @throws IllegalStateException if any executor fails validation, with all error messages joined
     */
    @Override
    public List<ContractEntry<?>> contribute(JsonObject config) {
        List<ContractEntry<?>> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Object executor : executors) {
            try {
                entries.add(buildEntry(executor, config));
            } catch (IllegalStateException e) {
                errors.add(e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Delayed job executor registration failed with " + errors.size()
                    + " error(s):\n" + String.join("\n", errors));
        }

        return entries;
    }

    // --- Entry Building ---

    /**
     * Builds a single {@link ContractEntry} for the given executor instance.
     *
     * @param executor the {@link DelayedJobExecutor} instance
     * @param config   the application configuration
     * @return the built contract entry
     * @throws IllegalStateException if validation fails
     */
    private ContractEntry<?> buildEntry(Object executor, JsonObject config) {
        Class<?> executorClass = executor.getClass();

        // Verify the object actually implements DelayedJobExecutor before type resolution
        if (!(executor instanceof DelayedJobExecutor<?, ?>)) {
            throw new IllegalStateException(executorClass.getName()
                    + " does not implement DelayedJobExecutor — check your @DelayedJobs @IntoSet binding");
        }

        // Resolve C (client contract interface) from DelayedJobExecutor<P, C> — type arg index 1
        Class<?> contractInterface = TypeResolver.resolveTypeArgument(executorClass, DelayedJobExecutor.class, 1);
        if (contractInterface == null) {
            throw new IllegalStateException("Cannot resolve contract type parameter C from "
                    + executorClass.getName()
                    + " — ensure the class specifies concrete type arguments for"
                    + " DelayedJobExecutor<P, C>");
        }

        // Get @DelayedJobContract from C
        DelayedJobContract annotation = contractInterface.getAnnotation(DelayedJobContract.class);
        if (annotation == null) {
            throw new IllegalStateException("Contract interface "
                    + contractInterface.getName()
                    + " must be annotated with @DelayedJobContract");
        }

        // Validate name
        String name = annotation.name();
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid @DelayedJobContract name '"
                    + name
                    + "' on "
                    + contractInterface.getName()
                    + ". Must match [a-zA-Z0-9._-]{1,128}");
        }

        // Validate maxAttempts range
        int maxAttempts = annotation.maxAttempts();
        if (maxAttempts < 1 || maxAttempts > 1000) {
            throw new IllegalStateException("@DelayedJobContract maxAttempts must be between 1 and 1000, got "
                    + maxAttempts + " on " + contractInterface.getName());
        }

        // Resolve P (payload type) from DelayedJobExecutor<P, C> — type arg index 0
        Class<?> payloadType = TypeResolver.resolveTypeArgument(executorClass, DelayedJobExecutor.class, 0);
        if (payloadType == null) {
            throw new IllegalStateException("Cannot resolve payload type parameter P from " + executorClass.getName());
        }

        // Find execute method, with type-erasure fallback
        Method executeMethod = findExecuteMethod(executorClass, payloadType);

        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(executorClass)
                .serviceInstance(executor)
                .namespace("delayed-job")
                .name(name)
                .operation("execute")
                .address("jobs/delayed/" + name + "/execute")
                .method(executeMethod)
                .payloadType(payloadType)
                .returnType(Void.class)
                .param("payload", ParamSource.PAYLOAD, payloadType)
                .param("ctx", ParamSource.DISPATCH_CONTEXT, JobContext.class)
                .done()
                .deploymentOptions(config, "services", "contracts", "delayed-job", name)
                .build();

        log.info(
                "Registered typed delayed job contract '{}' → {}.execute() [address=jobs/delayed/{}/execute]",
                name,
                executorClass.getSimpleName(),
                name);

        return entry;
    }

    // --- Reflection Helpers ---

    /**
     * Finds the {@code execute} method on the executor class that accepts the given payload type.
     * Falls back to the type-erased {@code Object} signature if the concrete payload signature
     * is not directly present due to type erasure.
     *
     * @param executorClass the executor implementation class
     * @param payloadType   the concrete payload type
     * @return the execute method
     * @throws IllegalStateException if no suitable execute method is found
     */
    private Method findExecuteMethod(Class<?> executorClass, Class<?> payloadType) {
        try {
            return executorClass.getMethod("execute", payloadType, JobContext.class);
        } catch (NoSuchMethodException first) {
            // Type erasure may cause the method to appear with Object payload
            try {
                return executorClass.getMethod("execute", Object.class, JobContext.class);
            } catch (NoSuchMethodException second) {
                throw new IllegalStateException("Cannot find execute method on " + executorClass.getName(), second);
            }
        }
    }
}
