// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static dev.vertique.kafka.SettlementRig.P0;
import static dev.vertique.kafka.SettlementRig.P1;
import static dev.vertique.kafka.SettlementRig.record;
import static dev.vertique.kafka.SettlementRig.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.kafka.SettlementRig.FakeTimer;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the settlement protocol of a {@code MANUAL} consumer one interleaving at a time. Every handler run, publish,
 * commit and timer is completed by the test, so each test is a fixed order of events and not a race.
 *
 * <p>Verified:
 * <ul>
 *   <li>The consumer commits the watermark: the offset after the longest finished prefix, never past an unfinished
 *       record, whatever finishes first, with simultaneous failures, queued records and offset gaps.</li>
 *   <li>Records of a partition run concurrently up to the permits and in no promised order; a record that waits for
 *       a retry, a dead-letter publish or the gate holds no permit.</li>
 *   <li>A failure follows the strategy: skip, retry in memory with the attempts counted, dead letter with a publish
 *       that is made again until it is acknowledged. Nothing seeks.</li>
 *   <li>A callback of an ended assignment changes nothing, also when the partition came back to the same
 *       consumer.</li>
 *   <li>A commit that fails is submitted again with the watermark of that moment; a restart reads the uncommitted
 *       records again.</li>
 *   <li>Retention bounds stop fetching and release it at half the bound; the gate keeps records from starting and a
 *       refused record spends no attempt; a stop starts nothing and commits only the settled prefix.</li>
 * </ul>
 */
class KafkaSettlementTest {

    // --- The watermark ---

    @Nested
    @DisplayName("the watermark")
    class Watermark {

        @Test
        @DisplayName("two simultaneous failures keep later records that finish first from being committed")
        void simultaneousFailuresHoldTheWatermark() {
            // Given: offsets 0 to 3 run at once on a retrying binding
            SettlementRig rig = new SettlementRig(settings(4, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0, 1, 2, 3);

            // When: 0 and 1 fail together and 2 and 3 finish first
            rig.run(P0, 2).succeed();
            rig.run(P0, 3).succeed();
            rig.run(P0, 0).fail();
            rig.run(P0, 1).fail();

            // Then: nothing is committed past the failed records
            assertEquals(List.of(), rig.committed(P0));

            // And: once 0 retries and succeeds, only 0 is committed
            rig.pendingTimers().get(0).fire();
            rig.run(P0, 0).succeed();
            assertEquals(List.of(1L), rig.committed(P0));

            // And: once 1 retries and succeeds, the whole prefix is
            rig.pendingTimers().get(0).fire();
            rig.run(P0, 1).succeed();
            assertEquals(List.of(1L, 4L), rig.committed(P0));
        }

        @Test
        @DisplayName("a record that is only queued keeps the watermark below it")
        void queuedRecordHoldsTheWatermark() {
            // Given: one permit and three registered records
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0, 1, 2);
            assertEquals(1, rig.port.runs.size(), "only one record has a permit");

            // When: they finish in turn
            rig.run(P0, 0).succeed();
            assertEquals(List.of(1L), rig.committed(P0));
            rig.run(P0, 1).succeed();
            rig.run(P0, 2).succeed();

            // Then: each commit is the offset after the longest finished prefix
            assertEquals(List.of(1L, 2L, 3L), rig.committed(P0));
        }

        @Test
        @DisplayName("filtered, ignored and recovered records settle like any other and are committed")
        void skippedAndRecoveredRecordsSettle() {
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0, 1, 2);

            rig.run(P0, 1).settle(KafkaTerminalOutcome.RECOVERED);
            rig.run(P0, 2).settle(KafkaTerminalOutcome.SKIP);
            assertEquals(List.of(), rig.committed(P0), "0 has not finished");

            rig.run(P0, 0).settle(KafkaTerminalOutcome.SKIP);
            assertEquals(List.of(3L), rig.committed(P0));
        }

        @Test
        @DisplayName(
                "offset gaps do not hold the watermark, and a settled record above a gap waits for its predecessor")
        void offsetGaps() {
            // Given: offsets 0, 5 and 9, as a compacted topic or a transaction marker leaves them
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0, 5, 9);

