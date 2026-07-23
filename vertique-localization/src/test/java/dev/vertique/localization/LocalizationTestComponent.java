// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.locale.LocaleResolver;
import dev.vertique.localization.message.MessageSourceFactory;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger test component that wires {@link LocalizationModule} for boot verification.
 *
 * <p>Declared as a top-level type (not nested) to avoid javac annotation-processor
 * symbol-completion order issues with nested {@code @Component} interfaces on some JDK versions.
 */
@Singleton
@Component(
        modules = {
            LocalizationModule.class,
            ConfigParsingModule.class,
            LocalizationModuleWiringTest.TestSupportModule.class
        })
interface LocalizationTestComponent {

    /**
     * Provides the resolved {@link LocalizationConfig}.
     *
     * @return the localization configuration
     */
    LocalizationConfig config();

    /**
     * Provides the {@link LocaleResolver}.
     *
     * @return the locale resolver
     */
    LocaleResolver resolver();

    /**
     * Provides the {@link MessageSourceFactory}.
     *
     * @return the message source factory
     */
    MessageSourceFactory factory();

    /**
     * Provides the set of all {@link ServiceDispatchContextEncoder} bindings.
     *
     * @return service-dispatch context encoders
     */
    Set<ServiceDispatchContextEncoder<?>> serviceDispatchEncoders();

    /**
     * Provides the set of all {@link ServiceDispatchContextDecoder} bindings.
     *
     * @return service-dispatch context decoders
     */
    Set<ServiceDispatchContextDecoder<?>> serviceDispatchDecoders();

    /**
     * Provides the set of all {@link DurableContextMetadataEncoder} bindings.
     *
     * @return durable context metadata encoders
     */
    Set<DurableContextMetadataEncoder<?>> durableEncoders();

    /**
     * Provides the set of all {@link DurableContextMetadataDecoder} bindings.
     *
     * @return durable context metadata decoders
     */
    Set<DurableContextMetadataDecoder<?>> durableDecoders();
}
