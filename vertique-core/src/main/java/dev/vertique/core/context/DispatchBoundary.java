// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * Boundary identifiers passed to {@link DurableContextPropagator} and
 * {@link DispatchEnvelopeBuilder} to identify the propagation site.
 *
 * <p>Centralizing the strings avoids typos and makes the set of known boundaries discoverable.
 * Boundary modules contribute their identifiers here as they integrate the substrate.
 *
 * <p>The string values are part of the SPI contract because encoders and decoders may receive
 * the boundary as part of their {@link DurableEncodeContext} / {@link DurableDecodeContext} /
 * {@link ServiceDispatchEncodeContext} / {@link ServiceDispatchDecodeContext}.
 */
public final class DispatchBoundary {

    /** Model Context Protocol server request dispatch. */
    public static final String MCP = "mcp";

    /** Outgoing service-dispatch envelope built by the service client factory. */
    public static final String SERVICE_DISPATCH = "service-dispatch";

    /** Kafka producer-side merge and Kafka consumer-side bind. */
    public static final String KAFKA = "kafka";

    /** Outbox writer merge at enqueue. */
    public static final String OUTBOX = "outbox";

    /** Outbox relay service-destination handler bind. */
    public static final String OUTBOX_SERVICE = "outbox-service";

    /**
     * Delayed-job producer-side merge at enqueue and consumer-side decode at poll. Used by
     * {@code DelayedJobService.toExecution} to merge ambient durable context into the persisted
     * {@code job_executions.metadata} row, and by {@code DelayedJobPoller.dispatch} to decode that
     * metadata into the outgoing service envelope's caller-overrides on the non-duplicated poller
     * context (FR-CTX-172 path 2).
     */
    public static final String DELAYED_JOB = "delayed-job";

    /**
     * Workflow producer-side merge for native workflow carriers (branch tokens). Used by
     * {@code PgWorkflowEngine} at branch creation to capture ambient durable context into
     * {@code workflow_branch_tokens.metadata}, and by {@code BranchTransitionEngine.runBranchDispatch}
     * and the branch-recovery sweep to bind the persisted metadata back via {@code bindFrom}
     * before invoking the branch advance.
     *
     * <p>Workflow timer durable propagation rides on the {@link #DELAYED_JOB} boundary, not this
     * one — timer fire flows through the delayed-job dispatch pipeline, so timer metadata must be
     * encoded for that boundary's decoders.
     */
    public static final String WORKFLOW = "workflow";

    /**
     * External-adapter inbound handling scope opened when resolving context and building the
     * dispatch envelope for a triggered inbound exchange.
     *
     * <p>Used as the boundary identifier passed to
     * {@link dev.vertique.context.InboundExecutionContextScope#installDispatch} and to
     * {@link dev.vertique.context.DispatchEnvelopeBuilder#build} so that durable-context encoders
     * and decoders can classify Camel inbound traffic correctly.
     */
    public static final String CAMEL = "camel";

    private DispatchBoundary() {}
}
