// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJobLogger}.
 */
@DisplayName("DefaultJobLogger")
class DefaultJobLoggerTest {

    private DefaultJobLogger logger;

    @BeforeEach
    void setUp() {
        logger = new DefaultJobLogger();
    }

    @Test
    @DisplayName("starts empty")
    void startsEmpty() {
        assertTrue(logger.entries().isEmpty());
    }

    @Test
    @DisplayName("info adds INFO entry")
    void infoAddsInfoEntry() {
        logger.info("test message");
        assertEquals(1, logger.entries().size());
        assertEquals("INFO", logger.entries().get(0).level());
        assertEquals("test message", logger.entries().get(0).message());
    }

    @Test
    @DisplayName("warn adds WARN entry")
    void warnAddsWarnEntry() {
        logger.warn("warning");
        assertEquals("WARN", logger.entries().get(0).level());
    }

    @Test
    @DisplayName("error adds ERROR entry")
    void errorAddsErrorEntry() {
        logger.error("error occurred");
        assertEquals("ERROR", logger.entries().get(0).level());
    }

    @Test
    @DisplayName("entries preserves insertion order")
    void entriesPreservesOrder() {
        logger.info("first");
        logger.warn("second");
        logger.error("third");
        assertEquals(3, logger.entries().size());
        assertEquals("INFO", logger.entries().get(0).level());
        assertEquals("WARN", logger.entries().get(1).level());
        assertEquals("ERROR", logger.entries().get(2).level());
    }

    @Test
    @DisplayName("each entry has a non-null loggedAt timestamp")
    void entriesHaveTimestamps() {
        logger.info("timestamped");
        assertTrue(logger.entries().get(0).loggedAt() != null);
    }
}
