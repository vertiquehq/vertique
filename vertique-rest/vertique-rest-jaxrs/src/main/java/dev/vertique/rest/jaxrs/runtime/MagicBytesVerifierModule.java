// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;

/**
 * Opt-in Dagger module contributing the built-in magic-bytes verifier.
 *
 * <p>This module is intentionally not included by the framework's default component. Applications
 * that want leading-byte verification add it explicitly alongside {@code RestModule}.
 */
@Module
public abstract class MagicBytesVerifierModule {

    /**
     * Contributes the package-private built-in implementation through the public verifier SPI.
     *
     * @param vertx the Vert.x runtime used for asynchronous file reads
     * @return the built-in magic-bytes verifier
     */
    @Provides
    @Singleton
    @IntoSet
    static FileContentVerifier magicBytes(Vertx vertx) {
        return new MagicBytesFileContentVerifier(vertx);
    }
}
