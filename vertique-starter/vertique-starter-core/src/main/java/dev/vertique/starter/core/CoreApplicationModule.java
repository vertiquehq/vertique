// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter.core;

/**
 * Composes the host-neutral lifecycle foundation required by every Vertique application.
 *
 * <p>Membership is exactly {@link dev.vertique.core.VertxModule},
 * {@link dev.vertique.config.parser.ConfigParsingModule},
 * {@link dev.vertique.deploy.DeployerModule}, and
 * {@link dev.vertique.core.lifecycle.CoreLifecycleStepsModule}. This membership and the module's
 * direct dependency ledger are release-line compatibility surfaces.
 *
 * <p>Applications still own deployment entries and generated modules, and select their launcher
 * and test libraries explicitly. This module contributes no host, transport, test, or generated
 * code.
 */
@dagger.Module(
        includes = {
            dev.vertique.core.VertxModule.class,
            dev.vertique.config.parser.ConfigParsingModule.class,
            dev.vertique.deploy.DeployerModule.class,
            dev.vertique.core.lifecycle.CoreLifecycleStepsModule.class
        })
public abstract class CoreApplicationModule {}
