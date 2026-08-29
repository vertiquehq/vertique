// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

/**
 * Fully-qualified class name constants for framework types referenced by
 * {@link ServiceContractProcessor} and its sub-components.
 *
 * <p>Using string constants rather than class literals avoids compile-time dependencies on the
 * runtime modules, keeping the processor jar's classpath minimal. All names must match the
 * exact binary names used in the target modules.
 */
public final class ServiceAnnotations {

    // --- Service contract annotations ---

    /** FQN of {@code dev.vertique.services.ServiceContract}. */
    public static final String SERVICE_CONTRACT = "dev.vertique.services.ServiceContract";

    /** FQN of {@code dev.vertique.services.ServiceOperation}. */
    public static final String SERVICE_OPERATION = "dev.vertique.services.ServiceOperation";

    /** FQN of {@code dev.vertique.services.OneWay}. */
    public static final String ONE_WAY = "dev.vertique.services.OneWay";

    /** FQN of {@code dev.vertique.services.ServiceHandler}. */
    public static final String SERVICE_HANDLER = "dev.vertique.services.ServiceHandler";

    // --- Parameter classification types ---

    /** FQN of {@code dev.vertique.core.eventbus.DispatchContextValue}. */
    public static final String DISPATCH_CONTEXT_VALUE = "dev.vertique.core.eventbus.DispatchContextValue";

    /** FQN of {@code dev.vertique.security.SecurityContext}. */
    public static final String SECURITY_CONTEXT = "dev.vertique.security.SecurityContext";

    /** FQN of {@code dev.vertique.core.eventbus.DispatchEnvelope}. */
    public static final String DISPATCH_ENVELOPE = "dev.vertique.core.eventbus.DispatchEnvelope";

    // --- Future type ---

    /** FQN of {@code io.vertx.core.Future}. */
    public static final String FUTURE = "io.vertx.core.Future";

    // --- Resilience annotations ---

    /** FQN of {@code dev.vertique.resilience.annotation.Timeout}. */
    public static final String TIMEOUT = "dev.vertique.resilience.annotation.Timeout";

    /** FQN of {@code dev.vertique.resilience.annotation.CircuitBreaker}. */
    public static final String CIRCUIT_BREAKER = "dev.vertique.resilience.annotation.CircuitBreaker";

    /** FQN of {@code dev.vertique.resilience.annotation.Retry}. */
    public static final String RETRY = "dev.vertique.resilience.annotation.Retry";

    // --- NoAutoWire ---

    /** FQN of {@code dev.vertique.codegen.NoAutoWire}. */
    public static final String NO_AUTO_WIRE = "dev.vertique.codegen.NoAutoWire";

    // --- Conditional registration (CG-011) ---

    /** FQN of {@code dev.vertique.codegen.ConditionalOnProperty}. */
    public static final String CONDITIONAL_ON_PROPERTY = "dev.vertique.codegen.ConditionalOnProperty";

    /** FQN of {@code dev.vertique.codegen.ConditionalOnProperties}. */
    public static final String CONDITIONAL_ON_PROPERTIES = "dev.vertique.codegen.ConditionalOnProperties";

    // --- Client-proxy reserved-identifier check ---

    /** FQN of {@code dev.vertique.services.dispatch.ServiceMethodMeta}. */
    public static final String SERVICE_METHOD_META = "dev.vertique.services.dispatch.ServiceMethodMeta";

    private ServiceAnnotations() {}
}
