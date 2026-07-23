// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.exception.DbValidationException;
import dev.vertique.db.query.PageSizeConstraintViolationException;
import org.junit.jupiter.api.Test;

class PageSizeConstraintViolationExceptionTest {

    private final PageSizeConstraintViolationException ex = new PageSizeConstraintViolationException(500, 1, 100);

    @Test
    void message_containsAllValues() {
        assertEquals("pageSize 500 is out of range [1, 100]", ex.getMessage());
    }

    @Test
    void requestedPageSize_returnsValue() {
        assertEquals(500, ex.requestedPageSize());
    }

    @Test
    void minPageSize_returnsValue() {
        assertEquals(1, ex.minPageSize());
    }

    @Test
    void maxPageSize_returnsValue() {
        assertEquals(100, ex.maxPageSize());
    }

    @Test
    void extendsDbValidationException() {
        assertInstanceOf(DbValidationException.class, ex);
    }

    @Test
    void extendsValidationException() {
        assertInstanceOf(ValidationException.class, ex);
    }
}
