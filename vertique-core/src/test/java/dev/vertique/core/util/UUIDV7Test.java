// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UUIDV7}.
 *
 * <p>Covers the RFC 9562 bit layout, the sub-millisecond fraction mapping, strict ordering within
 * and across milliseconds (by {@link UUID#compareTo(UUID)} and by string form), carry from the
 * fraction into the millisecond, clock regression, the 48-bit timestamp range, timestamp
 * extraction, and concurrent uniqueness and ordering.
 */
@DisplayName("UUIDV7")
class UUIDV7Test {

    private static final long TS = 0x0123_4567_89ABL;
    private static final long RAND_B_MASK = (1L << 62) - 1;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;

    /** A settable clock. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(long millis, long nanosIntoMillisecond) {
            set(millis, nanosIntoMillisecond);
        }

        void set(long millis, long nanosIntoMillisecond) {
            this.now = Instant.ofEpochMilli(millis).plusNanos(nanosIntoMillisecond);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Entropy source returning the scripted values in turn, repeating the last one. */
    private static final class ScriptedRandom implements RandomGenerator {
        private final long[] values;
        private int next;

        ScriptedRandom(long... values) {
            this.values = values;
        }

        @Override
        public long nextLong() {
            return values[Math.min(next++, values.length - 1)];
        }
    }

    private static UUIDV7.Generator scripted(TestClock clock, long... randomValues) {
        return new UUIDV7.Generator(clock, new ScriptedRandom(randomValues));
    }

    private static UUIDV7.Generator real(TestClock clock) {
        return new UUIDV7.Generator(clock, new SecureRandom());
    }

    private static long fraction(UUID uuid) {
        return uuid.getMostSignificantBits() & 0xFFF;
    }

    private static void assertStrictlyIncreasing(UUID previous, UUID next) {
        assertTrue(previous.compareTo(next) < 0, previous + " should sort before " + next);
        assertTrue(previous.toString().compareTo(next.toString()) < 0, previous + " should sort before " + next);
    }

    @Nested
    @DisplayName("layout")
    class Layout {

        @Test
        @DisplayName("fields: timestamp, version 7, fraction, variant 2 and random bits land where RFC 9562 puts them")
        void fields() {
            // 500,000 ns into the millisecond is exactly half way: 2048 / 4096.
            UUIDV7.Generator generator = scripted(new TestClock(TS, 500_000), -1L);

            UUID uuid = generator.next();

            assertEquals(new UUID((TS << 16) | 0x7000L | 0x800, VARIANT_BITS | RAND_B_MASK), uuid);
            assertEquals(7, uuid.version());
            assertEquals(2, uuid.variant());
        }

        @Test
        @DisplayName("randB: every call draws fresh random bits, and the field never leaks into the variant")
        void randomBits() {
            UUIDV7.Generator generator = scripted(new TestClock(TS, 0), 0L, 0x1234_5678L);

            assertEquals(VARIANT_BITS, generator.next().getLeastSignificantBits());
            assertEquals(VARIANT_BITS | 0x1234_5678L, generator.next().getLeastSignificantBits());
        }

        @Test
        @DisplayName("fraction: nanoseconds map onto 4096 steps of a millisecond")
        void fractionMapping() {
            TestClock clock = new TestClock(TS, 0);
            UUIDV7.Generator generator = scripted(clock, 0L);
            long[][] nanosToFraction = {{0, 0}, {244, 0}, {245, 1}, {250_000, 1024}, {999_999, 4095}};

            for (int i = 0; i < nanosToFraction.length; i++) {
                long[] pair = nanosToFraction[i];
                clock.set(TS + i, pair[0]);
                UUID uuid = generator.next();
                assertEquals(pair[1], fraction(uuid), "nanos " + pair[0]);
                assertEquals(TS + i, UUIDV7.timestampMillis(uuid));
            }
        }

        @Test
        @DisplayName("timestamp: the largest 48-bit millisecond value is accepted")
        void maxTimestamp() {
            UUID uuid = real(new TestClock(MAX_TIMESTAMP, 0)).next();

            assertEquals(MAX_TIMESTAMP, UUIDV7.timestampMillis(uuid));
            assertEquals(7, uuid.version());
        }

        @Test
        @DisplayName("timestamp: zero is a valid epoch millisecond")
        void zeroTimestamp() {
            UUID uuid = real(new TestClock(0, 0)).next();

            assertEquals(0, UUIDV7.timestampMillis(uuid));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("sameInstant: 10,000 identifiers from a frozen clock are strictly increasing")
        void sameInstant() {
            UUIDV7.Generator generator = real(new TestClock(TS, 123_456));
            UUID previous = generator.next();

            for (int i = 0; i < 10_000; i++) {
                UUID next = generator.next();
                assertStrictlyIncreasing(previous, next);
                previous = next;
            }
        }

        @Test
        @DisplayName("sameInstant: each call advances the fraction by exactly one step")
        void advancesOneStep() {
            UUIDV7.Generator generator = scripted(new TestClock(TS, 0), 0L);

            UUID first = generator.next();
            UUID second = generator.next();
            UUID third = generator.next();

            assertEquals(0, fraction(first));
            assertEquals(1, fraction(second));
            assertEquals(2, fraction(third));
            assertEquals(TS, UUIDV7.timestampMillis(third));
        }

        @Test
        @DisplayName("carry: the step after the last fraction rolls over into the next millisecond")
        void carryIntoMillisecond() {
            UUIDV7.Generator generator = scripted(new TestClock(TS, 999_999), 0L);

            UUID last = generator.next();
            UUID carried = generator.next();

            assertEquals(4095, fraction(last));
            assertEquals(TS, UUIDV7.timestampMillis(last));
            assertEquals(0, fraction(carried));
            assertEquals(TS + 1, UUIDV7.timestampMillis(carried));
            assertStrictlyIncreasing(last, carried);
        }

        @Test
        @DisplayName("burst: the timestamp runs ahead of a frozen clock and the order holds")
        void burstRunsAhead() {
            UUIDV7.Generator generator = real(new TestClock(TS, 0));
            UUID previous = generator.next();

            for (int i = 1; i < 3 * 4096; i++) {
                UUID next = generator.next();
                assertStrictlyIncreasing(previous, next);
                previous = next;
            }

            assertEquals(TS + 2, UUIDV7.timestampMillis(previous));
            assertEquals(4095, fraction(previous));
        }

        @Test
        @DisplayName("clockAdvances: a later clock is used as is, not stretched from the previous identifier")
        void clockAdvances() {
            TestClock clock = new TestClock(TS, 100_000);
            UUIDV7.Generator generator = real(clock);
            UUID first = generator.next();

            clock.set(TS + 5, 750_000);
            UUID second = generator.next();

            assertEquals(TS + 5, UUIDV7.timestampMillis(second));
            assertEquals(3072, fraction(second));
            assertStrictlyIncreasing(first, second);
        }

        @Test
        @DisplayName("clockRegression: a clock moving backwards keeps advancing from the last identifier")
        void clockMovesBackwards() {
            TestClock clock = new TestClock(TS, 0);
            UUIDV7.Generator generator = scripted(clock, 0L);
            UUID first = generator.next();

            clock.set(TS - 60_000, 900_000);
            UUID second = generator.next();
            UUID third = generator.next();

            assertEquals(TS, UUIDV7.timestampMillis(second));
            assertEquals(1, fraction(second));
            assertEquals(2, fraction(third));
            assertStrictlyIncreasing(first, second);
            assertStrictlyIncreasing(second, third);
        }

        @Test
        @DisplayName("clockCatchesUp: once the clock passes the last identifier it is used again")
        void clockCatchesUpAfterRegression() {
            TestClock clock = new TestClock(TS, 0);
            UUIDV7.Generator generator = real(clock);
            generator.next();
            clock.set(TS - 10, 0);
            UUID ahead = generator.next();

            clock.set(TS + 10, 0);
            UUID caughtUp = generator.next();

            assertEquals(TS, UUIDV7.timestampMillis(ahead));
            assertEquals(TS + 10, UUIDV7.timestampMillis(caughtUp));
            assertEquals(0, fraction(caughtUp));
            assertStrictlyIncreasing(ahead, caughtUp);
        }

        @Test
        @DisplayName("acrossMilliseconds: later clocks always sort later")
        void acrossMilliseconds() {
            TestClock clock = new TestClock(TS, 0);
            UUIDV7.Generator generator = real(clock);
            UUID previous = generator.next();

            for (int i = 1; i <= 1_000; i++) {
                clock.set(TS + i, 0);
                UUID next = generator.next();
                assertEquals(TS + i, UUIDV7.timestampMillis(next));
                assertStrictlyIncreasing(previous, next);
                previous = next;
            }
        }
    }

    @Nested
    @DisplayName("clock range")
    class ClockRange {

        @Test
        @DisplayName("tooLarge: a timestamp beyond 48 bits is rejected and the generator stays usable")
        void tooLarge() {
            TestClock clock = new TestClock(MAX_TIMESTAMP + 1, 0);
            UUIDV7.Generator generator = real(clock);

            assertThrows(IllegalStateException.class, generator::next);

            clock.set(TS, 0);
            assertEquals(TS, UUIDV7.timestampMillis(generator.next()));
        }

        @Test
        @DisplayName("negative: a clock before the epoch is rejected")
        void negative() {
            UUIDV7.Generator generator = real(new TestClock(-1, 0));

            assertThrows(IllegalStateException.class, generator::next);
        }

        @Test
        @DisplayName("exhaustion: stepping past the last representable millisecond is rejected")
        void exhaustionAtLimit() {
            UUIDV7.Generator generator = real(new TestClock(MAX_TIMESTAMP, 999_999));
            UUID last = generator.next();

            assertEquals(MAX_TIMESTAMP, UUIDV7.timestampMillis(last));
            assertEquals(4095, fraction(last));
            assertThrows(IllegalStateException.class, generator::next);
        }
    }

    @Nested
    @DisplayName("timestampMillis")
    class TimestampMillis {

        @Test
        @DisplayName("roundTrip: returns the generator's timestamp")
        void roundTrip() {
            assertEquals(TS, UUIDV7.timestampMillis(real(new TestClock(TS, 42)).next()));
        }

        @Test
        @DisplayName("version4: a random UUID is rejected")
        void rejectsVersion4() {
            assertThrows(IllegalArgumentException.class, () -> UUIDV7.timestampMillis(UUID.randomUUID()));
        }

        @Test
        @DisplayName("variant: a version 7 UUID with a non-RFC variant is rejected")
        void rejectsOtherVariant() {
            UUID wrongVariant = new UUID((TS << 16) | 0x7000L, 0L);

            assertEquals(7, wrongVariant.version());
            assertThrows(IllegalArgumentException.class, () -> UUIDV7.timestampMillis(wrongVariant));
        }

        @Test
        @DisplayName("null: a null UUID is rejected")
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> UUIDV7.timestampMillis(null));
        }
    }

    @Nested
    @DisplayName("randomUUID")
    class RandomUuid {

        @Test
        @DisplayName("current: produces a version 7 UUID stamped with the current time")
        void usesTheSystemClock() {
            long before = System.currentTimeMillis();
            UUID uuid = UUIDV7.randomUUID();
            long after = System.currentTimeMillis();

            assertEquals(7, uuid.version());
            assertEquals(2, uuid.variant());
            long timestamp = UUIDV7.timestampMillis(uuid);
            // Tests in this class burst the shared generator, which may leave it a little ahead.
            assertTrue(timestamp >= before && timestamp <= after + 1_000, "timestamp " + timestamp);
        }

        @Test
        @DisplayName("sequence: consecutive calls are strictly increasing")
        void consecutiveCalls() {
            UUID previous = UUIDV7.randomUUID();

            for (int i = 0; i < 10_000; i++) {
                UUID next = UUIDV7.randomUUID();
                assertStrictlyIncreasing(previous, next);
                previous = next;
            }
        }

        @Test
        @DisplayName("concurrent: threads get unique identifiers, each thread seeing them in order")
        void concurrentUniquenessAndOrder() throws Exception {
            int threads = 8;
            int perThread = 5_000;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<List<UUID>>> tasks = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    tasks.add(() -> {
                        List<UUID> issued = new ArrayList<>(perThread);
                        for (int i = 0; i < perThread; i++) {
                            issued.add(UUIDV7.randomUUID());
                        }
                        return issued;
                    });
                }

                Set<UUID> all = new HashSet<>();
                for (Future<List<UUID>> future : executor.invokeAll(tasks)) {
                    List<UUID> issued = future.get(30, TimeUnit.SECONDS);
                    for (int i = 1; i < issued.size(); i++) {
                        assertStrictlyIncreasing(issued.get(i - 1), issued.get(i));
                    }
                    all.addAll(issued);
                }

                assertEquals(threads * perThread, all.size());
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
