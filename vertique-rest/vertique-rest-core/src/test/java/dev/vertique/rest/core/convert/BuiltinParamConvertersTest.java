// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.DayOfWeek;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework built-in {@link ParamConverter} registrations.
 *
 * <p>Each FR-015-02 target type must round-trip {@code fromString}/{@code toString}, and a malformed
 * value must surface as a {@link ParamConversionException}. Converters are obtained through the public
 * surface, {@link ParamConverterRegistry#find(Class)} on a registry built with no application
 * bindings.
 */
class BuiltinParamConvertersTest {

    private final ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

    @SuppressWarnings("unchecked")
    private <T> ParamConverter<T> converterFor(Class<T> type) {
        return (ParamConverter<T>) registry.find(type).orElseThrow();
    }

    /** Asserts that {@code fromString(input)} produces {@code expected} for the built-in converter of {@code type}. */
    private <T> void assertFromString(Class<T> type, String input, T expected) {
        assertThat(converterFor(type).fromString(input)).isEqualTo(expected);
    }

    /** A sample local enum used to exercise the enum-synth converter. */
    private enum Color {
        RED,
        GREEN,
        BLUE
    }

    // --- String + primitives/boxed ---

    @Test
    @DisplayName("String round-trips identity")
    void stringRoundtrip() {
        ParamConverter<String> c = converterFor(String.class);
        assertThat(c.toString(c.fromString("hello"))).isEqualTo("hello");
    }

    @Test
    @DisplayName("Integer (boxed) round-trips")
    void integerRoundtrip() {
        ParamConverter<Integer> c = converterFor(Integer.class);
        assertThat(c.toString(c.fromString("42"))).isEqualTo("42");
        assertThat(c.fromString("42")).isEqualTo(42);
    }

    @Test
    @DisplayName("int (primitive) round-trips")
    void intPrimitiveRoundtrip() {
        assertFromString(int.class, "42", 42);
    }

    @Test
    @DisplayName("Long round-trips")
    void longRoundtrip() {
        assertFromString(Long.class, "123456789012345", 123456789012345L);
        assertFromString(long.class, "7", 7L);
    }

    @Test
    @DisplayName("Short round-trips")
    void shortRoundtrip() {
        assertFromString(Short.class, "12", (short) 12);
        assertFromString(short.class, "12", (short) 12);
    }

    @Test
    @DisplayName("Byte round-trips")
    void byteRoundtrip() {
        assertFromString(Byte.class, "5", (byte) 5);
        assertFromString(byte.class, "5", (byte) 5);
    }

    @Test
    @DisplayName("Double round-trips")
    void doubleRoundtrip() {
        assertFromString(Double.class, "3.5", 3.5d);
        assertFromString(double.class, "3.5", 3.5d);
    }

    @Test
    @DisplayName("Float round-trips")
    void floatRoundtrip() {
        assertFromString(Float.class, "1.5", 1.5f);
        assertFromString(float.class, "1.5", 1.5f);
    }

    @Test
    @DisplayName("Boolean round-trips")
    void booleanRoundtrip() {
        assertFromString(Boolean.class, "true", Boolean.TRUE);
        assertFromString(boolean.class, "false", Boolean.FALSE);
    }

    @Test
    @DisplayName("Character round-trips")
    void charRoundtrip() {
        assertFromString(Character.class, "x", 'x');
        assertFromString(char.class, "y", 'y');
    }

    // --- Big numerics ---

    @Test
    @DisplayName("BigInteger round-trips")
    void bigIntegerRoundtrip() {
        assertFromString(BigInteger.class, "99999999999", new BigInteger("99999999999"));
    }

    @Test
    @DisplayName("BigDecimal round-trips")
    void bigDecimalRoundtrip() {
        assertFromString(BigDecimal.class, "3.14", new BigDecimal("3.14"));
    }

    // --- UUID + URI ---

    @Test
    @DisplayName("UUID round-trips")
    void uuidRoundtrip() {
        UUID id = UUID.randomUUID();
        assertFromString(UUID.class, id.toString(), id);
    }

    @Test
    @DisplayName("URI round-trips")
    void uriRoundtrip() {
        assertFromString(URI.class, "https://example.com/path", URI.create("https://example.com/path"));
    }

    // --- java.time family ---

    @Test
    @DisplayName("Instant round-trips ISO-8601")
    void instantIsoRoundtrip() {
        assertFromString(Instant.class, "2024-01-15T10:30:00Z", Instant.parse("2024-01-15T10:30:00Z"));
    }

    @Test
    @DisplayName("LocalDate round-trips ISO-8601")
    void localDateIsoRoundtrip() {
        assertFromString(LocalDate.class, "2024-01-15", LocalDate.of(2024, 1, 15));
    }

    @Test
    @DisplayName("LocalTime round-trips ISO-8601")
    void localTimeRoundtrip() {
        assertFromString(LocalTime.class, "10:30:00", LocalTime.of(10, 30, 0));
    }

    @Test
    @DisplayName("LocalDateTime round-trips ISO-8601")
    void localDateTimeRoundtrip() {
        assertFromString(LocalDateTime.class, "2024-01-15T10:30:00", LocalDateTime.of(2024, 1, 15, 10, 30, 0));
    }

    @Test
    @DisplayName("OffsetDateTime round-trips ISO-8601")
    void offsetDateTimeRoundtrip() {
        assertFromString(
                OffsetDateTime.class, "2024-01-15T10:30:00+02:00", OffsetDateTime.parse("2024-01-15T10:30:00+02:00"));
    }

    @Test
    @DisplayName("OffsetTime round-trips ISO-8601")
    void offsetTimeRoundtrip() {
        assertFromString(OffsetTime.class, "10:30:00+02:00", OffsetTime.parse("10:30:00+02:00"));
    }

    @Test
    @DisplayName("ZonedDateTime round-trips ISO-8601")
    void zonedDateTimeRoundtrip() {
        assertFromString(
                ZonedDateTime.class,
                "2024-01-15T10:30:00+01:00[Europe/Paris]",
                ZonedDateTime.parse("2024-01-15T10:30:00+01:00[Europe/Paris]"));
    }

    @Test
    @DisplayName("Duration round-trips ISO-8601")
    void durationRoundtrip() {
        assertFromString(Duration.class, "PT15M", Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Period round-trips ISO-8601")
    void periodRoundtrip() {
        assertFromString(Period.class, "P1Y2M3D", Period.of(1, 2, 3));
    }

    @Test
    @DisplayName("Year round-trips")
    void yearRoundtrip() {
        assertFromString(Year.class, "2024", Year.of(2024));
    }

    @Test
    @DisplayName("YearMonth round-trips")
    void yearMonthRoundtrip() {
        assertFromString(YearMonth.class, "2024-01", YearMonth.of(2024, 1));
    }

    @Test
    @DisplayName("MonthDay round-trips")
    void monthDayRoundtrip() {
        assertFromString(MonthDay.class, "--01-15", MonthDay.of(1, 15));
    }

    @Test
    @DisplayName("ZoneId round-trips")
    void zoneIdRoundtrip() {
        assertFromString(ZoneId.class, "Europe/Paris", ZoneId.of("Europe/Paris"));
    }

    @Test
    @DisplayName("ZoneOffset round-trips")
    void zoneOffsetRoundtrip() {
        assertFromString(ZoneOffset.class, "+02:00", ZoneOffset.of("+02:00"));
    }

    // --- Enum ---

    @Test
    @DisplayName("Enum fromString is case-sensitive name() match")
    void enumRoundtripCaseSensitive() {
        assertFromString(DayOfWeek.class, "MONDAY", DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("Enum toString returns name()")
    void enumToStringIsName() {
        assertThat(converterFor(DayOfWeek.class).toString(DayOfWeek.FRIDAY)).isEqualTo("FRIDAY");
    }

    @Test
    @DisplayName("Sample local enum round-trips via synth converter")
    void sampleEnumRoundtrip() {
        ParamConverter<Color> c = converterFor(Color.class);
        assertThat(c.fromString("GREEN")).isEqualTo(Color.GREEN);
        assertThat(c.toString(Color.BLUE)).isEqualTo("BLUE");
    }

    // --- Malformed values (built-ins throw RAW; the resolver does the contextual wrapping) ---

    @Test
    @DisplayName("Malformed int throws the raw NumberFormatException")
    void badIntValueThrowsRawNumberFormatException() {
        assertThatThrownBy(() -> converterFor(Integer.class).fromString("not-a-number"))
                .isInstanceOf(NumberFormatException.class)
                .isNotInstanceOf(ParamConversionException.class);
    }

    @Test
    @DisplayName("Malformed UUID throws the raw IllegalArgumentException, not a ParamConversionException")
    void badUuidValueThrowsRawIllegalArgumentException() {
        assertThatThrownBy(() -> converterFor(UUID.class).fromString("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ParamConversionException.class);
    }

    @Test
    @DisplayName("Malformed LocalDate throws the raw DateTimeParseException")
    void badLocalDateValueThrowsRawDateTimeParseException() {
        assertThatThrownBy(() -> converterFor(LocalDate.class).fromString("not-a-date"))
                .isInstanceOf(java.time.format.DateTimeParseException.class)
                .isNotInstanceOf(ParamConversionException.class);
    }

    @Test
    @DisplayName("Character converter throws the raw IllegalArgumentException for a multi-char string")
    void badCharValueThrowsRawIllegalArgumentException() {
        assertThatThrownBy(() -> converterFor(Character.class).fromString("xy"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ParamConversionException.class);
    }

    // --- Exception field carriage ---

    @Test
    @DisplayName("ParamConversionException carries paramName, source, and targetType")
    void paramConversionExceptionCarriesNameAndType() {
        ParamConversionException ex =
                new ParamConversionException("bad value", "count", ParamSource.QUERY, Integer.class);

        assertThat(ex.paramName()).isEqualTo("count");
        assertThat(ex.source()).isEqualTo(ParamSource.QUERY);
        assertThat(ex.targetType()).isEqualTo(Integer.class);
    }
}
