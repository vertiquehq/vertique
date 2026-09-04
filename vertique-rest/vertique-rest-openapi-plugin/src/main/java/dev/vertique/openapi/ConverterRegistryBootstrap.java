// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import io.swagger.v3.core.converter.ModelConverters;

/**
 * Initializes swagger-core's process-wide converter registry before the Maven plugin registers
 * application-specific converters.
 *
 * <p>swagger-core's {@link ModelConverters#getInstance(boolean)} lazily creates its singleton
 * without synchronizing the first read/write. The swagger Maven plugin constructs configured
 * converters before registering them, so parallel reactor executions can otherwise initialize
 * different registries and lose one execution's registrations. The monitor is the
 * {@code ModelConverters} class object itself so this guard also coordinates with swagger-core's
 * synchronized overloads and {@link ModelConverters#reset()}.
 */
final class ConverterRegistryBootstrap {

    private ConverterRegistryBootstrap() {}

    /**
     * Ensures the OpenAPI 3.0 converter registry exists before converter registration begins.
     */
    static void initialize() {
        synchronized (ModelConverters.class) {
            ModelConverters.getInstance(false);
        }
    }
}
