// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

/**
 * Enumeration of the three Dagger qualifiers (plus the REST client direct-binding shape) that
 * {@link AutoWireProcessor} generates bindings for.
 *
 * <p>Each constant captures:
 * <ul>
 *   <li>{@link #qualifierFqn} — the fully-qualified annotation name used as the Dagger qualifier
 *       on the generated {@code @Provides} method, or {@code null} for {@link #REST_CLIENTS} which
 *       uses a direct {@code @Singleton} binding rather than a multibinding.</li>
 *   <li>{@link #moduleSimpleName} — the simple class name of the generated {@code @Module}
 *       (e.g., {@code GeneratedKafkaConsumersModule}).</li>
 * </ul>
 *
 * <p>JAX-RS resource binding is owned by {@code vertique-codegen-jaxrs} (CG-010), which generates
 * {@code GeneratedJaxRsResourcesModule} via {@code JaxRsPipelineProcessor}. CG-002 does not emit
 * {@code @JaxRsResources} bindings.
 *
 * <p>Service-contract bindings are owned by {@code vertique-codegen-services} (CG-005), which
 * generates a separate {@code GeneratedServicesModule} populated with
 * {@code @Provides @IntoSet ServiceContractContributor} bindings; CG-002 does not emit them.
 */
public enum Qualifier {
    /**
     * Contributes to {@code @KafkaConsumers Set<Object>} multibinding in {@code KafkaModule}.
     */
    KAFKA_CONSUMERS("dev.vertique.kafka.KafkaConsumers", "GeneratedKafkaConsumersModule"),

    /**
     * Contributes to {@code @DelayedJobs Set<Object>} multibinding in {@code DelayedJobModule}.
     */
    DELAYED_JOBS("dev.vertique.job.delayed.dagger.DelayedJobs", "GeneratedDelayedJobsModule"),

    /**
     * Emits {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory)} — no
     * multibinding qualifier; handled by {@link dev.vertique.codegen.dagger.processor.emit.RestClientModuleEmitter}.
     */
    REST_CLIENTS(null, "GeneratedRestClientsModule");

    /** The fully-qualified annotation class name for the Dagger qualifier, or {@code null}. */
    public final String qualifierFqn;

    /** The simple class name of the generated {@code @Module}. */
    public final String moduleSimpleName;

    Qualifier(String qualifierFqn, String moduleSimpleName) {
        this.qualifierFqn = qualifierFqn;
        this.moduleSimpleName = moduleSimpleName;
    }
}
