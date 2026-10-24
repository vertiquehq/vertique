// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Generates time-ordered {@code UUIDv7} identifiers as defined by RFC 9562, section 5.7.
 *
 * <p>{@link #randomUUID()} is a drop-in replacement for {@link UUID#randomUUID()} wherever
 * identifiers must sort by creation time, for example as database primary keys that should append
 * to the right edge of an index instead of scattering across it. The result is an ordinary
 * {@link UUID}; no new type crosses any API boundary.
 *
 * <p>Layout, most significant bit first:
 *
 * <pre>
 *  48 bits  unix_ts_ms  Unix epoch milliseconds
 *   4 bits  ver         0b0111
 *  12 bits  rand_a      fraction of the millisecond, in 1/4096 steps
 *   2 bits  var         0b10
 *  62 bits  rand_b      random
 * </pre>
 *
 * <p>This is the "increased clock precision" method of RFC 9562, section 6.2, the same layout
 * PostgreSQL's {@code uuidv7()} and Hibernate's version 7 generator use. Identifiers therefore sort
 * by creation time at roughly quarter-microsecond resolution, including across processes and hosts
 * whose clocks agree, and they sort consistently with identifiers a database generates with that
 * layout.
 *
 * <h2>Ordering</h2>
 *
 * <p>Identifiers produced by one JVM are strictly increasing in the order the calls return. Each
 * identifier takes the current time, but never less than one step (1/4096 ms, about 244 ns) after
 * the previous one. Because the order holds for the whole UUID, {@link UUID#compareTo(UUID)} and the
 * lexicographic order of {@link UUID#toString()} both agree with creation order.
 *
 * <p>Monotonicity survives two edge cases without blocking the caller:
 *
 * <ul>
 *   <li><strong>Clock moving backwards.</strong> Identifiers keep advancing from the last one
 *       issued, so a clock step or leap-second correction never produces a smaller identifier.
 *   <li><strong>Bursts.</strong> Generating faster than one identifier per step, about four million
 *       per second sustained, pushes the embedded timestamp ahead of the wall clock until the burst
 *       ends.
 * </ul>
 *
 * <p>Ordering is guaranteed per JVM only. Identifiers from different processes or hosts order by
 * their clocks, and by random bits where the clocks give the same instant.
 *
 * <h2>Precision</h2>
 *
 * <p>The sub-millisecond fraction is only as fine as the platform clock. Where the clock has
 * microsecond precision, as on macOS, consecutive fractions may differ in units of four steps.
 * Ordering is unaffected.
 *
 * <h2>Security</h2>
 *
 * <p>The random bits come from a {@link SecureRandom}, the entropy source behind
 * {@link UUID#randomUUID()}. Unlike a version 4 UUID, a version 7 UUID discloses its creation time
 * and has fewer unpredictable bits. Do not use it as a bearer token, session secret, or any other
 * value whose confidentiality matters.
 *
 * <p>This class is thread-safe. Callers contend only for a short critical section that does no
 * blocking work of its own, so it may be called from an event-loop thread.
 */
public final class UUIDV7 {

    private static final Generator SHARED = new Generator(Clock.systemUTC(), new SecureRandom());

    private UUIDV7() {}

    /**
     * Generates a new {@code UUIDv7}, strictly greater than every identifier this method has
     * previously returned in this JVM.
     *
     * @return a new version 7, variant 2 UUID
     * @throws IllegalStateException if the system clock is outside the range a 48-bit millisecond
     *     timestamp can represent (before 1970-01-01 or after the year 10889)
     */
    public static UUID randomUUID() {
        return SHARED.next();
    }

    /**
     * Extracts the creation time embedded in a {@code UUIDv7}.
     *
     * <p>The value is the 48-bit {@code unix_ts_ms} field. It is the generator's timestamp, which
     * can run slightly ahead of the wall clock after a clock regression or a burst.
     *
     * @param uuid a version 7, variant 2 UUID
     * @return the embedded timestamp as milliseconds since the Unix epoch
     * @throws IllegalArgumentException if {@code uuid} is not a version 7, variant 2 UUID
     * @throws NullPointerException if {@code uuid} is {@code null}
     */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != VERSION || uuid.variant() != VARIANT) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> TIMESTAMP_SHIFT;
    }

    // --- RFC 9562 layout ---

    private static final int VERSION = 7;
    private static final int VARIANT = 2;
    private static final int TIMESTAMP_SHIFT = 16;
    private static final long VERSION_BITS = (long) VERSION << 12;
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final long RAND_B_MASK = (1L << 62) - 1;

    // --- Sub-millisecond clock ---

    private static final int SUB_MS_BITS = 12;
    private static final long SUB_MS_MASK = (1L << SUB_MS_BITS) - 1;
    private static final long NANOS_PER_MS = 1_000_000L;

    /**
     * The monotonic state machine behind {@link UUIDV7#randomUUID()}, with an injectable clock and
     * entropy source so ordering edge cases can be exercised deterministically.
     *
     * <p>Time is tracked as a single 60-bit tick count: the millisecond timestamp followed by the
     * 12-bit fraction. A strictly increasing tick count is a strictly increasing UUID.
     */
    static final class Generator {

        private final Clock clock;
        private final RandomGenerator random;
        private final ReentrantLock lock = new ReentrantLock();

        private long lastTick = -1;

        Generator(Clock clock, RandomGenerator random) {
            this.clock = clock;
            this.random = random;
        }

        /**
         * Issues the next identifier.
         *
         * @return a UUID greater than every previously returned one
         * @throws IllegalStateException if the timestamp would not fit in 48 bits
         */
        UUID next() {
            lock.lock();
            try {
                Instant now = clock.instant();
                long millis = now.toEpochMilli();
                if (millis < 0 || millis > MAX_TIMESTAMP) {
                    throw new IllegalStateException("Clock out of range for a 48-bit UUIDv7 timestamp: " + millis);
                }
                long fraction = ((now.getNano() % NANOS_PER_MS) << SUB_MS_BITS) / NANOS_PER_MS;
                long tick = Math.max((millis << SUB_MS_BITS) | fraction, lastTick + 1);
                long timestamp = tick >>> SUB_MS_BITS;
                if (timestamp > MAX_TIMESTAMP) {
                    throw new IllegalStateException("UUIDv7 timestamp exhausted the 48-bit range: " + timestamp);
                }
                lastTick = tick;
                long msb = (timestamp << TIMESTAMP_SHIFT) | VERSION_BITS | (tick & SUB_MS_MASK);
                long lsb = VARIANT_BITS | (random.nextLong() & RAND_B_MASK);
                return new UUID(msb, lsb);
            } finally {
                lock.unlock();
            }
        }
    }
}
