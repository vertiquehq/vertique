// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Context-propagation types for {@code LocalizationContext}.
 *
 * <p>This package contains:
 * <ul>
 *   <li>{@code LocalizationContext} — immutable record carrying request-scoped locale, time zone,
 *       and optional currency/calendar/numbering-system overrides plus low-cardinality
 *       {@code localeSource}/{@code zoneSource} diagnostic fields.</li>
 *   <li>{@code LocalizationContextHolder} — typed facade over {@code ContextHolder} for
 *       {@code LocalizationContext} bindings (eliminates repetitive {@code Class<>} token
 *       at call sites).</li>
 *   <li>{@code LocalizationContextServiceDispatchEncoder} /
 *       {@code LocalizationContextServiceDispatchDecoder} — identity pass-through codec pair
 *       for in-process service-dispatch propagation.</li>
 *   <li>{@code LocalizationDurableNamespace} — constant definitions for the {@code localization}
 *       durable-metadata namespace.</li>
 *   <li>{@code LocalizationContextDurableEncoder} /
 *       {@code LocalizationContextDurableDecoder} — durable-boundary codec pair that serialises
 *       and deserialises {@code LocalizationContext} under the {@code localization} namespace,
 *       with strict BCP 47 locale parsing and supported-locale validation on decode.</li>
 * </ul>
 *
 * <p>The encoder/decoder pairs are contributed to the Dagger multibinding sets declared by
 * {@code ContextRuntimeModule} via {@code @Provides @IntoSet} methods in
 * {@code LocalizationModule}.
 */
package dev.vertique.localization.context;
