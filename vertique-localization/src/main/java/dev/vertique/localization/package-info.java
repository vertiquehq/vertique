// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vertique localization core: HTTP-agnostic message-source API, locale negotiation, and
 * the {@link dev.vertique.localization.context.LocalizationContext} value type that flows
 * with a request across in-process service calls and durable async boundaries.
 *
 * <p>The module is deliberately split into functional sub-packages:
 * <ul>
 *   <li>{@link dev.vertique.localization.config} — typed config and parser.</li>
 *   <li>{@link dev.vertique.localization.message} — {@code MessageSource} SPI, factory, options,
 *       resolvables, and message-coded SPI.</li>
 *   <li>{@link dev.vertique.localization.locale} — {@code LocaleResolver} SPI covering
 *       RFC 4647 language ranges and RFC 5646 language tag lists.</li>
 *   <li>{@link dev.vertique.localization.context} — the {@code LocalizationContext} record plus its
 *       shipped propagation adapters: a {@code LocalizationContextHolder} facade, an identity
 *       service-dispatch codec, and a durable codec carrying the single {@code localization}
 *       namespace (registered via {@code @IntoSet} in {@code LocalizationModule}). See ADR 0066.</li>
 * </ul>
 *
 * <p>This module never depends on any REST module (enforced by Maven Enforcer); REST-side
 * concerns such as {@code Accept-Language} parsing and {@code ProblemDetail} localization live
 * in the separate {@code vertique-rest-localization} module.
 */
package dev.vertique.localization;
