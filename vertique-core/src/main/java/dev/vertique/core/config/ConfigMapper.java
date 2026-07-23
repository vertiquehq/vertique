// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;

/**
 * Dagger {@link Qualifier} marking an {@link com.fasterxml.jackson.databind.ObjectMapper} an
 * application supplies to customize config parsing.
 *
 * <p>When present, the framework re-layers its mandatory modules and lenient policy over the
 * supplied mapper (it never replaces them) and uses the result as the config mapper backing the
 * injected {@link ConfigParser}. The qualified mapper is therefore <strong>dedicated to and owned
 * by config parsing</strong> — the framework finalizes it in place before first use, so an
 * application must not share the same instance concurrently for other purposes.
 *
 * <p>The override is bound through {@code @BindsOptionalOf}; absent any application-supplied
 * {@code @ConfigMapper} mapper, the framework uses its lenient default.
 */
@Qualifier
@Retention(RUNTIME)
public @interface ConfigMapper {}
