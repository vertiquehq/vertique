// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import dev.vertique.localization.config.LocalizationConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * Default implementation of {@link MessageSourceFactory} that creates immutable
 * {@link DefaultMessageSource} instances from the module's {@link LocalizationConfig}.
 *
 * <p>This factory is a {@link Singleton} and captures only immutable configuration; it is
 * therefore safe to call from multiple threads. Each invocation of {@link #create(String)} or
 * {@link #create(MessageSourceOptions)} returns a fresh, independent {@link MessageSource} instance
 * (FR-LOC-060).
 *
 * <h2>ClassLoader precedence</h2>
 * <ol>
 *   <li>{@code options.classLoader()} — explicit override; highest priority.</li>
 *   <li>{@code options.caller().getClassLoader()} — caller-class classloader, when the caller is
 *       non-null and its classloader is non-null (bootstrap-loaded classes such as
 *       {@code Object.class} return {@code null} from {@link Class#getClassLoader()} and are
 *       skipped).</li>
 *   <li>{@link Thread#getContextClassLoader()} — thread context classloader, when non-null.</li>
 *   <li>{@link ClassLoader#getSystemClassLoader()} — last-resort safety net.</li>
 * </ol>
 *
 * @see MessageSourceFactory
 * @see DefaultMessageSource
 * @see LocalizationConfig
 */
@Singleton
public class DefaultMessageSourceFactory implements MessageSourceFactory {

    /** Immutable module configuration injected at construction. */
    private final LocalizationConfig config;

    /**
     * Constructs a factory with the given localization configuration.
     *
     * @param config the module configuration providing default locale, TTL, and message-format
     *               settings; must not be {@code null}
     */
    @Inject
    public DefaultMessageSourceFactory(LocalizationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Creates a {@link MessageSource} backed by the resource bundle with the given
     * {@code basename} (FR-LOC-060).
     *
     * <p>This is sugar for
     * {@code create(MessageSourceOptions.builder().basename(basename).build())}.
     *
     * @param basename the resource bundle base name; must not be {@code null} or blank
     * @return a new {@link MessageSource}; never {@code null}
     * @throws IllegalArgumentException if {@code basename} is {@code null} or blank
     */
    @Override
    public MessageSource create(String basename) {
        return create(MessageSourceOptions.builder().basename(basename).build());
    }

    /**
     * Creates a {@link MessageSource} using the configuration encapsulated in {@code options}.
     *
     * <p>The classloader is resolved via the precedence chain documented in this class's javadoc.
     *
     * @param options the resolved configuration; must not be {@code null}
     * @return a new {@link MessageSource}; never {@code null}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    @Override
    public MessageSource create(MessageSourceOptions options) {
        Objects.requireNonNull(options, "options");
        ClassLoader classLoader = resolveClassLoader(options);
        return new DefaultMessageSource(config, options, classLoader);
    }

    // --- ClassLoader resolution ---

    /**
     * Resolves the effective {@link ClassLoader} for the given options, following the four-rung
     * precedence chain.
     *
     * @param options the source options providing optional explicit classloader and caller hint
     * @return the resolved classloader; never {@code null}
     */
    private static ClassLoader resolveClassLoader(MessageSourceOptions options) {
        // Rung 1: explicit classLoader override
        if (options.classLoader() != null) {
            return options.classLoader();
        }

        // Rung 2: caller's classloader (skip if caller is null or bootstrap-loaded)
        Class<?> caller = options.caller();
        if (caller != null) {
            ClassLoader callerCl = caller.getClassLoader();
            if (callerCl != null) {
                return callerCl;
            }
        }

        // Rung 3: thread context classloader
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            return tccl;
        }

        // Rung 4: system classloader — last resort
        return ClassLoader.getSystemClassLoader();
    }
}
