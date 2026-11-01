// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.vertique.workflow.services.source.ObserverFixture.Built;
import dev.vertique.workflow.services.source.ObserverFixture.LogCapture;
import dev.vertique.workflow.services.source.ObserverFixture.RecordingObserver;
import dev.vertique.workflow.services.source.ObserverFixture.ThrowingObserver;
import dev.vertique.workflow.source.SignalSourceEvent;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowSignalSourceDelivery#report(SignalSourceEvent)} tells every registered
 * observer about an outcome and isolates each one: a throw from an observer never reaches the
 * caller, never stops the observers after it, and is handed to the bean's failure reporter by the
 * observer's class and the callback name alone.
 */
class WorkflowSignalSourceDeliveryObserverTest {

    private static final String SECRET = "PAY-77";

    @Test
    @DisplayName("report reaches every observer and a throwing observer stops none")
    void reportReachesEveryObserverAndAThrowingObserverStopsNone() {
        // Given a recording observer, an observer that throws, and a second recording observer, in that order
        RecordingObserver before = new RecordingObserver();
        IllegalStateException failure = new IllegalStateException(SECRET + " is secret");
        ThrowingObserver throwing = new ThrowingObserver(failure);
        RecordingObserver after = new RecordingObserver();
        Built built = ObserverFixture.deliveryWith(before, throwing, after);
        SignalSourceEvent event = ObserverFixture.recorded();

        try (LogCapture logs = new LogCapture()) {
            logs.probe("capture is attached");

            // When a RECORDED outcome is reported
            assertThatCode(() -> built.delivery().report(event)).doesNotThrowAnyException();

            // Then both recording observers received the one event
            assertThat(before.events()).containsExactly(event);
            assertThat(after.events()).containsExactly(event);

            // and the reporter received the throwing observer's class, the callback name and the exception
            verify(built.reporter()).report(ThrowingObserver.class, "onOutcome", failure);
            verifyNoMoreInteractions(built.reporter());

            // and nothing the bean logged holds the secret
            assertThat(logs.records()).anyMatch(text -> text.contains("capture is attached"));
            assertThat(logs.records()).noneMatch(text -> text.contains(SECRET));
        }
    }

    @Test
    @DisplayName("report also isolates an error from an observer and reports it without its message")
    void reportIsolatesAnErrorFromAnObserverAndReportsItWithoutItsMessage() {
        // Given, for each error, an observer that throws it followed by a recording observer
        List<Supplier<Throwable>> errors = List.of(
                () -> new AssertionError(SECRET), () -> new LinkageError(SECRET), () -> new StackOverflowError());

        try (LogCapture logs = new LogCapture()) {
            logs.probe("capture is attached");

            for (Supplier<Throwable> error : errors) {
                ThrowingObserver throwing = new ThrowingObserver(error.get());
                RecordingObserver after = new RecordingObserver();
                Built built = ObserverFixture.deliveryWith(throwing, after);
                SignalSourceEvent event = ObserverFixture.recorded();
                String errorName = throwing.failure().getClass().getSimpleName();

                // When an outcome is reported
                assertThatCode(() -> built.delivery().report(event))
                        .as("report returns normally when an observer throws %s", errorName)
                        .doesNotThrowAnyException();

                // Then the recording observer after it received the event
                assertThat(after.events()).as("event after %s", errorName).containsExactly(event);

                // and the reporter received the error itself
                verify(built.reporter()).report(ThrowingObserver.class, "onOutcome", throwing.failure());
                verifyNoMoreInteractions(built.reporter());
            }

            // and nothing the bean logged holds the secret
            assertThat(logs.records()).anyMatch(text -> text.contains("capture is attached"));
            assertThat(logs.records()).noneMatch(text -> text.contains(SECRET));
        }
    }
}
