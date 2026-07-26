// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter.rest;

/**
 * Composes the mechanism-neutral REST application foundation for Vertique REST APIs.
 *
 * <p>Membership is exactly {@link dev.vertique.starter.core.CoreApplicationModule},
 * {@link dev.vertique.rest.jaxrs.RestModule},
 * {@link dev.vertique.rest.validation.RestValidationModule},
 * {@link dev.vertique.rest.security.AuthModule},
 * {@link dev.vertique.rest.security.SecurityModule}, and
 * {@link dev.vertique.management.ManagementModule}. This membership and the module's direct
 * dependency ledger are release-line compatibility surfaces.
 *
 * <p>Applications still own HTTP and management deployment entries, generated JAX-RS modules,
 * launcher choice, test libraries, and any concrete authentication mechanism such as JWT.
 */
@dagger.Module(
        includes = {
            dev.vertique.starter.core.CoreApplicationModule.class,
            dev.vertique.rest.jaxrs.RestModule.class,
            dev.vertique.rest.validation.RestValidationModule.class,
            dev.vertique.rest.security.AuthModule.class,
            dev.vertique.rest.security.SecurityModule.class,
            dev.vertique.management.ManagementModule.class
        })
public abstract class RestApplicationModule {}
