// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import java.util.Set;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {\ dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * Dagger module that wires the context-propagation substrate.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link ContextHolder} bound to {@link DefaultContextHolder}.
 *   <li>Empty multibinding sets for all four SPI families (service-dispatch encoders/decoders and
 *       durable encoders/decoders) so modules that do not contribute any SPI can still compile.
 *   <li>Built-in {@link DurablePropagationMetadata} service-dispatch encoder and decoder.
 * </ul>
 *
 * <p>MDC service-dispatch propagation is provided separately by
 * {@code dev.vertique.logging.LoggingContextModule} in {@code vertique-logging}. Applications
 * that want MDC entries to propagate through the carrier pipeline must include
 * {@code LoggingContextModule} alongside this module.
 *
 * <p>Consumer modules contribute SPI implementations via {@code @Provides @IntoSet} or
 * {@code @Binds @IntoSet} bindings pointing to this module's multibinding sets.
 */
@Module
public abstract class ContextRuntimeModule {

    /**
     * Binds the {@link ContextHolder} SPI to the {@link DefaultContextHolder} implementation.
     *
     * @param impl the default implementation
     * @return the bound {@link ContextHolder}
     */
    @Binds
    abstract ContextHolder contextHolder(DefaultContextHolder impl);

    /**
     * Declares the empty multibinding set for service-dispatch context encoders.
     *
     * @return the empty set of encoders
     */
    @Multibinds
    abstract Set<ServiceDispatchContextEncoder<?>> serviceDispatchContextEncoders();

    /**
     * Declares the empty multibinding set for service-dispatch context decoders.
     *
     * @return the empty set of decoders
     */
    @Multibinds
    abstract Set<ServiceDispatchContextDecoder<?>> serviceDispatchContextDecoders();

    /**
     * Declares the empty multibinding set for durable context metadata encoders.
     *
     * @return the empty set of durable encoders
     */
    @Multibinds
    abstract Set<DurableContextMetadataEncoder<?>> durableContextMetadataEncoders();

    /**
     * Declares the empty multibinding set for durable context metadata decoders.
     *
     * @return the empty set of durable decoders
     */
    @Multibinds
    abstract Set<DurableContextMetadataDecoder<?>> durableContextMetadataDecoders();

    /**
     * Declares the empty multibinding set for first-ingress context initializers.
     *
     * @return the empty set of initializers
     */
    @Multibinds
    abstract Set<InboundContextInitializer> inboundContextInitializers();

    /**
     * Provides the built-in {@link DurablePropagationMetadata} service-dispatch encoder into the
     * encoder multibinding set (FR-CTX-143).
     *
     * @return the encoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextEncoder<?> durablePropagationMetadataEncoder() {
        return new DurablePropagationMetadataServiceDispatchEncoder();
    }

    /**
     * Provides the built-in {@link DurablePropagationMetadata} service-dispatch decoder into the
     * decoder multibinding set (FR-CTX-143).
     *
     * @return the decoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextDecoder<?> durablePropagationMetadataDecoder() {
        return new DurablePropagationMetadataServiceDispatchDecoder();
    }
}
