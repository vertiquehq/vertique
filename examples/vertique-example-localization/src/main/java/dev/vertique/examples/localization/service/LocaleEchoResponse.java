// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization.service;

/**
 * Response DTO returned by {@link LocaleEchoService}, carrying the locale information
 * resolved from the inbound request.
 *
 * @param languageTag  the BCP 47 language tag of the resolved locale (e.g. {@code "sv"}, {@code "fi"})
 * @param localeSource the low-cardinality diagnostic label identifying which source resolved the
 *                     locale (e.g. {@code "rest-accept-language"}, {@code "query-param"},
 *                     {@code "default-locale"})
 */
public record LocaleEchoResponse(String languageTag, String localeSource) {}
