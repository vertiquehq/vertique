// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.db.flyway.FlywayMode;
import jakarta.inject.Inject;

/**
 * Refuses to start a load node whose configuration would spoil a measurement.
 *
 * <p>Two things must hold for every node of a run, and neither can be left to a default:
 * <ul>
 *   <li>Migrations are off. Schema changes run once, before any node starts, through the admin entry point; a node
 *       that migrates would take locks in the middle of a run.</li>
 *   <li>The node names its database connections. The run attributes pooled work to a node by the
 *       {@code application_name} of its connections, so a node without one cannot be told from another.</li>
 * </ul>
 *
 * <p>The check runs when the application graph is validated, before any verticle is deployed.
 */
public final class LoadNodeValidator implements ComposeValidator {

    /**
     * Validates the configuration.
     *
     * @param db the typed {@code db} configuration
     * @param flyway the typed {@code flyway} configuration
     * @throws ConfigurationException if migrations are enabled or no {@code db.properties.application_name} is set
     */
    @Inject
    public LoadNodeValidator(DbPoolConfig db, FlywayConfig flyway) {
        if (flyway.mode() != FlywayMode.DISABLED) {
            throw new ConfigurationException("flyway.mode must be DISABLED on a load node; run the migrations once "
                    + "with the admin entry point before any node starts");
        }
        String name = EffectiveConfig.applicationName(db);
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("db.properties.application_name must be set on a load node so that its "
                    + "database connections can be attributed to it");
        }
    }
}
