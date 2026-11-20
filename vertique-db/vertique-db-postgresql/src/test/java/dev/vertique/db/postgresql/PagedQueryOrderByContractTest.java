// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import dev.vertique.db.query.NullHandling;
import dev.vertique.db.query.OrderDirection;
import dev.vertique.db.query.OrderKey;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the keyset and the offset builder to the same {@code orderBy} contract: the failure type each kind of bad
 * input produces is the one the builders' Javadoc names.
 */
class PagedQueryOrderByContractTest {

    private static Stream<Arguments> builders() {
        Supplier<Object> keyset = PgPagedQuery::<String>builder;
        Supplier<Object> offset = PgOffsetPagedQuery::<String>builder;
        return Stream.of(
                Arguments.of(Named.of("keyset builder", keyset)), Arguments.of(Named.of("offset builder", offset)));
    }

    private static void columns(Object builder, String... columns) {
        if (builder instanceof PgPagedQuery.Builder<?> keyset) {
            keyset.orderBy(columns);
        } else {
            ((PgOffsetPagedQuery.Builder<?>) builder).orderBy(columns);
        }
    }

    private static void columns(Object builder, OrderDirection direction, NullHandling nulls, String... columns) {
        if (builder instanceof PgPagedQuery.Builder<?> keyset) {
            keyset.orderBy(direction, nulls, columns);
        } else {
            ((PgOffsetPagedQuery.Builder<?>) builder).orderBy(direction, nulls, columns);
        }
    }

    private static void keys(Object builder, OrderKey first, OrderKey... rest) {
        if (builder instanceof PgPagedQuery.Builder<?> keyset) {
            keyset.orderBy(first, rest);
        } else {
            ((PgOffsetPagedQuery.Builder<?>) builder).orderBy(first, rest);
        }
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("a null column name is a NullPointerException")
    void nullColumnNameIsANullPointerException(Supplier<Object> builder) {
        assertThrows(NullPointerException.class, () -> columns(builder.get(), "id", null));
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("a null column array, direction or null policy is a NullPointerException")
    void nullArgumentsAreNullPointerExceptions(Supplier<Object> builder) {
        assertThrows(
                NullPointerException.class,
                () -> columns(builder.get(), OrderDirection.ASC, NullHandling.DISALLOW, (String[]) null));
        assertThrows(NullPointerException.class, () -> columns(builder.get(), null, NullHandling.DISALLOW, "id"));
        assertThrows(NullPointerException.class, () -> columns(builder.get(), OrderDirection.ASC, null, "id"));
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("a blank or unsafe column name is an InvalidDataAccessUsageException")
    void blankOrUnsafeColumnNameIsAnInvalidDataAccessUsageException(Supplier<Object> builder) {
        assertThrows(InvalidDataAccessUsageException.class, () -> columns(builder.get(), " "));
        assertThrows(InvalidDataAccessUsageException.class, () -> columns(builder.get(), "id; DROP TABLE items--"));
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("an empty column list or a duplicate column is an IllegalArgumentException")
    void emptyOrDuplicateColumnsAreIllegalArguments(Supplier<Object> builder) {
        assertThrows(IllegalArgumentException.class, () -> columns(builder.get()));
        assertThrows(IllegalArgumentException.class, () -> columns(builder.get(), "id", "id"));
        assertThrows(
                IllegalArgumentException.class, () -> keys(builder.get(), OrderKey.asc("id"), OrderKey.desc("id")));
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("a null order key is a NullPointerException")
    void nullOrderKeyIsANullPointerException(Supplier<Object> builder) {
        assertThrows(NullPointerException.class, () -> keys(builder.get(), null));
        assertThrows(NullPointerException.class, () -> keys(builder.get(), OrderKey.asc("id"), (OrderKey) null));
    }

    @ParameterizedTest
    @MethodSource("builders")
    @DisplayName("valid columns and order keys are accepted")
    void validInputIsAccepted(Supplier<Object> builder) {
        assertDoesNotThrow(() -> columns(builder.get(), "name", "id"));
        assertDoesNotThrow(() -> columns(builder.get(), OrderDirection.DESC, NullHandling.NULLS_LAST, "name", "id"));
        assertDoesNotThrow(() -> keys(builder.get(), OrderKey.asc("name"), OrderKey.desc("id")));
    }
}
