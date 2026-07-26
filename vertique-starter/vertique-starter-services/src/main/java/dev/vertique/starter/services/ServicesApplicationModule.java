// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter.services;

/**
 * Composes the headless event-bus services foundation for Vertique service applications.
 *
 * <p>Membership is exactly {@link dev.vertique.starter.core.CoreApplicationModule},
 * {@link dev.vertique.services.DispatchModule}, and
 * {@link dev.vertique.management.ManagementModule}. This membership and the module's direct
 * dependency ledger are release-line compatibility surfaces.
 *
 * <p>Applications still own management deployment entries, generated services modules, launcher
 * choice, test libraries, and worker opt-in for genuinely blocking implementations. The default
 * execution model remains the Vert.x event loop.
 */
@dagger.Module(
        includes = {
            dev.vertique.starter.core.CoreApplicationModule.class,
            dev.vertique.services.DispatchModule.class,
            dev.vertique.management.ManagementModule.class
        })
public abstract class ServicesApplicationModule {}
