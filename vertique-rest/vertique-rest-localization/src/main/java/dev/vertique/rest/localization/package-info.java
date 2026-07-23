// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Inbound REST locale negotiation for the Vertique framework.
 *
 * <p>This module binds a typed {@link dev.vertique.localization.context.LocalizationContext} into the
 * request-scoped {@code ContextHolder} at REST inbound, so handler code and downstream propagation see
 * one consistent locale. The bound context in {@code ContextHolder} is the single source of truth;
 * handler code reads it via {@code ContextHolder.current(LocalizationContext.class)}. Resolution runs
 * through an ordered {@code LocaleSource} chain: the built-in
 * {@code AcceptLanguageLocaleSource} negotiates the HTTP {@code Accept-Language} header, applications
 * contribute additional pre-auth sources (cookie, query parameter, custom header) by adding a
 * {@code @IntoSet LocaleSource} binding, and the configured {@code defaultLocale} is the guaranteed
 * last resort.
 *
 * <p>The module is server-only and opt-in: it depends on {@code vertique-rest-core} and
 * {@code vertique-localization} but never on {@code vertique-rest-jaxrs} or {@code vertique-rest-client}.
 * Applications that do not install {@code RestLocalizationModule} see no behavior change.
 *
 * <p>Outbound {@code Accept-Language} emission and framework-driven response-body localization
 * (ProblemDetail/validation) are out of scope for this module's v1; see
 * the module documentation.
 */
package dev.vertique.rest.localization;
