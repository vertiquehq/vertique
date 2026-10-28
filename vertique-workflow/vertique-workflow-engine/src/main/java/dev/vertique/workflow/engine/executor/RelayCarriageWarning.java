// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: the warning an executor logs at startup when identity carriage is required for the outbox relay or for the
 * workflow. It depends only on the typed {@link OutboxRelayCarriage}; configuration is never read here.
 */
public final class RelayCarriageWarning {

    private static final Logger log = LoggerFactory.getLogger(RelayCarriageWarning.class);

    private RelayCarriageWarning() {}

    /**
     * Logs one warning for each required carriage and nothing otherwise.
     *
     * @param carriage the carriage requirements of the application
     */
    public static void check(OutboxRelayCarriage carriage) {
        if (carriage.required()) {
            log.warn(
                    "Identity carriage is required for the outbox-relay, but the intents a workflow segment writes carry"
                            + " attribution headers and no identity snapshot, so the relay treats them as carrying no identity"
                            + " and the service operation they dispatch runs as the relay's identity, not as the user who"
                            + " recorded the command");
        }
        if (carriage.workflowRequired()) {
            log.warn("Identity carriage is required for the workflow, but executor drives bind no identity snapshot,"
                    + " so the requirement cannot be met on them");
        }
    }
}
