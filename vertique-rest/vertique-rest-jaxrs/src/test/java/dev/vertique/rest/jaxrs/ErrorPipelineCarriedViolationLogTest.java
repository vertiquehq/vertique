// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.ViolationCarrier;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.core.validation.ViolationPathForm;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves what the error pipeline logs about the violations a carrier holds: the paths a client could
 * have shaped are neutralized or left out, never the message, a key, an index or a pointer, and the
 * count per response is bounded.
 */
class ErrorPipelineCarriedViolationLogTest {

    /** A carrier whose detail paths are JSON Pointers and which declares so. */
    private static final class PointerCarrier extends BusinessRuleException implements ViolationCarrier {
        private final List<ViolationDetail> violations;

        PointerCarrier(List<ViolationDetail> violations) {
            super("invalid payload");
            this.violations = violations;
        }

        @Override
        public List<ViolationDetail> violations() {
            return violations;
        }

        @Override
        public ViolationPathForm violationPathForm() {
            return ViolationPathForm.JSON_POINTER;
        }
    }

    private Logger pipelineLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void capturePipelineLogs() {
        pipelineLogger = (Logger) LoggerFactory.getLogger(ErrorPipeline.class);
        previousLevel = pipelineLogger.getLevel();
        pipelineLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        pipelineLogger.addAppender(appender);
    }

    @AfterEach
    void releasePipelineLogs() {
        pipelineLogger.detachAppender(appender);
        appender.stop();
        pipelineLogger.setLevel(previousLevel);
    }

    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a Java-path carrier logs a neutralized path with its type, never a key, an index or the message")
    void javaPathCarrierLogsNeutralizedPaths() {
        // Given: Java paths holding a map key shaped to break a naive renderer, a nested list in a map, and
        // the root, each with a message that must never be logged
        BeanValidationException carrier = new BeanValidationException(
                "validation failed",
                List.of(
                        new ViolationDetail("byKey[ZZKEY].name", "ZZMESSAGE", "required", null),
                        new ViolationDetail("byKey[a]ZZKEY].name", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a].ZZKEY", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a][0].ZZKEY", "ZZMESSAGE", "required", null),
                        new ViolationDetail("x[a].x", "ZZMESSAGE", "required", null),
                        new ViolationDetail("", "ZZMESSAGE", "required", null)));

        // When: the pipeline logs the carrier
        ErrorPipeline.logCarriedViolations(carrier);

        // Then: one event per non-empty path shows the path up to its first bracket, with the type
        List<String> messages = loggedMessages();
        assertEquals(2, messages.stream().filter(m -> m.contains("byKey[]")).count(), () -> messages.toString());
        assertEquals(3, messages.stream().filter(m -> m.contains("x[]")).count(), () -> messages.toString());
        assertEquals(5, messages.size(), () -> "the empty path must log nothing: " + messages);
        assertTrue(messages.stream().allMatch(m -> m.contains("required")), () -> messages.toString());

        // And: no event holds a key the client chose, any index, or the message
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage();
            assertFalse(text.contains("ZZKEY"), () -> "key logged: " + text);
            assertFalse(text.contains("ZZMESSAGE"), () -> "message logged: " + text);
            assertFalse(text.contains("[a"), () -> "key text logged: " + text);
            assertFalse(text.contains("[0"), () -> "index logged: " + text);
            for (Object argument : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                String rendered = String.valueOf(argument);
                assertFalse(rendered.contains("ZZKEY"), () -> "key logged: " + rendered);
                assertFalse(rendered.contains("ZZMESSAGE"), () -> "message logged: " + rendered);
            }
        }
    }

    @Test
    @DisplayName("a pointer carrier logs nothing for a well-formed pointer and only the type for a malformed one")
    void pointerCarrierLogsOnlyTheTypeOfAMalformedPointer() {
        // Given: a good pointer, a path with no leading slash, the empty pointer and an unknown escape
        PointerCarrier carrier = new PointerCarrier(List.of(
                new ViolationDetail("/comment", "ZZMESSAGE-good", "maxLength", null),
                new ViolationDetail("a/b", "ZZMESSAGE-slash", "pattern", null),
                new ViolationDetail("", "further violations omitted", "truncated", null),
                new ViolationDetail("/x~2", "ZZMESSAGE-escape", "format", null)));

        // When: the pipeline logs the carrier
        ErrorPipeline.logCarriedViolations(carrier);

        // Then: one event per malformed pointer names its type
        List<String> messages = loggedMessages();
        assertEquals(2, messages.size(), () -> messages.toString());
        assertTrue(messages.get(0).contains("pattern"), () -> messages.toString());
        assertTrue(messages.get(1).contains("format"), () -> messages.toString());

        // And: no event or argument holds a path or a message
        for (String forbidden : List.of("a/b", "x~2", "ZZMESSAGE", "/comment", "further violations omitted")) {
            for (ILoggingEvent event : appender.list) {
                assertFalse(
                        event.getFormattedMessage().contains(forbidden),
                        () -> forbidden + " logged: " + event.getFormattedMessage());
                for (Object argument : event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray()) {
                    assertFalse(
                            String.valueOf(argument).contains(forbidden),
                            () -> forbidden + " logged as an argument: " + argument);
                }
            }
        }
    }

    @Test
    @DisplayName("a pointer carrier of well-formed pointers logs nothing")
    void pointerCarrierOfWellFormedPointersLogsNothing() {
        PointerCarrier carrier =
                new PointerCarrier(List.of(new ViolationDetail("/comment", "too long", "maxLength", null)));

        ErrorPipeline.logCarriedViolations(carrier);

        assertTrue(loggedMessages().isEmpty(), () -> "unexpected events: " + loggedMessages());
    }

    @Test
    @DisplayName("a Java-path carrier logs at most 100 events per response")
    void javaPathCarrierLogsAtMostOneHundredEvents() {
        List<ViolationDetail> details = IntStream.range(0, 101)
                .mapToObj(i -> new ViolationDetail("field" + i, "must not be blank", "required", null))
                .toList();

        ErrorPipeline.logCarriedViolations(new BeanValidationException("validation failed", details));

        assertEquals(100, appender.list.size());
    }

    @Test
    @DisplayName("a pointer carrier logs at most 100 events per response")
    void pointerCarrierLogsAtMostOneHundredEvents() {
        List<ViolationDetail> details = IntStream.range(0, 101)
                .mapToObj(i -> new ViolationDetail("no-slash" + i, "bad", "format", null))
                .toList();

        ErrorPipeline.logCarriedViolations(new PointerCarrier(details));

        assertEquals(100, appender.list.size());
    }
}
