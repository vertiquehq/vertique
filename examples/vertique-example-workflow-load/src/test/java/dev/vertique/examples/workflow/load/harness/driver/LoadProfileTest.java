// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the open-loop schedule: how many arrivals a profile offers and when each one is due. */
class LoadProfileTest {

    @Test
    @DisplayName("a fixed profile offers the rate times the duration, evenly spaced")
    void fixedRate() {
        LoadProfile profile = LoadProfile.fixed(100, 2, 3);

        assertEquals(500, profile.total());
        assertEquals(0L, profile.offsetNanos(0));
        assertEquals(10_000_000L, profile.offsetNanos(1));
        assertEquals(2_000_000_000L, profile.offsetNanos(200));
        assertEquals(2.0, profile.windowStartSec());
        assertEquals(5.0, profile.windowEndSec());
        assertEquals("warmup", profile.segment(profile.segmentOf(199)).label());
        assertEquals("window", profile.segment(profile.segmentOf(200)).label());
    }

    @Test
    @DisplayName("a burst offers its arrivals at one instant and keeps the average rate")
    void burst() {
        LoadProfile profile = LoadProfile.of(List.of(new LoadProfile.Segment("sparse", 10, 0.8, 4, true)));

        assertEquals(8, profile.total());
        assertEquals(profile.offsetNanos(0), profile.offsetNanos(3));
        assertEquals(5_000_000_000L, profile.offsetNanos(4));
    }

    @Test
    @DisplayName("a pause takes time and offers nothing")
    void pause() {
        LoadProfile profile = LoadProfile.of(List.of(
                new LoadProfile.Segment("a", 1, 10, 1, true),
                new LoadProfile.Segment("gap", 5, 0, 1, false),
                new LoadProfile.Segment("b", 1, 10, 1, true)));

        assertEquals(20, profile.total());
        assertEquals("b", profile.segment(profile.segmentOf(10)).label());
        assertEquals(6_000_000_000L, profile.offsetNanos(10));
        assertEquals(7.0, profile.endSec());
        assertEquals(0.0, profile.windowStartSec());
        assertEquals(7.0, profile.windowEndSec());
    }

    @Test
    @DisplayName("a staircase has equal steps with growing rates")
    void staircase() {
        LoadProfile profile = LoadProfile.staircase(100, 100, 3, 10);

        assertEquals(1000 + 2000 + 3000, profile.total());
        assertEquals("step-2", profile.segment(profile.segmentOf(1500)).label());
        assertEquals(30.0, profile.endSec());
    }

    @Test
    @DisplayName("an arrival past the end is out of range")
    void outOfRange() {
        LoadProfile profile = LoadProfile.fixed(10, 0, 1);

        assertThrows(IndexOutOfBoundsException.class, () -> profile.segmentOf(10));
    }

    @Test
    @DisplayName("an empty profile has no arrivals and no window")
    void empty() {
        LoadProfile profile = LoadProfile.of(List.of());

        assertEquals(0, profile.total());
        assertEquals(0.0, profile.endSec());
    }

    @Test
    @DisplayName("a segment needs a label, a duration and a rate")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile.Segment("", 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile.Segment("a", 0, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile.Segment("a", 1, -1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile.Segment("a", 1, 1, 0, true));
    }
}
