// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Holder for the framework's built-in {@link ParamConverter} registrations covering the FR-015-02
 * target-type set: {@code String}, the primitive and boxed numeric/boolean/char types, the big
 * numerics, {@link UUID}, {@link URI}, and the {@code java.time} family.
 *
 * <p>The {@link #map()} factory produces a fresh, ordered map keyed by the exact target {@link Class}.
 * Both the primitive ({@code int.class}) and boxed ({@code Integer.class}) classes are registered so
 * that an exact-class lookup succeeds for either key. Enum conversion is handled generically by the
 * {@link ParamConverterRegistry} via the {@code Class.isEnum()} synthesis rule, not by an entry here.
 */
final class BuiltinParamConverters {

    private BuiltinParamConverters() {
        // static holder
    }

    /**
     * Builds a fresh, mutable map of the framework built-in converters keyed by exact target type.
     * A new map is returned on each call so the caller (the registry) may overlay application
     * bindings without mutating shared state.
     *
     * @return a new map from target type to its built-in converter
     */
    static Map<Class<?>, ParamConverter<?>> map() {
        Map<Class<?>, ParamConverter<?>> map = new LinkedHashMap<>();

        // --- String (identity) ---
        ParamConverter<String> stringConverter = parsing(Function.identity());
        map.put(String.class, stringConverter);

        // --- Booleans ---
        ParamConverter<Boolean> booleanConverter = parsing(Boolean::parseBoolean);
        map.put(boolean.class, booleanConverter);
        map.put(Boolean.class, booleanConverter);

        // --- Integral primitives + boxed ---
        ParamConverter<Byte> byteConverter = parsing(Byte::parseByte);
        map.put(byte.class, byteConverter);
        map.put(Byte.class, byteConverter);

        ParamConverter<Short> shortConverter = parsing(Short::parseShort);
        map.put(short.class, shortConverter);
        map.put(Short.class, shortConverter);

        ParamConverter<Integer> intConverter = parsing(Integer::parseInt);
        map.put(int.class, intConverter);
        map.put(Integer.class, intConverter);

        ParamConverter<Long> longConverter = parsing(Long::parseLong);
        map.put(long.class, longConverter);
        map.put(Long.class, longConverter);

        // --- Floating-point primitives + boxed ---
        ParamConverter<Float> floatConverter = parsing(Float::parseFloat);
        map.put(float.class, floatConverter);
        map.put(Float.class, floatConverter);

        ParamConverter<Double> doubleConverter = parsing(Double::parseDouble);
        map.put(double.class, doubleConverter);
        map.put(Double.class, doubleConverter);

        // --- Character (single-char string) ---
        ParamConverter<Character> charConverter = charConverter();
        map.put(char.class, charConverter);
        map.put(Character.class, charConverter);

        // --- Big numerics ---
        map.put(BigInteger.class, parsing(BigInteger::new));
        map.put(BigDecimal.class, parsing(BigDecimal::new));

        // --- UUID + URI ---
        map.put(UUID.class, parsing(UUID::fromString));
        map.put(URI.class, parsing(URI::create));

        // --- java.time family (each via its parse) ---
        map.put(Instant.class, parsing(Instant::parse));
        map.put(LocalDate.class, parsing(LocalDate::parse));
        map.put(LocalTime.class, parsing(LocalTime::parse));
        map.put(LocalDateTime.class, parsing(LocalDateTime::parse));
        map.put(OffsetDateTime.class, parsing(OffsetDateTime::parse));
        map.put(OffsetTime.class, parsing(OffsetTime::parse));
        map.put(ZonedDateTime.class, parsing(ZonedDateTime::parse));
        map.put(Duration.class, parsing(Duration::parse));
        map.put(Period.class, parsing(Period::parse));
        map.put(Year.class, parsing(Year::parse));
        map.put(YearMonth.class, parsing(YearMonth::parse));
        map.put(MonthDay.class, parsing(MonthDay::parse));
        map.put(ZoneId.class, parsing(ZoneId::of));
        map.put(ZoneOffset.class, parsing(ZoneOffset::of));

        return map;
    }

    /**
     * Builds a {@link ParamConverter} whose {@code fromString} applies {@code parser} and whose
     * {@code toString} uses the value's canonical {@link Object#toString()} (the ISO/canonical form
     * for all built-in target types). A parse failure propagates raw (the underlying
     * {@link NumberFormatException}, {@link java.time.format.DateTimeParseException}, UUID's
     * {@link IllegalArgumentException}, etc.); the {@link ParamConversionResolver} is the sole builder
     * of {@link ParamConversionException}, wrapping the raw failure with the real parameter
     * name/source/target type supplied by the call site.
     *
     * @param parser the parse function from string to {@code T}
     * @param <T>    the target type
     * @return a converter delegating to {@code parser} and {@code toString}
     */
    private static <T> ParamConverter<T> parsing(Function<String, T> parser) {
        return new ParamConverter<>() {
            @Override
            public T fromString(String value) {
                return parser.apply(value);
            }

            @Override
            public String toString(T value) {
                return value.toString();
            }
        };
    }

    /**
     * Builds the {@link Character} converter: a single-character string maps to that {@code char};
     * any other length throws a raw {@link IllegalArgumentException} that the
     * {@link ParamConversionResolver} re-contextualizes into a {@link ParamConversionException}.
     *
     * @return the character converter
     */
    private static ParamConverter<Character> charConverter() {
        return new ParamConverter<>() {
            @Override
            public Character fromString(String value) {
                if (value.length() != 1) {
                    throw new IllegalArgumentException(
                            "Cannot convert value to Character: expected a single character");
                }
                return value.charAt(0);
            }

            @Override
            public String toString(Character value) {
                return value.toString();
            }
        };
    }
}
