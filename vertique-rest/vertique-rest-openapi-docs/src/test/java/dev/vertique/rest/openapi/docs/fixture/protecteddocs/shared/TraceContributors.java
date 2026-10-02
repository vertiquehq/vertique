// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;

/**
 * The fixture's operation handler contributors. Production code writes no trace; these do, so the
 * trace of a request names exactly the fixture contributors its chain ran, in order.
 */
public final class TraceContributors {

    /** The request header whose value {@value #REJECT_VALUE} makes the application contributor reject. */
    public static final String REJECT_HEADER = "X-Reject";

    /** The value of {@value #REJECT_HEADER} that makes the application contributor reject. */
    public static final String REJECT_VALUE = "1";

    /** The trace step of the application contributor. */
    public static final String APPLICATION_STEP = "app60";

    private TraceContributors() {}

    /**
     * Returns the trace step name of the probe at a priority.
     *
     * @param priority the probe's priority
     * @return {@code "probe"} followed by the priority
     */
    public static String probeStep(int priority) {
        return "probe" + priority;
    }

    /**
     * A probe: at registration it records the operation descriptor it was handed; on each request it
     * appends its step name to the request's trace and continues.
     */
    public static final class Probe implements OperationHandlerContributor {

        private final int priority;
        private final Observations observations;

        /**
         * Creates a probe.
         *
         * @param priority     the contributor priority, which also names the probe's step
         * @param observations the component's observation hub
         */
        public Probe(int priority, Observations observations) {
            this.priority = priority;
            this.observations = observations;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String orderKey() {
            return Probe.class.getName() + "#" + priority;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            observations.recordOperation(context.operationId(), context.operation());
            String step = probeStep(priority);
            context.route().addHandler(ctx -> {
                observations.appendTrace(ctx, step);
                ctx.next();
            });
        }
    }

    /**
     * An application-supplied contributor at priority {@value #PRIORITY}: it appends {@value
     * #APPLICATION_STEP} to the trace and fails the request with {@code 403} when the request carries
     * {@value #REJECT_HEADER}: {@value #REJECT_VALUE}; otherwise it continues.
     */
    public static final class ApplicationRejecter implements OperationHandlerContributor {

        /** The contributor's priority. */
        public static final int PRIORITY = 60;

        private final Observations observations;

        /**
         * Creates the contributor.
         *
         * @param observations the component's observation hub
         */
        public ApplicationRejecter(Observations observations) {
            this.observations = observations;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(ctx -> {
                observations.appendTrace(ctx, APPLICATION_STEP);
                if (REJECT_VALUE.equals(ctx.request().getHeader(REJECT_HEADER))) {
                    ctx.fail(403);
                    return;
                }
                ctx.next();
            });
        }
    }
}
