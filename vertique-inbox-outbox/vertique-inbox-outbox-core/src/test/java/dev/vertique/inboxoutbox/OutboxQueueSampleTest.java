// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxQueueSample} validation. */
@DisplayName("OutboxQueueSample")
class OutboxQueueSampleTest {

    @Test
    @DisplayName("carries the components it is given")
    void carriesComponents() {
        OutboxQueueSample sample = new OutboxQueueSample(3, Duration.ofSeconds(7), 2);

        assertEquals(3, sample.due());
        assertEquals(Duration.ofSeconds(7), sample.oldestDueAge());
        assertEquals(2, sample.deadLetter());
    }

    @Test
    @DisplayName("rejects a negative count")
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxQueueSample(-1, Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxQueueSample(0, Duration.ZERO, -1));
    }

    @Test
    @DisplayName("rejects a null age")
    void rejectsNullAge() {
        assertThrows(NullPointerException.class, () -> new OutboxQueueSample(0, null, 0));
    }

    @Test
    @DisplayName("reads a negative age as zero")
    void negativeAgeIsZero() {
        assertEquals(Duration.ZERO, new OutboxQueueSample(1, Duration.ofSeconds(-5), 0).oldestDueAge());
    }
}
