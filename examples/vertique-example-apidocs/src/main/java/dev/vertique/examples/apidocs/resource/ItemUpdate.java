// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs.resource;

import jakarta.validation.constraints.Pattern;

/**
 * The request body of a catalog item update.
 *
 * <p>The validation gate enforces the {@link Pattern} on every request, and the published document
 * states it as the property's {@code pattern}. The pattern is anchored at both ends because the
 * gate accepts a value when the pattern matches any part of it. It ends with
 * {@code (?![\s\S])} rather than {@code $}, because {@code $} also matches before a final line
 * terminator.
 *
 * @param name the new item name: when present, 1 to 64 letters, digits, or spaces
 */
public record ItemUpdate(
        @Pattern(regexp = "^[A-Za-z0-9 ]{1,64}(?![\\s\\S])") String name) {}
