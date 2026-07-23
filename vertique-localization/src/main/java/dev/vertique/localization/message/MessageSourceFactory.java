// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

/**
 * SPI factory for creating {@link MessageSource} instances backed by a specific resource bundle.
 *
 * <p>Implementations are application-scoped and are injected via constructor injection where
 * needed. The framework does not provide a default {@code MessageSourceFactory} binding;
 * the application (or a higher-level {@code vertique-rest-localization} integration) registers
 * a concrete implementation (FR-LOC-011).
 *
 * <h2>Usage patterns</h2>
 * <pre>{@code
 * // Simple: by bundle base name
 * MessageSource source = factory.create("messages");
 *
 * // Advanced: with explicit fallbacks and ClassLoader
 * MessageSource source = factory.create(
 *     MessageSourceOptions.builder()
 *         .basename("customer-messages")
 *         .fallbackBasename("common-messages")
 *         .classLoader(getClass().getClassLoader())
 *         .build());
 * }</pre>
 *
 * @see MessageSource
 * @see MessageSourceOptions
 */
public interface MessageSourceFactory {

    /**
     * Creates a {@link MessageSource} backed by the resource bundle identified by
     * {@code basename}.
     *
     * @param basename the resource bundle base name (e.g. {@code "messages"} for
     *                 {@code messages.properties}); must not be {@code null} or blank
     * @return a new {@link MessageSource} instance; never {@code null}
     * @throws IllegalArgumentException if {@code basename} is {@code null} or blank
     */
    MessageSource create(String basename);

    /**
     * Creates a {@link MessageSource} using the configuration encapsulated in
     * {@code options}.
     *
     * @param options the resolved configuration describing the primary bundle, fallback bundles,
     *                optional class loader override, and optional caller hint; must not be
     *                {@code null}
     * @return a new {@link MessageSource} instance; never {@code null}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    MessageSource create(MessageSourceOptions options);
}
