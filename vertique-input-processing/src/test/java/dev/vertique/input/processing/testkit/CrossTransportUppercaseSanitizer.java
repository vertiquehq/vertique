// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing.testkit;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.Locale;

/**
 * Deterministic, transport-agnostic sanitizer used by {@link CrossTransportInputCorpus} (R03, finding
 * #425): brackets and upper-cases every string value it sees (e.g. {@code "alpha"} becomes
 * {@code "[ALPHA]"}).
 *
 * <p>{@link #transform(String)} is the single source of truth for this sanitizer's behavior. {@link
 * #sanitize(String, InputValueContext)} delegates to it, and {@link CrossTransportInputCorpus} computes
 * its published expected output by calling the same static method — never by restating the transform
 * as a second literal that could silently drift from what this class actually does.
 */
public final class CrossTransportUppercaseSanitizer implements Sanitizer {

    /**
     * The pure transform this sanitizer applies.
     *
     * @param value the raw string value; must not be {@code null}
     * @return the bracketed, upper-cased value
     */
    public static String transform(String value) {
        return "[" + value.toUpperCase(Locale.ROOT) + "]";
    }

    @Override
    public String sanitize(String value, InputValueContext context) {
        return transform(value);
    }
}
