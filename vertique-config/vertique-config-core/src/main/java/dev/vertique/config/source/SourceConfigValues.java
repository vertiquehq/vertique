// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Utility class providing shared config-field parsing helpers for {@link ConfigPropertySourceFactory}
 * implementations.
 *
 * <p>All helpers follow the same contract: return a validated value when the field is present and
 * valid, apply a {@code defaultValue} when the field is absent, and throw a
 * {@link ConfigPropertySourceException} naming the source and the field when the value is invalid
 * (zero, negative, or the wrong type). This is the shared implementation of the
 * {@code parsePositiveInt} pattern that was previously duplicated across the AWS Secrets, Azure Key
 * Vault, and Vault property-source factories.
 *
 * <p>This class is {@code final} with a private constructor — all methods are static utilities.
 */
public final class SourceConfigValues {

    // --- Construction ---

    /** Not instantiable. */
    private SourceConfigValues() {}

    // --- Positive integer ---

    /**
     * Reads an integer field from {@code config} and validates that it is a positive (&gt; 0)
     * integer, returning {@code defaultValue} when the field is absent.
     *
     * <p>Guards against {@link ClassCastException} when a non-integer JSON value is present.
     *
     * <ul>
     *   <li>Field absent → return {@code defaultValue} unchanged.</li>
     *   <li>Field present but not numeric (e.g. {@code "not-a-number"}) →
     *       {@link ConfigPropertySourceException} whose message contains the field name.</li>
     *   <li>Field present and numeric but &lt;= 0 → {@link ConfigPropertySourceException} whose
     *       message contains the field name and the actual value.</li>
     *   <li>Field present and &gt; 0 → return the validated value.</li>
     * </ul>
     *
     * @param sourceName   the source instance name; included in error messages
     * @param config       the JSON object to read from
     * @param field        the config field name
     * @param defaultValue the value to use when {@code field} is absent
     * @return the validated positive integer, or {@code defaultValue} when the field is absent
     * @throws ConfigPropertySourceException if the field is non-integer or not positive
     */
    public static int positiveInt(String sourceName, JsonObject config, String field, int defaultValue) {
        Object raw = config.getValue(field);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number num)) {
            throw new ConfigPropertySourceException(
                    sourceName,
                    "field '" + field + "' must be an integer; got: "
                            + raw.getClass().getSimpleName());
        }
        // Normalise to BigDecimal so that BigDecimal, BigInteger, Double, Float, Long, and
        // Integer are all handled uniformly. Using new BigDecimal(value.toString()) avoids
        // the floating-point representation noise of BigDecimal.valueOf(double).
        BigDecimal bd;
        try {
            bd = new BigDecimal(num.toString());
        } catch (NumberFormatException e) {
            // Should not happen for standard Number types, but guard defensively.
            throw new ConfigPropertySourceException(
                    sourceName, "field '" + field + "' could not be parsed as a number; got: " + num);
        }
        // Reject any fractional value (e.g. 1.5, 5000.5).
        if (bd.stripTrailingZeros().scale() > 0) {
            throw new ConfigPropertySourceException(
                    sourceName, "field '" + field + "' must be an exact integer (no fractional part); got: " + num);
        }
        // Reject values outside int range (e.g. BigInteger 2^40, Long 3_000_000_000).
        BigInteger bigInt = bd.toBigIntegerExact();
        if (bigInt.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || bigInt.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
            throw new ConfigPropertySourceException(
                    sourceName,
                    "field '"
                            + field
                            + "' value "
                            + bigInt
                            + " is out of int range ["
                            + Integer.MIN_VALUE
                            + ", "
                            + Integer.MAX_VALUE
                            + "]");
        }
        int value = bigInt.intValueExact();
        if (value <= 0) {
            throw new ConfigPropertySourceException(
                    sourceName, "field '" + field + "' must be a positive integer (> 0); got: " + value);
        }
        return value;
    }
}
