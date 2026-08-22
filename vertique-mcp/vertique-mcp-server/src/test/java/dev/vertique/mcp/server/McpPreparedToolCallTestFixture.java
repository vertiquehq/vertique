// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Framework wiring for {@link McpPreparedToolCallTest} (T014 TP-002).
 *
 * <p>{@link GeneratedInvoker} stands in for a real generated invoker's {@code prepare(...)} (T012's
 * frozen shape, {@code contracts/tool-authoring-and-dispatch.md} §4.2): it closes over one
 * descriptor, the cancellation signal, the effective mapper, and a materialized carrier — all four
 * held here, by this fixture, at the exact point they are passed in — and returns an
 * {@link McpPreparedToolCall} that exposes none of them, only {@code normalizedArguments()} and
 * {@code invoke()}.
 *
 * <p>{@link #NORMALIZED_VALUE} is deliberately not {@link #RAW_VALUE}: {@code prepare(...)} stands
 * in for the INP-001 normalization a later slice (T015) wires for real, so a test reading
 * {@code normalizedArguments()} cannot pass by accident on an unprocessed pass-through of the raw
 * wire argument.
 */
final class McpPreparedToolCallTestFixture {

    static final String ARGUMENT_KEY = "value";
    static final String RAW_VALUE = "  Raw Wire Value  ";
    static final String NORMALIZED_VALUE = "raw wire value";

    private final McpToolDescriptor descriptor;
    private final RecordingCancellationSignal cancellation;
    private final ObjectMapper effectiveMapper;
    private final Carrier materializedCarrier;
    private final McpPreparedToolCall preparedCall;

    private McpPreparedToolCallTestFixture(
            McpToolDescriptor descriptor,
            RecordingCancellationSignal cancellation,
            ObjectMapper effectiveMapper,
            Carrier materializedCarrier,
            McpPreparedToolCall preparedCall) {
        this.descriptor = descriptor;
        this.cancellation = cancellation;
        this.effectiveMapper = effectiveMapper;
        this.materializedCarrier = materializedCarrier;
        this.preparedCall = preparedCall;
    }

    /**
     * Builds the descriptor, cancellation signal, effective mapper, and materialized carrier, then
     * obtains the prepared call from {@link GeneratedInvoker#prepare}.
     *
     * @return the fixture, holding all four Given values at this exact capture point
     */
    static McpPreparedToolCallTestFixture prepare() {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "prepared-call.fixture",
                null,
                "T014 TP-002 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        RecordingCancellationSignal cancellation = new RecordingCancellationSignal();
        ObjectMapper effectiveMapper = new ObjectMapper();
        Carrier materializedCarrier = new Carrier(NORMALIZED_VALUE);

        GeneratedInvoker invoker = new GeneratedInvoker(descriptor, effectiveMapper, materializedCarrier);
        McpPreparedToolCall preparedCall = invoker.prepare(Map.of(ARGUMENT_KEY, RAW_VALUE), cancellation);

        return new McpPreparedToolCallTestFixture(
                descriptor, cancellation, effectiveMapper, materializedCarrier, preparedCall);
    }

    /** Returns the prepared call under test — the only value the test's assertions read. */
    McpPreparedToolCall preparedCall() {
        return preparedCall;
    }

    /** Returns the fixture-held cancellation signal, so the test can cancel it directly. */
    RecordingCancellationSignal cancellation() {
        return cancellation;
    }

    /** Returns the descriptor this fixture passed to {@code prepare(...)} — never read off the prepared call. */
    McpToolDescriptor descriptor() {
        return descriptor;
    }

    /** Returns the effective mapper this fixture passed to {@code prepare(...)} — never read off the prepared call. */
    ObjectMapper effectiveMapper() {
        return effectiveMapper;
    }

    /** Returns the materialized carrier this fixture passed to {@code prepare(...)} — never read off the prepared call. */
    Carrier materializedCarrier() {
        return materializedCarrier;
    }

    /** Stands in for a generated invoker's nested input-carrier record. */
    record Carrier(String value) {}

    /** A test-controlled {@link McpCancellationSignal} the fixture cancels directly. */
    static final class RecordingCancellationSignal implements McpCancellationSignal {
        private final Promise<Void> cancelledPromise = Promise.promise();
        private volatile boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Future<Void> cancelled() {
            return cancelledPromise.future();
        }

        /** Cancels this signal, firing every handler registered on {@link #cancelled()}. */
        void cancel() {
            cancelled = true;
            cancelledPromise.tryComplete();
        }
    }

    /**
     * Stands in for one {@code @McpTool}-generated invoker. {@code prepare(...)} closes the
     * descriptor, effective mapper, and materialized carrier — plus the cancellation signal it
     * receives as a parameter — into the returned {@link McpPreparedToolCall}, exposing none of them
     * as a member.
     */
    private static final class GeneratedInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final ObjectMapper effectiveMapper;
        private final Carrier materializedCarrier;

        GeneratedInvoker(McpToolDescriptor descriptor, ObjectMapper effectiveMapper, Carrier materializedCarrier) {
            this.descriptor = descriptor;
            this.effectiveMapper = effectiveMapper;
            this.materializedCarrier = materializedCarrier;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            // Stands in for INP-001 normalization (T015 owns the real request-time pipeline): produces
            // a value that deliberately differs from the raw wire argument above.
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put(ARGUMENT_KEY, NORMALIZED_VALUE);
            Map<String, Object> unmodifiableNormalizedArguments = Collections.unmodifiableMap(normalized);

            // This anonymous class is defined inside an instance method of GeneratedInvoker, so it
            // implicitly closes over this.descriptor, this.effectiveMapper, and this.materializedCarrier
            // — the real generated invocation (T015 owns the actual pipeline) would call the
            // application method through them. None of the three is exposed as a member below.
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return unmodifiableNormalizedArguments;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    Promise<McpToolResult<?>> settlement = Promise.promise();
                    // References descriptor/materializedCarrier/effectiveMapper (GeneratedInvoker's own
                    // fields) only to make the closure capture in the javadoc above real rather than
                    // asserted; the real generated invocation (T015) would call the application method
                    // through them instead. Never exposed as a member of this returned call.
                    cancellation
                            .cancelled()
                            .onSuccess(ignored -> settlement.tryFail(new CancellationException("prepared call for '"
                                    + descriptor.name() + "' (carrier=" + materializedCarrier.value() + ", mapper="
                                    + effectiveMapper.getClass().getSimpleName() + ") was cancelled")));
                    return settlement.future();
                }
            };
        }
    }
}
