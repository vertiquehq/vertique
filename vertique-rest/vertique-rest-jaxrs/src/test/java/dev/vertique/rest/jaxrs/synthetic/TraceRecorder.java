// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-request trace recorder shared by every stub contributor, the twin resource, and every
 * synthetic operation's terminal handler in the {@code SyntheticOperationIT} fixture.
 *
 * <p>Each request carries its own trace id in the {@value #REQUEST_ID_HEADER} header, chosen by
 * the caller; every component on the request's handler chain appends its own label, in the order
 * it actually ran, so the recorded list is the ground truth for handler-order proofs and
 * denial-never-falls-through proofs.
 */
public final class TraceRecorder {

    /** The header every IT request carries its trace id in. */
    static final String REQUEST_ID_HEADER = "X-Trace-Id";

    private final Map<String, List<String>> traces = new ConcurrentHashMap<>();

    /**
     * Appends {@code label} to the trace of the request {@code ctx} belongs to. A request with no
     * {@value #REQUEST_ID_HEADER} header is not recorded.
     *
     * @param ctx   the routing context of the running request
     * @param label the label to append
     */
    void record(RoutingContext ctx, String label) {
        String requestId = ctx.request().getHeader(REQUEST_ID_HEADER);
        if (requestId == null) {
            return;
        }
        traces.computeIfAbsent(requestId, key -> new CopyOnWriteArrayList<>()).add(label);
    }

    /**
     * Returns the recorded trace for {@code requestId}, or an empty list when nothing was recorded.
     *
     * @param requestId the trace id a request carried in {@value #REQUEST_ID_HEADER}
     * @return the ordered list of labels appended for that request
     */
    public List<String> trace(String requestId) {
        return traces.getOrDefault(requestId, List.of());
    }
}
