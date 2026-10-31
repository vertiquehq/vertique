// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.KeyBehaviour;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.KeyShape;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.WaitRecord;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the rule every computed correlation key obeys, on the main path and in a branch: a non-blank string of at
 * most 255 characters with no NUL. A key that breaks it fails the segment of the wait with the typed error, which
 * names the definition and the step and never the value, and writes no record; when the wait was reached by applying a
 * signal, the signal is rejected and the instance stays where it was.
 *
 * <p>The instance row of a failed start holds the executor's sanitized reason, not the exception, so the type and the
 * message of the failure are read from what the executor logs.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WaitKeyRuleIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_wait_key_rule_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String KEY_ERROR = "WorkflowCorrelationKeyException";

    /** The step the keyed wait sits at, by placement. */
    private static String stepOf(KeyShape shape) {
        return shape == KeyShape.MAIN ? "wait-payment" : "wait-a";
    }

    /** A key of exactly the length, whose prefix and padding no message or history entry may repeat. */
    private static String keyOfLength(int length) {
        return "LONGKEY-" + "z".repeat(length - "LONGKEY-".length());
    }

    private static WorkflowInstanceId start(KeyedWaitFixture f, Node node, KeyShape shape, String definitionId) {
        return shape == KeyShape.MAIN
                ? f.start(node, definitionId, "ref-1", KeyedWaitFixture.SCOPE)
                : f.startFork(node, definitionId, "ref-1", "ref-2");
    }

    private static void runToIdle(Node node, WorkflowInstanceId... ids) {
        node.startExecutor();
        for (WorkflowInstanceId id : ids) {
            node.awaitIdle(id);
        }
    }

    /**
     * Asserts that the start of the instance failed in the segment of the wait: the instance is failed, nothing of the
     * segment is left, the executor logged the typed error, and its message names the definition and the step.
     */
    private static void assertRefused(KeyedWaitFixture f, WorkflowInstanceId id, String definitionId, KeyShape shape) {
        assertThat(f.fixture.instanceOf(id).status()).isEqualTo("FAILED");
        assertThat(f.waitRecords(id)).isEmpty();
        assertThat(f.branchTokens(id)).isEmpty();
        assertThat(f.loggedText(Level.ERROR)).contains(KEY_ERROR);
        assertThat(f.loggedMessagesOf(KEY_ERROR, Level.TRACE))
                .anySatisfy(message -> assertThat(message).contains(definitionId, stepOf(shape)));
    }

    /** Asserts that no stored error, history entry or log entry repeats any of the values. */
    private static void assertNeverRepeated(KeyedWaitFixture f, WorkflowInstanceId id, String... values) {
        assertThat(f.storedErrorText(id)).doesNotContain(values);
        assertThat(String.join("\n", f.fixture.historyTextsOf(id))).doesNotContain(values);
        assertThat(f.loggedText(Level.TRACE)).doesNotContain(values);
        assertThat(f.loggedMessagesOf(KEY_ERROR, Level.TRACE))
                .allSatisfy(message -> assertThat(message).doesNotContain(values));
    }

    @ParameterizedTest
    @EnumSource(KeyShape.class)
    @DisplayName("a key over 255 characters fails the wait's segment, and exactly 255 is accepted")
    void keyOverTheLimitFailsTheWaitSegmentWithTheTypedErrorAndExactlyTheLimitIsAccepted(KeyShape shape) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given definitions whose accessor returns a string of 255, 256 and 300 characters
            String atLimit = keyOfLength(255);
            String atLimitId = f.keyRule(shape, "at-limit", reference -> atLimit);
            String justOverId = f.keyRule(shape, "just-over", reference -> keyOfLength(256));
            String overId = f.keyRule(shape, "over", reference -> keyOfLength(300));
            Node node = f.node("engine").build();
            WorkflowInstanceId accepted = start(f, node, shape, atLimitId);
            WorkflowInstanceId justOver = start(f, node, shape, justOverId);
            WorkflowInstanceId over = start(f, node, shape, overId);

            // When the instances start and the executor runs
            runToIdle(node, accepted, justOver, over);

            // Then with exactly 255 characters the wait is entered and its record holds the key unchanged; an instance
            // waits at the wait itself on the main path, and runs while a branch of its fork waits
            assertThat(f.fixture.instanceOf(accepted).status())
                    .isEqualTo(shape == KeyShape.MAIN ? "WAITING" : "RUNNING");
            assertThat(f.branchTokens(accepted))
                    .allMatch(token -> shape == KeyShape.MAIN || token.status().equals("WAITING"));
            assertThat(f.waitRecords(accepted))
                    .singleElement()
                    .extracting(WaitRecord::correlationKey)
                    .isEqualTo(atLimit);

            // And with 256 and with 300 characters the start fails, the typed error names the definition and the
            // step, no record exists, and no part of the value is repeated
            assertRefused(f, justOver, justOverId, shape);
            assertRefused(f, over, overId, shape);
            assertNeverRepeated(f, justOver, "LONGKEY", "zzzzzzzz");
            assertNeverRepeated(f, over, "LONGKEY", "zzzzzzzz");
        }
    }

    static Stream<Arguments> unusableKeys() {
        return Stream.of(KeyShape.values())
                .flatMap(shape -> Stream.of(KeyBehaviour.NULL, KeyBehaviour.BLANK, KeyBehaviour.THROWING)
                        .map(behaviour -> Arguments.of(shape, behaviour)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unusableKeys")
    @DisplayName("a null, blank or throwing key fails the wait's segment and writes no record")
    void nullBlankOrThrowingKeyFailsTheWaitSegmentWithTheTypedErrorAndWritesNoRecord(
            KeyShape shape, KeyBehaviour behaviour) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a definition whose accessor returns no key, blanks, or throws an exception that names a secret
            String definitionId =
                    f.keyRule(shape, behaviour.name().toLowerCase(java.util.Locale.ROOT), behaviour.accessor);
            Node node = f.node("engine").build();
            WorkflowInstanceId id = start(f, node, shape, definitionId);

            // When the instance starts and the executor runs
            runToIdle(node, id);

            // Then the start fails with the typed error, which names the definition and the step, and no record exists
            assertRefused(f, id, definitionId, shape);

            // And neither the secret nor the accessor's own message is repeated anywhere
            assertNeverRepeated(f, id, KeyedWaitFixture.SECRET, "lookup failed");
        }
    }

    @ParameterizedTest
    @EnumSource(KeyShape.class)
    @DisplayName("a key containing a NUL fails the wait's segment and writes no record")
    void keyContainingANulFailsTheWaitSegmentWithTheTypedErrorAndWritesNoRecord(KeyShape shape) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a definition whose accessor returns a key with a NUL inside
            UnaryOperator<String> accessor = KeyBehaviour.NUL.accessor;
            String definitionId = f.keyRule(shape, "nul", accessor);
            Node node = f.node("engine").build();
            WorkflowInstanceId id = start(f, node, shape, definitionId);

            // When the instance starts and the executor runs
            runToIdle(node, id);

            // Then the start fails with the typed error, which names the definition and the step, and no record exists
            assertRefused(f, id, definitionId, shape);

            // And the key, or any part of it, is repeated nowhere
            assertNeverRepeated(f, id, KeyedWaitFixture.NUL_KEY, "PAY-", "\0");
        }
    }

    @Test
    @DisplayName("a signal that reaches a wait with a bad key is rejected and the instance stays")
    void signalReachingAWaitWithABadKeyIsRejectedAndTheInstanceStays() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance waiting at wait-a with a valid key, whose next wait, wait-b, computes a blank key from
            // what the signal of wait-a puts in the state
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.start(node, KeyedWaitFixture.CHAIN, "PAY-1", KeyedWaitFixture.SCOPE);
            runToIdle(node, id);
            JsonObject before = f.snapshot(id);
            List<WaitRecord> recordsBefore = f.waitRecords(id);
            assertThat(recordsBefore)
                    .singleElement()
                    .extracting(WaitRecord::stepId)
                    .isEqualTo("wait-a");

            // When a caller records a-done with a payload that makes the key blank, and the instance is idle again
            f.signalInstance(node, id, "a-done", new Payment("   "), "k-1");
            node.awaitIdle(id);

            // Then the command is rejected and the instance is as before
            assertThat(f.signalCommands(id)).singleElement().satisfies(command -> {
                assertThat(command.state()).isEqualTo("REJECTED");
            });
            assertThat(f.snapshot(id)).isEqualTo(before);

            // And the record of wait-a still exists and none exists for wait-b
            assertThat(f.waitRecords(id)).isEqualTo(recordsBefore);

            // And a log entry names the definition and wait-b as the rejection
            assertThat(f.loggedEntries(Level.WARN))
                    .anySatisfy(entry -> assertThat(entry).contains(KeyedWaitFixture.CHAIN, "wait-b"));

            // When the caller records the same signal again with the same dedup key
            f.signalInstance(node, id, "a-done", new Payment("   "), "k-1");
            node.awaitIdle(id);

            // Then the call succeeds, adds no command, and no signal is left open
            assertThat(f.signalCommands(id)).hasSize(1);
            assertThat(f.signalDedupRows(id, "k-1")).isEqualTo(1);
            assertThat(f.openSignals(id)).isZero();
        }
    }
}
