// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultProgressReporter}.
 */
@DisplayName("DefaultProgressReporter")
class DefaultProgressReporterTest {

    private DefaultProgressReporter reporter;

    @BeforeEach
    void setUp() {
        reporter = new DefaultProgressReporter();
    }

    @Test
    @DisplayName("starts with zero counts")
    void startsWithZeroCounts() {
        ProgressSnapshot snap = reporter.snapshot();
        assertEquals(0, snap.total());
        assertEquals(0, snap.succeeded());
        assertEquals(0, snap.failed());
        assertNull(snap.status());
    }

    @Test
    @DisplayName("percentage is 0 when total is 0")
    void percentageIsZeroWhenTotalIsZero() {
        assertEquals(0, reporter.percentage());
    }

    @Test
    @DisplayName("setTotal updates total")
    void setTotalUpdatesTotal() {
        reporter.setTotal(100);
        assertEquals(100, reporter.snapshot().total());
    }

    @Test
    @DisplayName("incrementSucceeded increments by 1")
    void incrementSucceededByOne() {
        reporter.setTotal(10);
        reporter.incrementSucceeded();
        reporter.incrementSucceeded();
        assertEquals(2, reporter.snapshot().succeeded());
    }

    @Test
    @DisplayName("incrementSucceeded(count) increments by count")
    void incrementSucceededByCount() {
        reporter.setTotal(100);
        reporter.incrementSucceeded(50);
        assertEquals(50, reporter.snapshot().succeeded());
    }

    @Test
    @DisplayName("incrementFailed increments by 1")
    void incrementFailedByOne() {
        reporter.setTotal(10);
        reporter.incrementFailed();
        assertEquals(1, reporter.snapshot().failed());
    }

    @Test
    @DisplayName("incrementFailed(count) increments by count")
    void incrementFailedByCount() {
        reporter.setTotal(100);
        reporter.incrementFailed(30);
        assertEquals(30, reporter.snapshot().failed());
    }

    @Test
    @DisplayName("percentage computes (succeeded + failed) / total * 100")
    void percentageComputation() {
        reporter.setTotal(100);
        reporter.incrementSucceeded(60);
        reporter.incrementFailed(10);
        assertEquals(70, reporter.percentage());
    }

    @Test
    @DisplayName("setStatus updates status message")
    void setStatusUpdatesStatus() {
        reporter.setStatus("processing batch 3");
        assertEquals("processing batch 3", reporter.snapshot().status());
    }

    @Test
    @DisplayName("setStatus null clears status")
    void setStatusNullClearsStatus() {
        reporter.setStatus("some status");
        reporter.setStatus(null);
        assertNull(reporter.snapshot().status());
    }

    @Test
    @DisplayName("snapshot returns immutable record each call")
    void snapshotReturnsImmutableRecord() {
        reporter.setTotal(10);
        reporter.incrementSucceeded(5);
        ProgressSnapshot snap1 = reporter.snapshot();
        reporter.incrementSucceeded(3);
        ProgressSnapshot snap2 = reporter.snapshot();
        // First snapshot must not change after more increments
        assertEquals(5, snap1.succeeded());
        assertEquals(8, snap2.succeeded());
    }
}
