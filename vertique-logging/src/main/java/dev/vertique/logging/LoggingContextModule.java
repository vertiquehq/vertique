// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;

/**
 * Dagger module that wires MDC propagation into the context-propagation substrate.
 *
 * <p>Provides the built-in {@code MDCContext} service-dispatch encoder and decoder into
 * the substrate's {@code ServiceDispatchContextEncoder/Decoder} multibinding sets so MDC
 * entries propagate through the same carrier pipeline as all other typed context values.
 *
 * <p>This module replaces the MDC providers that previously lived in
 * {@code ContextRuntimeModule} (in {@code vertique-context}). The substrate runtime no longer
 * registers MDC-specific bindings — feature ownership lives in the logging module.
 *
 * <p>AppComponents that want MDC service-dispatch propagation MUST include
 * {@code LoggingContextModule.class} alongside their existing {@code ContextRuntimeModule.class}
 * inclusion.
 */
@Module
public abstract class LoggingContextModule {

    /**
     * Provides the built-in MDC service-dispatch encoder via
     * {@link MDCContexts#serviceDispatchEncoder()}.
     *
     * @return the encoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextEncoder<?> mdcContextEncoder() {
        return MDCContexts.serviceDispatchEncoder();
    }

    /**
     * Provides the built-in MDC service-dispatch decoder via
     * {@link MDCContexts#serviceDispatchDecoder()}.
     *
     * @return the decoder instance
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextDecoder<?> mdcContextDecoder() {
        return MDCContexts.serviceDispatchDecoder();
    }
}
