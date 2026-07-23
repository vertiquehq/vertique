// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.resource;

import dev.vertique.examples.sse.job.JobService;
import dev.vertique.examples.sse.job.JobStatus;
import dev.vertique.rest.core.sse.SseChannel;
import dev.vertique.rest.core.sse.SseChannelFactory;
import dev.vertique.rest.core.sse.SseEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource for the job progress feed.
 *
 * <p>Exposes three endpoints:
 * <ul>
 *   <li>{@code POST /jobs} — creates a new simulated job and returns its id</li>
 *   <li>{@code GET /jobs/{jobId}} — returns the current status snapshot of a job</li>
 *   <li>{@code GET /jobs/{jobId}/events} — SSE stream of progress events until the job
 *       reaches a terminal state ({@link JobStatus#DONE} or {@link JobStatus#FAILED})</li>
 * </ul>
 */
@Tag(name = "Jobs", description = "Job progress SSE feed")
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobResource {

    private final JobService jobs;
    private final SseChannelFactory sseChannels;

    /**
     * Creates a {@link JobResource} with its required dependencies.
     *
     * @param jobs        the in-memory job service that drives progress simulation
     * @param sseChannels factory for creating per-request SSE channels
     */
    @Inject
    public JobResource(JobService jobs, SseChannelFactory sseChannels) {
        this.jobs = jobs;
        this.sseChannels = sseChannels;
    }

    /**
     * Creates a new simulated job and returns its unique identifier.
     *
     * @return a JSON object containing the job {@code id} field
     */
    @POST
    @Operation(
            operationId = "createJob",
            summary = "Create a job",
            description = "Creates a new simulated job and returns its identifier.")
    @ApiResponse(responseCode = "200", description = "Job created successfully")
    public JsonObject createJob() {
        return new JsonObject().put("id", jobs.createJob());
    }

    /**
     * Returns the current status of a job.
     *
     * @param jobId the unique job identifier from the path
     * @return a JSON object with {@code id}, {@code status}, and {@code percent} fields
     * @throws WebApplicationException with HTTP 404 if the job does not exist
     */
    @GET
    @Path("/{jobId}")
    @Operation(
            operationId = "getJob",
            summary = "Get job status",
            description = "Returns the current status snapshot of a job.")
    @ApiResponse(responseCode = "200", description = "Job status returned")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public JsonObject getJob(@Parameter(description = "Job identifier") @PathParam("jobId") String jobId) {
        return jobs.current(jobId)
                .map(e -> new JsonObject()
                        .put("id", e.jobId())
                        .put("status", e.status().name())
                        .put("percent", e.percent()))
                .orElseThrow(() -> new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new JsonObject()
                                .put("status", 404)
                                .put("title", "Not Found")
                                .put("detail", "Job not found: " + jobId))
                        .build()));
    }

    /**
     * Opens an SSE stream of progress events for the given job.
     *
     * <p>Events are emitted until the job reaches a terminal state ({@link JobStatus#DONE} or
     * {@link JobStatus#FAILED}), at which point a final {@code complete} event is sent and the
     * stream is closed. The {@code Last-Event-ID} request header, if present, is used to skip
     * already-seen events from the replay buffer.
     *
     * @param jobId       the unique job identifier from the path
     * @param lastEventId the value of the {@code Last-Event-ID} request header; may be {@code null}
     * @return an SSE read stream that the framework pipes to the HTTP response
     * @throws WebApplicationException with HTTP 404 if the job does not exist
     */
    @GET
    @Path("/{jobId}/events")
    @Produces("text/event-stream")
    @Operation(
            operationId = "streamJobEvents",
            summary = "Stream job progress events",
            description = "Opens an SSE stream that emits progress events until the job reaches a terminal state. "
                    + "The response body is an SSE event stream (text/event-stream) with named progress "
                    + "events and a final complete event. The response body schema is not declared in this "
                    + "OpenAPI document because the Vert.x OpenAPI contract validator currently rejects "
                    + "text/event-stream as an unsupported response media type — see "
                    + "https://github.com/vertiquehq/vertique/issues/6 for the framework-level follow-up.")
    @ApiResponse(responseCode = "200", description = "SSE stream opened")
    @ApiResponse(responseCode = "404", description = "Job not found")
    public ReadStream<SseEvent> streamJobEvents(
            @Parameter(description = "Job identifier") @PathParam("jobId") String jobId,
            @Nullable @HeaderParam("Last-Event-ID") String lastEventId) {

        if (jobs.current(jobId).isEmpty()) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                    .entity(new JsonObject()
                            .put("status", 404)
                            .put("title", "Not Found")
                            .put("detail", "Job not found: " + jobId))
                    .build());
        }

        SseChannel channel = sseChannels.create();

        JobService.Subscription subscription = jobs.subscribe(jobId, lastEventId, evt -> {
            SseEvent progressEvent = SseEvent.builder()
                    .event("progress")
                    .id(Integer.toString(evt.seq()))
                    .data(evt)
                    .retryMs(3000L)
                    .build();
            Future<Void> sent = channel.send(progressEvent);
            if (evt.status() == JobStatus.DONE || evt.status() == JobStatus.FAILED) {
                SseEvent terminalEvent =
                        SseEvent.builder().event("complete").data("done").build();
                sent.compose(v -> channel.send(terminalEvent)).onComplete(ar -> {
                    if (ar.failed()) {
                        channel.fail(ar.cause());
                    } else {
                        channel.complete();
                    }
                });
            } else {
                sent.onFailure(channel::fail);
            }
        });

        channel.onClose(subscription::unsubscribe);
        return channel.stream();
    }
}
