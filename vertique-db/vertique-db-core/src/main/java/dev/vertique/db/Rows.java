// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Null-safe row extraction helpers for common data types. All methods return {@code null} or {@link
 * Optional#empty()} when the column value is SQL NULL.
 */
public final class Rows {

    private Rows() {}

    // --- Enum ---

    /**
     * Extracts an enum value from the named column.
     *
     * @param <E>      the enum type
     * @param row      the row
     * @param column   the column name
     * @param enumType the enum class
     * @return the enum value, or {@code null} if the column is NULL
     */
    public static <E extends Enum<E>> E enumValue(Row row, String column, Class<E> enumType) {
        String value = row.getString(column);
        return value != null ? Enum.valueOf(enumType, value) : null;
    }

    /**
     * Extracts an optional enum value from the named column.
     *
     * @param <E>      the enum type
     * @param row      the row
     * @param column   the column name
     * @param enumType the enum class
     * @return an optional containing the enum value, or empty if NULL
     */
    public static <E extends Enum<E>> Optional<E> optionalEnum(Row row, String column, Class<E> enumType) {
        return Optional.ofNullable(enumValue(row, column, enumType));
    }

    // --- UUID ---

    /**
     * Extracts a UUID from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the UUID, or {@code null} if the column is NULL
     */
    public static UUID uuidOrNull(Row row, String column) {
        return row.getUUID(column);
    }

    /**
     * Extracts an optional UUID from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return an optional containing the UUID, or empty if NULL
     */
    public static Optional<UUID> optionalUuid(Row row, String column) {
        return Optional.ofNullable(uuidOrNull(row, column));
    }

    // --- Date / time ---

    /**
     * Extracts an OffsetDateTime from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the OffsetDateTime, or {@code null} if the column is NULL
     */
    public static OffsetDateTime offsetDateTimeOrNull(Row row, String column) {
        return row.getOffsetDateTime(column);
    }

    /**
     * Extracts an Instant from the named column by converting from {@link OffsetDateTime}.
     *
     * @param row    the row
     * @param column the column name
     * @return the Instant, or {@code null} if the column is NULL
     */
    public static Instant instantOrNull(Row row, String column) {
        OffsetDateTime odt = row.getOffsetDateTime(column);
        return odt != null ? odt.toInstant() : null;
    }

    /**
     * Extracts a LocalDate from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the LocalDate, or {@code null} if the column is NULL
     */
    public static LocalDate localDateOrNull(Row row, String column) {
        return row.getLocalDate(column);
    }

    // --- Numeric ---

    /**
     * Extracts an Integer from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the Integer, or {@code null} if the column is NULL
     */
    public static Integer integerOrNull(Row row, String column) {
        return row.getInteger(column);
    }

    /**
     * Extracts a Long from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the Long, or {@code null} if the column is NULL
     */
    public static Long longOrNull(Row row, String column) {
        return row.getLong(column);
    }

    // --- Boolean ---

    /**
     * Extracts a Boolean from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the Boolean, or {@code null} if the column is NULL
     */
    public static Boolean booleanOrNull(Row row, String column) {
        return row.getBoolean(column);
    }

    // --- JSON ---

    /**
     * Extracts a JsonObject from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return the JsonObject, or {@code null} if the column is NULL
     */
    public static JsonObject jsonObjectOrNull(Row row, String column) {
        return row.getJsonObject(column);
    }

    /**
     * Extracts an optional JsonObject from the named column.
     *
     * @param row    the row
     * @param column the column name
     * @return an optional containing the JsonObject, or empty if NULL
     */
    public static Optional<JsonObject> optionalJson(Row row, String column) {
        return Optional.ofNullable(jsonObjectOrNull(row, column));
    }

    /**
     * Extracts a JsonObject from the named column and maps it to the target type.
     *
     * @param <T>    the target type
     * @param row    the row
     * @param column the column name
     * @param type   the class to map to
     * @return the mapped object, or {@code null} if the column is NULL
     */
    public static <T> T jsonOrNull(Row row, String column, Class<T> type) {
        JsonObject json = row.getJsonObject(column);
        return json != null ? json.mapTo(type) : null;
    }
}
