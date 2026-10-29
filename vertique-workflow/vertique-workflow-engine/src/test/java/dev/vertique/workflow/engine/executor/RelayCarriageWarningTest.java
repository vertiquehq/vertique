// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Verifies the warning the executor logs at startup when identity carriage is required for the outbox relay or for the
 * workflow: it names each required carriage and why it cannot be met, and logs nothing when neither is required.
 */
class RelayCarriageWarningTest {

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of(true, false, true, false),
                Arguments.of(false, true, false, true),
                Arguments.of(true, true, true, true),
                Arguments.of(false, false, false, false));
    }

    @ParameterizedTest(name = "outbox-relay required={0}, workflow required={1}")
    @MethodSource("configurations")
    void warnsOnceWhenOutboxRelayCarriageIsRequired(
            boolean required, boolean workflowRequired, boolean warnsForRelay, boolean warnsForWorkflow) {
        Logger logger = (Logger) LoggerFactory.getLogger(RelayCarriageWarning.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Given the carriage requirements the executor start was given
            OutboxRelayCarriage carriage = new OutboxRelayCarriage(required, workflowRequired);

            // When the executor start checks them
            RelayCarriageWarning.check(carriage);

            // Then there is one warning for each required carriage and none otherwise
            List<String> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
                    .toList();
            assertThat(warnings).hasSize((warnsForRelay ? 1 : 0) + (warnsForWorkflow ? 1 : 0));
            assertThat(warnings.stream().filter(message -> message.contains("outbox-relay")))
                    .hasSize(warnsForRelay ? 1 : 0)
                    .allSatisfy(message -> assertThat(message).contains("snapshot"));
            assertThat(warnings.stream().filter(message -> message.contains("executor drives")))
                    .hasSize(warnsForWorkflow ? 1 : 0)
                    .allSatisfy(
                            message -> assertThat(message).contains("snapshot").doesNotContain("outbox-relay"));
            assertThat(appender.list).hasSameSizeAs(warnings);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
