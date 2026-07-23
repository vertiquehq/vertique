// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.bootstrap;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when a {@link VertxBuilderContributor} fails during the contribution phase.
 *
 * <p>Failure modes:
 * <ul>
 *   <li>The contributor threw an exception from
 *       {@link VertxBuilderContributor#contribute(io.vertx.core.VertxBuilder, BootstrapContext)}.
 *       The original exception is preserved as the {@link #getCause() cause}.</li>
 *   <li>The contributor returned {@code null} from {@code contribute}. No cause is set.</li>
 * </ul>
 *
 * <p>The message always contains the fully-qualified class name of the contributor that failed so
 * that the startup abort can be attributed immediately.
 *
 * <p>Extends {@link ConfigurationException} because a contributor failure is a startup/wiring
 * failure — the application cannot be configured into a running state.
 */
public class ContributorFailureException extends ConfigurationException {

    /**
     * Creates a failure exception for a contributor that threw an exception.
     *
     * @param message a descriptive message including the contributor's FQCN
     * @param cause   the original exception thrown by the contributor
     */
    public ContributorFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a failure exception for a contributor that returned a null builder.
     *
     * @param message a descriptive message including the contributor's FQCN
     */
    public ContributorFailureException(String message) {
        super(message);
    }
}
