// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.lifecycle.ComposeValidationStep;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Behavior-preservation proof: core's startup check still warns when the framework owns the process
 * JSON codec but nothing installed a profile before the VALIDATE-phase check runs.
 *
 * <p>The log assertion attaches a {@link ListAppender} to the root logger, so it pins the emitted
 * level and text rather than the logger name.
 */
class NoJsonRuntimeWarningIT {

    @BeforeEach
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    @Test
    @DisplayName("ComposeValidationStep warns when no JSON profile was installed before the check")
    void warnsWhenNoProfileWasInstalled() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            // Given: nothing has installed or used a JSON profile
            assertTrue(VertiqueJson.profile().isEmpty(), "no profile is installed after reset");

            // When: the VALIDATE-phase step runs
            boolean succeeded = new ComposeValidationStep(Set::of).start().succeeded();

            // Then: the step succeeds and the missing-install warning is logged at WARN
            assertTrue(succeeded, "the step itself must still succeed");
            boolean warned = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("No JSON profile was installed"));
            assertTrue(warned, "expected the 'No JSON profile was installed' WARN; got: " + appender.list);
        } finally {
            root.detachAppender(appender);
        }
    }
}
