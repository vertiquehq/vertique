// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.internal;

import dev.vertique.http.client.VertiqueHttpClientBuilder;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * INTERNAL, framework use only. Switches off behavior of {@link VertiqueHttpClientBuilder} that
 * application code cannot change. This type is outside the compatibility promise of the module.
 */
public final class FrameworkClientOptions {

    private static volatile Consumer<VertiqueHttpClientBuilder> headerValidationDisabler;

    private FrameworkClientOptions() {}

    /**
     * Makes {@code builder} build clients that do not apply the framework's request header rules
     * (invalid characters and the reserved name prefix). Vert.x still rejects invalid header
     * characters when a header is set.
     *
     * @param builder the builder to change
     * @return {@code builder}
     */
    public static VertiqueHttpClientBuilder withoutHeaderValidation(VertiqueHttpClientBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        Consumer<VertiqueHttpClientBuilder> disabler = headerValidationDisabler;
        if (disabler == null) {
            throw new IllegalStateException("The HTTP client builder is not initialized");
        }
        disabler.accept(builder);
        return builder;
    }

    /**
     * Registers the builder's non-public accessor. Called once by {@link VertiqueHttpClientBuilder}
     * when it is initialized; later calls are refused.
     *
     * @param disabler the accessor that turns header validation off for a builder
     */
    public static synchronized void register(Consumer<VertiqueHttpClientBuilder> disabler) {
        Objects.requireNonNull(disabler, "disabler");
        if (headerValidationDisabler != null) {
            throw new IllegalStateException("The accessor is already registered");
        }
        headerValidationDisabler = disabler;
    }
}
