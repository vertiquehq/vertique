// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.config.LocalizationConfigParser;
import dev.vertique.localization.context.LocalizationContextDurableDecoder;
import dev.vertique.localization.context.LocalizationContextDurableEncoder;
import dev.vertique.localization.context.LocalizationContextServiceDispatchDecoder;
import dev.vertique.localization.context.LocalizationContextServiceDispatchEncoder;
import dev.vertique.localization.locale.DefaultLocaleResolver;
import dev.vertique.localization.locale.LocaleResolver;
import dev.vertique.localization.message.DefaultMessageSourceFactory;
import dev.vertique.localization.message.MessageSourceFactory;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger module exposing the substrate-free bindings of {@code vertique-localization}:
 * {@link LocalizationConfig} (parsed from {@code @VertxConfig JsonObject}),
 * {@link LocaleResolver} (RFC 4647/5646 resolution), {@link MessageSourceFactory}
 * (per-application {@code MessageSource} construction), and the context-propagation adapters for
 * {@link dev.vertique.localization.context.LocalizationContext}.
 *
 * <p>The module deliberately does NOT provide a global {@link
 * dev.vertique.localization.message.MessageSource MessageSource} (FR-LOC-011) and does NOT
 * declare a {@code Set<MessageSource>} multibinding (FR-LOC-012). Applications create their
 * own qualified {@code MessageSource} bindings using the factory:
 *
 * <pre>{@code
 * @Qualifier @Retention(RUNTIME)
 * public @interface CustomerMessages {}
 *
 * @Module
 * abstract class AppMessagesModule {
 *     @Provides @Singleton @CustomerMessages
 *     static MessageSource customerMessages(MessageSourceFactory factory) {
 *         return factory.create(MessageSourceOptions.builder()
 *                 .basename("customer-messages")
 *                 .fallbackBasename("common-messages")
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * <p>Context-propagation multibinding contributions (registered into the
 * {@code Set<ServiceDispatchContextEncoder<?>>}, {@code Set<ServiceDispatchContextDecoder<?>>},
 * {@code Set<DurableContextMetadataEncoder<?>>}, and {@code Set<DurableContextMetadataDecoder<?>>}
 * sets declared by {@code ContextRuntimeModule}):
 * <ul>
 *   <li>{@link LocalizationContextServiceDispatchEncoder} — identity pass-through encoder for
 *       in-process service-dispatch propagation.</li>
 *   <li>{@link LocalizationContextServiceDispatchDecoder} — type-checked decoder for
 *       in-process service-dispatch propagation.</li>
 *   <li>{@link LocalizationContextDurableEncoder} — serialises the context to the
 *       {@code localization} namespace in a durable metadata document.</li>
 *   <li>{@link LocalizationContextDurableDecoder} — deserialises the context from the
 *       {@code localization} namespace, validated against the configured supported-locale list.</li>
 * </ul>
 *
 * @see dev.vertique.localization.config.LocalizationConfigParser
 * @see dev.vertique.localization.locale.LocaleResolver
 * @see dev.vertique.localization.message.MessageSourceFactory
 */
@Module
public abstract class LocalizationModule {

    /**
     * Parses the raw application {@link JsonObject} into a validated {@link LocalizationConfig}.
     * The parser is a static utility (not an {@code @Inject} target), so this binding uses a
     * static {@code @Provides} method.
     *
     * @param config the raw application configuration object (provided by {@code VertxModule})
     * @return the validated localization config
     */
    @Provides
    @Singleton
    static LocalizationConfig localizationConfig(@VertxConfig JsonObject config) {
        return LocalizationConfigParser.parse(config);
    }

    /**
     * Binds {@link DefaultLocaleResolver} as the implementation of {@link LocaleResolver}.
     * The implementation carries class-level {@code @Singleton} and an {@code @Inject}
     * constructor so Dagger manages instantiation.
     */
    @Binds
    abstract LocaleResolver localeResolver(DefaultLocaleResolver impl);

    /**
     * Binds {@link DefaultMessageSourceFactory} as the implementation of
     * {@link MessageSourceFactory}.
     */
    @Binds
    abstract MessageSourceFactory messageSourceFactory(DefaultMessageSourceFactory impl);

    // --- Context-propagation multibinding contributions ---

    /**
     * Service-dispatch encoder for {@link dev.vertique.localization.context.LocalizationContext}.
     * Identity pass-through — the immutable record is safe to share across in-process dispatch
     * boundaries by reference.
     *
     * @return the encoder contribution
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextEncoder<?> localizationServiceDispatchEncoder() {
        return new LocalizationContextServiceDispatchEncoder();
    }

    /**
     * Service-dispatch decoder for {@link dev.vertique.localization.context.LocalizationContext}.
     * Reinstates the value from the dispatch-context map into the holder on the receive side.
     *
     * @return the decoder contribution
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextDecoder<?> localizationServiceDispatchDecoder() {
        return new LocalizationContextServiceDispatchDecoder();
    }

    /**
     * Durable metadata encoder for {@link dev.vertique.localization.context.LocalizationContext}.
     * Serialises the context into the {@code localization} namespace of a durable metadata document.
     *
     * @return the durable encoder contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataEncoder<?> localizationDurableEncoder() {
        return new LocalizationContextDurableEncoder();
    }

    /**
     * Durable metadata decoder for {@link dev.vertique.localization.context.LocalizationContext}.
     * Deserialises the context from the {@code localization} namespace, validating the locale
     * against the configured supported-locale list.
     *
     * @param impl the singleton decoder (injected by Dagger)
     * @return the durable decoder contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataDecoder<?> localizationDurableDecoder(LocalizationContextDurableDecoder impl) {
        return impl;
    }
}
