// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;

/**
 * A pass-through contributor that only records its label into the shared {@link TraceRecorder}
 * and continues. Used as the {@code probe45} and {@code probe400} fixtures bracketing {@code
 * app60} and {@code authz100} in the IT's contributor order proof.
 */
final class TracingProbeContributor implements OperationHandlerContributor {

    private final int priority;
    private final String label;
    private final TraceRecorder trace;

    /**
     * Creates a probe contributor.
     *
     * @param priority the {@link OrderedExtension} priority band
     * @param label    the label recorded into the trace
     * @param trace    the shared trace recorder
     */
    TracingProbeContributor(int priority, String label, TraceRecorder trace) {
        this.priority = priority;
        this.label = label;
        this.trace = trace;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public void contribute(OperationRegistrationContext context) {
        context.route().addHandler(ctx -> {
            trace.record(ctx, label);
            ctx.next();
        });
    }

    @Override
    public String orderKey() {
        return label;
    }
}
