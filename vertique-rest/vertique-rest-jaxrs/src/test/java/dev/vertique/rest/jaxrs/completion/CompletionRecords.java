// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.completion;

import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What the completion fixture observed: every operation descriptor the recording contributor
 * received at router build, keyed by operation id, and every {@link RestRequestCompletedEvent} and
 * {@link HttpRequestCompletedEvent} the recording listeners received.
 *
 * <p>Both listeners count down the same armed latch, so a test can wait for a request's completion
 * whichever of the two events it produces, then read both lists.
 */
public final class CompletionRecords {

    private final Map<String, RestOperationDescriptor> registered = new ConcurrentHashMap<>();
    private final List<RestRequestCompletedEvent> restEvents = new CopyOnWriteArrayList<>();
    private final List<HttpRequestCompletedEvent> httpEvents = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch completion = new CountDownLatch(1);

    CompletionRecords() {}

    void recordRegistered(RestOperationDescriptor operation) {
        registered.put(operation.operationId(), operation);
    }

    void recordRest(RestRequestCompletedEvent event) {
        restEvents.add(event);
        completion.countDown();
    }

    void recordHttp(HttpRequestCompletedEvent event) {
        httpEvents.add(event);
        completion.countDown();
    }

    /**
     * Returns the descriptor the recording contributor received for {@code operationId}.
     *
     * @param operationId the operation id
     * @return the received descriptor, or {@code null} when no operation with that id was registered
     */
    public RestOperationDescriptor registered(String operationId) {
        return registered.get(operationId);
    }

    /**
     * Clears both event lists and arms a fresh completion latch. Call before sending a request.
     */
    public void resetEvents() {
        restEvents.clear();
        httpEvents.clear();
        completion = new CountDownLatch(1);
    }

    /**
     * Waits until either listener has received an event since the last {@link #resetEvents()}.
     *
     * @param timeout the maximum wait
     * @param unit    the unit of {@code timeout}
     * @return {@code true} when an event arrived within the bound
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completion.await(timeout, unit);
    }

    /**
     * Returns a snapshot of the REST completion events received since the last reset.
     *
     * @return the REST events, in dispatch order
     */
    public List<RestRequestCompletedEvent> restEvents() {
        return List.copyOf(restEvents);
    }

    /**
     * Returns a snapshot of the unclaimed-request completion events received since the last reset.
     *
     * @return the HTTP events, in dispatch order
     */
    public List<HttpRequestCompletedEvent> httpEvents() {
        return List.copyOf(httpEvents);
    }
}