            // When: 9 and 5 finish before 0
            rig.run(P0, 9).succeed();
            rig.run(P0, 5).succeed();
            assertEquals(List.of(), rig.committed(P0));

            // Then: 0 releases the whole prefix, and the commit is the offset after the last record
            rig.run(P0, 0).succeed();
            assertEquals(List.of(10L), rig.committed(P0));
        }

        @Test
        @DisplayName("the commit carries the watermark of the submit, and a later one follows when it moved meanwhile")
        void commitInFlightCarriesItsOwnOffset() {
            // Given: a commit that the broker has not answered
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0, 1, 2);
            rig.run(P0, 0).succeed();
            assertEquals(List.of(1L), rig.committed(P0));

            // When: more records finish while it is in flight
            rig.run(P0, 1).succeed();
            rig.run(P0, 2).succeed();
            assertEquals(List.of(1L), rig.committed(P0), "one commit is in flight at a time");

            // Then: its acknowledgment records offset 1, and the next commit carries the new watermark
            rig.port.commits.get(0).result.complete();
            assertEquals(List.of(1L, 3L), rig.committed(P0));
        }

        @Test
        @DisplayName("each partition has its own watermark")
        void partitionsAreIndependent() {
            SettlementRig rig = new SettlementRig(settings(4, ErrorStrategy.SKIP, false));
            rig.assign(P0, P1).add(P0, 0, 1).add(P1, 0);

            rig.run(P1, 0).succeed();
            rig.run(P0, 1).succeed();

            assertEquals(List.of(1L), rig.committed(P1));
            assertEquals(List.of(), rig.committed(P0));
        }
    }

    // --- Concurrency ---

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("records run at once up to the permits, in no promised order of finishing")
        void runsConcurrentlyUpToThePermits() {
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            rig.assign(P0, P1).add(P0, 0, 1).add(P1, 0, 1);

            assertEquals(3, rig.settlement.permits());
            assertEquals(3, rig.port.runs.size());

            // When: the second record finishes first
            rig.run(P0, 1).succeed();

            // Then: the fourth record takes the freed permit
            assertEquals(3, rig.settlement.permits());
            assertEquals(4, rig.port.runs.size());
        }

        @Test
        @DisplayName("a record that waits for its retry holds no permit")
        void retryWaitHoldsNoPermit() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0, 1);

            rig.run(P0, 0).fail();

            assertEquals(1, rig.settlement.permits(), "the second record took the permit");
            assertEquals(1, rig.started(P0, 1));
            assertEquals(1, rig.pendingTimers().size());
        }

        @Test
        @DisplayName("the oldest queued record starts first, so a retried record reaches a permit")
        void oldestRecordStartsFirst() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0, 1, 2);
            rig.run(P0, 0).fail();
            // 1 runs now; 2 is queued behind it
            rig.pendingTimers().get(0).fire();
            assertEquals(0, rig.started(P0, 2));

            rig.run(P0, 1).succeed();

            assertEquals(2, rig.started(P0, 0), "the retried record starts before the later one");
            assertEquals(0, rig.started(P0, 2));
        }
    }

    // --- Failures ---

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a retry runs the record again with one more attempt, and nothing seeks")
        void retryReentersThePipeline() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 7);

            rig.run(P0, 7).fail();
            assertEquals(List.of(KafkaTerminalOutcome.RETRY_SCHEDULED), rig.port.completions);
            assertEquals(100L, rig.pendingTimers().get(0).delayMs, "the first backoff");
            rig.pendingTimers().get(0).fire();

            assertEquals(2, rig.started(P0, 7));
            assertEquals(1, rig.run(P0, 7).attempt);
        }

        @Test
        @DisplayName("an exhausted retry is dead-lettered, and committed only once the publish is acknowledged")
        void exhaustedRetryIsDeadLettered() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0);

            rig.run(P0, 0).fail();
            rig.pendingTimers().get(0).fire();
            rig.run(P0, 0).fail();
            assertEquals(200L, rig.pendingTimers().get(0).delayMs, "the backoff grows");
            rig.pendingTimers().get(0).fire();
            rig.run(P0, 0).fail();

            assertEquals(1, rig.port.publishes.size());
            assertEquals(List.of(), rig.committed(P0), "not committed before the publish is acknowledged");

            rig.port.publishes.get(0).result.complete();
            assertEquals(List.of(1L), rig.committed(P0));
            assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, rig.port.completions.get(rig.port.completions.size() - 1));
        }

        @Test
        @DisplayName("a terminal failure is dead-lettered at once, without a retry")
        void terminalFailureSkipsTheRetries() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0);

            rig.run(P0, 0).fail(new KafkaTerminalFailure("Poison"));

            assertEquals(1, rig.port.publishes.size());
            assertEquals(0, rig.pendingTimers().size());
        }

        @Test
        @DisplayName("a skip settles the record at once")
        void skipSettles() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0);

            rig.run(P0, 0).fail();

            assertEquals(List.of(1L), rig.committed(P0));
            assertEquals(List.of(KafkaTerminalOutcome.SKIP), rig.port.completions);
        }

        @Test
        @DisplayName("a dead letter whose publish fails stays pending, is published again and holds the commit")
        void failedPublishIsMadeAgain() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0, 1);
            rig.run(P0, 1).succeed();

            // When: the publish for 0 fails
            rig.run(P0, 0).fail();
            rig.port.publishes.get(0).result.fail("broker down");

            // Then: the record is not committed and its publish is made again after a backoff
            assertEquals(List.of(), rig.committed(P0));
            assertEquals(1, rig.pendingTimers().size());
            rig.pendingTimers().get(0).fire();
            assertEquals(2, rig.port.publishes.size());
            rig.port.publishes.get(1).result.complete();
            assertEquals(List.of(2L), rig.committed(P0));
            assertEquals(
                    List.of(KafkaTerminalOutcome.SUCCESS, KafkaTerminalOutcome.DLQ_FAILED),
                    rig.port.completions.subList(0, 2));
        }

        @Test
        @DisplayName("a publish that throws is a failed publish")
        void throwingPublishIsAFailedPublish() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();

            rig.port.publishes.get(0).result.fail(new IllegalStateException("producer closed"));

            assertEquals(List.of(), rig.committed(P0));
            assertEquals(1, rig.pendingTimers().size());
        }

        @Test
        @DisplayName("a publish whose acknowledgment was lost is made again, and only the acknowledged one settles")
        void ambiguousPublishIsMadeAgain() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();

            // The first publish reached the broker and its answer was lost: the caller sees a failure
            rig.port.publishes.get(0).result.fail("timeout");
            rig.pendingTimers().get(0).fire();
            rig.port.publishes.get(1).result.complete();

            assertEquals(2, rig.port.publishes.size(), "the dead letter may appear twice, and never zero times");
            assertEquals(List.of(1L), rig.committed(P0));
        }

        @Test
        @DisplayName("a record has one publish outstanding at a time")
        void onePublishAtATime() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();

            assertEquals(1, rig.port.publishes.size());
            assertEquals(0, rig.pendingTimers().size());
        }

        @Test
        @DisplayName("an ordinary binding holds the partition's fetching for as long as one of its records waits")
        void backoffIsDerivedFromTheRetryWaits() {
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0, 1);
            rig.run(P0, 0).fail();
            rig.run(P0, 1).fail();
            assertTrue(rig.clientPaused(P0));

            rig.pendingTimers().get(0).fire();
            assertTrue(rig.clientPaused(P0), "the second retry still waits");

            rig.pendingTimers().get(0).fire();
            assertFalse(rig.clientPaused(P0), "both have ended");
        }

        @Test
        @DisplayName("a binding that forces its strategies has no partition backoff")
        void forcedBindingHasNoPartitionBackoff() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.RETRY, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();

            assertFalse(rig.clientPaused(P0));
            assertEquals(1, rig.pendingTimers().size());
        }
    }

    // --- Assignment ---

    @Nested
    @DisplayName("assignments")
    class Assignments {

        @Test
        @DisplayName("a handler of a revoked assignment that finishes after the partition came back changes nothing")
        void staleResultAfterReassignment() {
            // Given: offset 0 runs, the partition is revoked and assigned again, and the new assignment reads 0 again
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0);
            SettlementRig.Run old = rig.run(P0, 0);
            rig.revoke(P0).assign(P0).add(P0, 0);
            SettlementRig.Run fresh = rig.run(P0, 0);
            assertEquals(2, rig.settlement.permits(), "the old handler keeps its permit until it finishes");

            // When: the old handler finishes first
            old.succeed();

            // Then: it frees its permit and settles nothing
            assertEquals(1, rig.settlement.permits());
            assertEquals(List.of(), rig.committed(P0));

            // And: the new assignment's own run settles the record
            fresh.succeed();
            assertEquals(List.of(1L), rig.committed(P0));
        }

        @Test
        @DisplayName("a result of a revoked assignment publishes nothing, starts no timer and notifies nobody")
        void staleResultTouchesNothing() {
            // Given: a record of the first assignment runs while the partition goes away and comes back
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0, 1);
            SettlementRig.Run failing = rig.run(P0, 0);
            SettlementRig.Run succeeding = rig.run(P0, 1);
            rig.revoke(P0).assign(P0).add(P0, 5);
            long retained = rig.settlement.retainedRecords();

            // When: both finish, one failing and one succeeding
            failing.fail();
            succeeding.succeed();

            // Then: no dead letter, no commit, no notification, and the new assignment's counts are untouched
            assertEquals(0, rig.port.publishes.size());
            assertEquals(List.of(), rig.committed(P0));
            assertEquals(List.of(), rig.port.completions);
            assertEquals(retained, rig.settlement.retainedRecords());
            assertEquals(1, rig.settlement.permits(), "only the new assignment's record holds a permit");
        }

        @Test
        @DisplayName(
                "a revocation releases the records of the ended assignment though the closed gate keeps the queue still")
        void revocationReleasesTheQueuedEntries() {
            // Given a closed gate, so the queue is never polled, and a consumer whose partition keeps coming and going
            AtomicBoolean open = new AtomicBoolean(false);
            SettlementRig rig =
                    new SettlementRig(settings(2, ErrorStrategy.SKIP, true, 1_000, 1L << 30, new KafkaDispatchGate() {
                        @Override
                        public boolean open() {
                            return open.get();
                        }
                    }));
            List<KafkaPartitionLedger.Entry> everEnded = new java.util.ArrayList<>();

            for (int round = 0; round < 5; round++) {
                rig.assign(P0).add(P0, 0, 1, 2);
                assertEquals(3, rig.settlement.queuedEntries());
                KafkaPartitionLedger ledger = rig.settlement.ledger(P0);
                ledger.entries().forEach(everEnded::add);

                // When the partition is revoked
                rig.revoke(P0);

                // Then no entry of it is queued, no ledger holds one, and none still holds its record
                assertEquals(0, rig.settlement.queuedEntries(), "round " + round);
                assertEquals(0, ledger.size(), "round " + round);
            }
            assertEquals(15, everEnded.size());
            assertTrue(everEnded.stream().allMatch(entry -> entry.record == null && entry.cause == null));
            assertEquals(0, rig.settlement.retainedRecords());
            assertEquals(0, rig.settlement.retainedBytes());
        }

        @Test
        @DisplayName("a retry timer of a revoked assignment does not start the record")
        void staleRetryTimer() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();
            FakeTimer timer = rig.pendingTimers().get(0);

            rig.revoke(P0).assign(P0);
            assertTrue(timer.cancelled, "the revocation cancels the timer");
            timer.fire();

            assertEquals(1, rig.started(P0, 0));
        }

        @Test
        @DisplayName("a dead-letter acknowledgment of a revoked assignment does not settle the new one")
        void staleDeadLetterAcknowledgment() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.DEAD_LETTER, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();
            SettlementRig.Publish publish = rig.port.publishes.get(0);

            rig.revoke(P0).assign(P0).add(P0, 0);
            publish.result.complete();

            assertEquals(List.of(), rig.committed(P0));
            assertEquals(2, rig.started(P0, 0));
        }

        @Test
        @DisplayName("a commit acknowledgment of a revoked assignment does not move the new assignment's lastAcked")
        void staleCommitAcknowledgment() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).succeed();
            SettlementRig.Commit commit = rig.port.commits.get(0);

            rig.revoke(P0).assign(P0).add(P0, 0);
            commit.result.complete();
            rig.run(P0, 0).succeed();

            assertEquals(List.of(1L, 1L), rig.committed(P0), "the new assignment commits for itself");
        }

        @Test
        @DisplayName("a revocation commits the settled prefix as a best effort and drops the rest")
        void revocationCommitsTheSettledPrefix() {
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0, 1, 2);
            rig.run(P0, 0).succeed();
            rig.run(P0, 2).succeed();
            // 0's commit is in flight and 1 is unfinished: nothing more is sent

            rig.revoke(P0);

            assertEquals(List.of(1L), rig.committed(P0));
            assertEquals(0, rig.settlement.retainedRecords());
        }

        @Test
        @DisplayName("a record of a partition that is not assigned is dropped")
        void recordOfAnUnassignedPartitionIsDropped() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.assign(P0);

            rig.settlement.register(record(P1, 0));

            assertEquals(0, rig.port.runs.size());
        }

        @Test
        @DisplayName("the attempts of a new assignment start at zero")
        void attemptsStartAtZero() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();
            rig.pendingTimers().get(0).fire();
            assertEquals(1, rig.run(P0, 0).attempt);

            rig.revoke(P0).assign(P0).add(P0, 0);

            assertEquals(0, rig.run(P0, 0).attempt);
        }
    }

    // --- Commit failures and restart ---

    @Nested
    @DisplayName("commit failures and restarts")
    class CommitFailures {

        @Test
        @DisplayName("a failed commit is submitted again with the watermark of that moment")
        void failedCommitIsSubmittedAgain() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0, 1);
            rig.run(P0, 0).succeed();

            rig.port.commits.get(0).result.fail("broker unavailable");
            assertEquals(1, rig.pendingTimers().size(), "the watermark is submitted again after a wait");

            // The watermark moves meanwhile: the next commit carries the offset of that moment
            rig.run(P0, 1).succeed();
            assertEquals(List.of(1L, 2L), rig.committed(P0));
        }

        @Test
        @DisplayName("a commit whose acknowledgment was lost is submitted again and is not an error")
        void lostAcknowledgment() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).succeed();
            rig.port.commits.get(0).result.fail("timeout, though the broker stored it");

            rig.pendingTimers().get(0).fire();

            assertEquals(List.of(1L, 1L), rig.committed(P0), "committing the same offset twice is harmless");
        }

        @Test
        @DisplayName("a restart reads the uncommitted records again and commits them once they finish")
        void restartReplaysUncommittedRecords() {
            // Given: offset 0 finished and was committed; 1 finished but 2 did not, so the consumer then crashes
            SettlementRig first = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            first.assign(P0).add(P0, 0, 1, 2);
            first.run(P0, 0).succeed();
            first.run(P0, 2).succeed();
            long brokerOffset = first.committed(P0).get(first.committed(P0).size() - 1);
            assertEquals(1L, brokerOffset);

            // When: a new consumer starts empty and reads from the committed offset
            SettlementRig second = new SettlementRig(settings(3, ErrorStrategy.SKIP, false));
            second.assign(P0).add(P0, 1, 2);

            // Then: the records after the commit run again, and the watermark follows them
            assertEquals(1, second.started(P0, 1));
            assertEquals(1, second.started(P0, 2));
            second.run(P0, 1).succeed();
            second.run(P0, 2).succeed();
            assertEquals(List.of(2L, 3L), second.committed(P0));
        }
    }

    // --- Retention ---

    @Nested
    @DisplayName("retention bounds")
    class Retention {

        @Test
        @DisplayName("a partition stops fetching at its record bound and fetches again at half of it")
        void partitionRecordBound() {
            // Given: a consumer bound of 8 records, so 2 per partition
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false, 8, 1L << 30, null));
            rig.assign(P0).add(P0, 0);
            assertFalse(rig.clientPaused(P0));

            // When: a second record reaches the bound
            rig.add(P0, 1);
            assertTrue(rig.clientPaused(P0));

            // And: the fetch that was already under way still registers its records
            rig.add(P0, 2);
            assertEquals(3, rig.settlement.ledger(P0).size());

            // Then: the partition fetches again once the count is at half the bound
            rig.run(P0, 0).succeed();
            assertTrue(rig.clientPaused(P0), "two records are still held");
            rig.run(P0, 1).succeed();
            assertFalse(rig.clientPaused(P0), "one record is held, which is half the bound");
        }

        @Test
        @DisplayName("a partition stops fetching at its byte bound")
        void partitionByteBound() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false, 1_000, 4_000, null));
            rig.assign(P0);

            rig.settlement.register(record(P0, 0, 2_000));
            assertTrue(rig.clientPaused(P0), "a partition may use a quarter of the consumer's bytes");
            rig.run(P0, 0).succeed();
            assertFalse(rig.clientPaused(P0), "a settled record releases its payload at once");
        }

        @Test
        @DisplayName("the consumer bound stops every partition, though each holds a single record")
        void consumerBound() {
            // Given: a consumer bound of 8 records over eight partitions, and one permit
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false, 8, 1L << 30, null));
            TopicPartitionSet partitions = new TopicPartitionSet(8);
            rig.settlement.assigned(partitions.all());

            // When: seven partitions each hold one record
            for (int i = 0; i < 7; i++) {
                rig.settlement.register(record(partitions.get(i), 0));
            }
            for (int i = 0; i < 7; i++) {
                assertFalse(rig.clientPaused(partitions.get(i)));
            }

            // And: the eighth record reaches the consumer bound
            rig.settlement.register(record(partitions.get(7), 0));

            // Then: every partition stops fetching
            for (int i = 0; i < 8; i++) {
                assertTrue(rig.clientPaused(partitions.get(i)), "partition " + i);
            }

            // And: they fetch again once the consumer is at half of its bound
            for (int i = 0; i < 4; i++) {
                rig.run(partitions.get(i), 0).succeed();
            }
            assertEquals(4, rig.settlement.retainedRecords());
            for (int i = 0; i < 8; i++) {
                assertFalse(rig.clientPaused(partitions.get(i)), "partition " + i);
            }
        }

        @Test
        @DisplayName("settled records above a gap still count as records, and release their payload at once")
        void settledRecordsAboveAGap() {
            SettlementRig rig = new SettlementRig(settings(3, ErrorStrategy.SKIP, false, 100, 1L << 30, null));
            rig.assign(P0).add(P0, 0, 1, 2);
            long bytes = rig.settlement.retainedBytes();

            rig.run(P0, 1).succeed();
            rig.run(P0, 2).succeed();

            assertEquals(3, rig.settlement.retainedRecords(), "0 holds the gap open");
            assertTrue(rig.settlement.retainedBytes() < bytes, "two payloads were released");
        }

        @Test
        @DisplayName("a revoked partition gives its records back to the consumer's bound")
        void revocationReleasesRetention() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false, 8, 1L << 30, null));
            rig.assign(P0).add(P0, 0, 1, 2);
            assertEquals(3, rig.settlement.retainedRecords());

            rig.revoke(P0);

            assertEquals(0, rig.settlement.retainedRecords());
            assertEquals(0, rig.settlement.retainedBytes());
        }

        @Test
        @DisplayName("every permit held, every retry slot waiting and the buffer full still make progress")
        void fullBuffersMakeProgress() {
            // Given: two permits, a partition bound of 2 and records that all fail once
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.RETRY, false, 8, 1L << 30, null));
            rig.assign(P0).add(P0, 0, 1, 2, 3);
            rig.run(P0, 0).fail();
            rig.run(P0, 1).fail();
            rig.run(P0, 2).fail();
            rig.run(P0, 3).fail();
            assertTrue(rig.clientPaused(P0), "full, and waiting for retries");
            assertEquals(0, rig.settlement.permits(), "retry waits hold no permit");

            // When: the retries fire
            for (FakeTimer timer : rig.pendingTimers()) {
                timer.fire();
            }

            // Then: the records run again and finish, and the partition fetches again
            for (long offset = 0; offset < 4; offset++) {
                rig.run(P0, offset).succeed();
            }
            List<Long> commits = rig.committed(P0);
            assertEquals(4L, commits.get(commits.size() - 1));
            assertFalse(rig.clientPaused(P0));
        }
    }

    /** A set of partitions of one topic. */
    private static final class TopicPartitionSet {

        private final List<io.vertx.kafka.client.common.TopicPartition> partitions;

        TopicPartitionSet(int count) {
            partitions = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                partitions.add(new io.vertx.kafka.client.common.TopicPartition(SettlementRig.TOPIC, 10 + i));
            }
        }

        io.vertx.kafka.client.common.TopicPartition get(int index) {
            return partitions.get(index);
        }

        List<io.vertx.kafka.client.common.TopicPartition> all() {
            return partitions;
        }
    }

    // --- The gate ---

    @Nested
    @DisplayName("the dispatch gate")
    class Gate {

        @Test
        @DisplayName("a closed gate starts nothing and stops every partition from fetching, until it opens")
        void closedGate() {
            AtomicBoolean open = new AtomicBoolean(false);
            SettlementRig rig =
                    new SettlementRig(settings(2, ErrorStrategy.RETRY, true, 1_000, 1L << 30, gateOf(open)));
            rig.assign(P0, P1);

            // When: records arrive while the gate is closed
            rig.add(P0, 0).add(P1, 0);

            // Then: none starts and both partitions stop fetching
            assertEquals(0, rig.port.runs.size());
            assertTrue(rig.clientPaused(P0));
            assertTrue(rig.clientPaused(P1));

            // When: the gate opens and the consumer asks again
            open.set(true);
            rig.pendingTimers().get(0).fire();

            // Then: the queued records start and the partitions fetch again
            assertEquals(2, rig.port.runs.size());
            assertFalse(rig.clientPaused(P0));
            assertFalse(rig.clientPaused(P1));
        }

        @Test
        @DisplayName("records that were running when the gate closed finish and settle")
        void runningRecordsFinish() {
            AtomicBoolean open = new AtomicBoolean(true);
            SettlementRig rig =
                    new SettlementRig(settings(2, ErrorStrategy.RETRY, true, 1_000, 1L << 30, gateOf(open)));
            rig.assign(P0).add(P0, 0, 1);

            open.set(false);
            rig.add(P0, 2);
            rig.run(P0, 0).succeed();

            assertEquals(List.of(1L), rig.committed(P0));
            assertEquals(0, rig.started(P0, 2), "the closed gate keeps the third record from starting");
        }

        @Test
        @DisplayName("a refused record returns to the queue with no attempt spent and is never dead-lettered")
        void refusedRecordSpendsNoAttempt() {
            // Given: a forcing binding whose gate is open, and a handler that finds intake closed after it started
            AtomicBoolean open = new AtomicBoolean(true);
            SettlementRig rig =
                    new SettlementRig(settings(2, ErrorStrategy.RETRY, true, 1_000, 1L << 30, gateOf(open)));
            rig.assign(P0).add(P0, 0);

            // When: it is refused again and again
            for (int refusals = 0; refusals < 5; refusals++) {
                rig.run(P0, 0).fail(new KafkaRedeliverFailure("IntakeRefused"));
                assertEquals(0, rig.port.publishes.size(), "never dead-lettered");
                assertTrue(rig.clientPaused(P0), "the consumer holds fetching while it waits");
                rig.pendingTimers().get(rig.pendingTimers().size() - 1).fire();
            }

            // Then: the attempts are unchanged, the permit was released, and the record runs again
            assertEquals(6, rig.started(P0, 0));
            assertEquals(0, rig.run(P0, 0).attempt);
            rig.run(P0, 0).succeed();
            assertEquals(List.of(1L), rig.committed(P0));
            assertEquals(0, rig.settlement.permits());
        }

        @Test
        @Timeout(10)
        @DisplayName(
                "a handler that refuses in the same call is not started again in a loop, but when the consumer asks again")
        void immediateRefusalDoesNotSpin() {
            // Given runs that refuse the moment they start, as a handler that finds intake closed would
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.RETRY, true));
            rig.port.onRun = run -> {
                if (rig.port.runs.size() > 50) {
                    throw new AssertionError("the refused record is started again and again");
                }
                run.fail(new KafkaRedeliverFailure("IntakeRefused"));
            };
            rig.assign(P0);

            // When records arrive
            rig.add(P0, 0, 1, 2);

            // Then one was started and refused, and none was started again
            assertEquals(1, rig.port.runs.size());
            assertTrue(rig.clientPaused(P0));

            // And: when the consumer asks again, the records start in the order they were read
            rig.port.onRun = null;
            rig.pendingTimers().get(rig.pendingTimers().size() - 1).fire();
            assertEquals(3, rig.port.runs.size(), "the refused record and the next one take the two permits");
            assertEquals(0, rig.port.runs.get(1).offset());
            assertEquals(1, rig.port.runs.get(2).offset());
        }

        @Test
        @DisplayName("a refusal outranks a retry budget: a record refused after failures keeps its attempts")
        void refusalKeepsEarlierAttempts() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, true));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();
            rig.pendingTimers().get(0).fire();
            assertEquals(1, rig.run(P0, 0).attempt);

            rig.run(P0, 0).fail(new KafkaRedeliverFailure("IntakeRefused"));
            rig.pendingTimers().get(rig.pendingTimers().size() - 1).fire();

            assertEquals(1, rig.run(P0, 0).attempt, "the refusal spent nothing");
        }

        @Test
        @DisplayName("on a binding that does not force its strategies a redeliver failure is an ordinary failure")
        void redeliverFailureOnAnOrdinaryBinding() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0);

            rig.run(P0, 0).fail(new KafkaRedeliverFailure("IntakeRefused"));

            assertEquals(List.of(1L), rig.committed(P0));
        }

        private KafkaDispatchGate gateOf(AtomicBoolean open) {
            return new KafkaDispatchGate() {
                @Override
                public boolean open() {
                    return open.get();
                }

                @Override
                public long recheckMs() {
                    return 50L;
                }
            };
        }
    }

    // --- Stopping ---

    @Nested
    @DisplayName("stopping")
    class Stopping {

        @Test
        @DisplayName("a stop starts no record and no retry, finishes the running ones and commits their prefix")
        void stopDrains() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0, 1);

            rig.settlement.beginStop();
            assertTrue(rig.clientPaused(P0), "no partition fetches while stopping");
            // A record the client had already returned is still registered, and does not start
            rig.add(P0, 2);
            assertEquals(1, rig.port.runs.size());
            assertFalse(rig.settlement.drained(), "a handler still runs");

            rig.run(P0, 0).succeed();
            assertTrue(rig.settlement.drained());
            assertEquals(1, rig.port.runs.size(), "finishing a record starts no other");

            rig.settlement.commitSettled();
            assertEquals(List.of(1L), rig.committed(P0));
            rig.settlement.finishStop();
            assertEquals(0, rig.settlement.retainedRecords());
        }

        @Test
        @DisplayName("a retry timer that fires while stopping does not start the record")
        void retryTimerDoesNotStartWhileStopping() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.RETRY, false));
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).fail();

            rig.settlement.beginStop();
            rig.pendingTimers().get(0).fire();

            assertEquals(1, rig.started(P0, 0));
        }

        @Test
        @DisplayName("past the deadline the consumer commits the settled prefix and leaves the rest for a replay")
        void deadlineLeavesUnfinishedRecords() {
            SettlementRig rig = new SettlementRig(settings(2, ErrorStrategy.SKIP, false));
            rig.assign(P0).add(P0, 0, 1);
            rig.run(P0, 1).succeed();

            rig.settlement.beginStop();
            assertFalse(rig.settlement.drained());
            assertEquals(1, rig.settlement.running());
            rig.settlement.commitSettled();
            rig.settlement.finishStop();

            assertEquals(List.of(), rig.committed(P0), "0 is unfinished, so nothing is committed");
            rig.run(P0, 0).succeed();
            assertEquals(List.of(), rig.committed(P0), "a result after the stop commits nothing");
            assertNull(rig.settlement.ledger(P0));
        }

        @Test
        @DisplayName("a stop waits for a commit in flight")
        void stopWaitsForCommits() {
            SettlementRig rig = new SettlementRig(settings(1, ErrorStrategy.SKIP, false));
            rig.port.autoComplete = false;
            rig.assign(P0).add(P0, 0);
            rig.run(P0, 0).succeed();

            rig.settlement.beginStop();
            assertFalse(rig.settlement.drained());
            rig.port.commits.get(0).result.complete();
            assertTrue(rig.settlement.drained());
        }
    }
}
