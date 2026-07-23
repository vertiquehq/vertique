// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Carries contextual metadata about a string value being processed by a canonicalizer or sanitizer.
 *
 * <p>This record is passed to every {@link Canonicalizer} and {@link Sanitizer} invocation,
 * allowing implementations to make context-aware decisions. For example, a canonicalizer may
 * apply stricter normalization for {@link InputLocation#HEADER} values than for
 * {@link InputLocation#BODY} fields.
 *
 * @param location    where in the HTTP request the value originates
 * @param path        dot-separated property path within the object graph (e.g., {@code "address.city"});
 *                    for top-level parameters this equals {@code logicalName}
 * @param logicalName the name of the parameter or field being processed
 * @param ownerType   the declaring class of the field or parameter being processed
 */
public record InputValueContext(InputLocation location, String path, String logicalName, Class<?> ownerType) {}
