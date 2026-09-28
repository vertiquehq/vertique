// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;

/**
 * The IT's application-layer contributor: records {@code app60} into the shared {@link
 * TraceRecorder} and fails the request with 403 when the {@value #REJECT_HEADER} header carries
 * {@value #REJECT_VALUE}. Runs at priority {@code 60}, between the {@code probe45} and {@code
 * authz100} fixtures.
 */
final class App60Contributor implements OperationHandlerContributor {

    static final String REJECT_HEADER = "X-Reject";
    static final String REJECT_VALUE = "1";

    private final TraceRecorder trace;

    /**
     * Creates the contributor.
     *
     * @param trace the shared trace recorder
     */
    App60Contributor(TraceRecorder trace) {
        this.trace = trace;
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public void contribute(OperationRegistrationContext context) {
        context.route().addHandler(ctx -> {
            trace.record(ctx, "app60");
            if (REJECT_VALUE.equals(ctx.request().getHeader(REJECT_HEADER))) {
                ctx.fail(403);
                return;
            }
            ctx.next();
        });
    }

    @Override
    public String orderKey() {
        return "app60";
    }
}
