// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * INTERNAL framework seam — the framework's process JSON codec as core sees it; {@code vertique-json} implements it.
 *
 * <p>Applications and framework modules call {@link VertiqueJson}, which owns the installed state, the
 * install lock, the reset gate and the attribution of every install. Application code is trusted, so this
 * interface carries no capability check; calling it directly bypasses those rules and is unsupported.
 */
public interface ProcessJsonCodec {

    /**
     * Returns the profile the codec runs before any install: id {@code vertx}, over Vert.x's raw mapper.
     *
     * @return the pre-install profile
     */
    JsonProfileCodec raw();

    /**
     * Returns the profile the codec currently runs: the last swapped-in profile, or {@link #raw()}.
     *
     * @return the current profile
     */
    JsonProfileCodec current();

    /**
     * Validates {@code profile} as the process codec's next profile. Reads the profile's mapper once and
     * runs the guards on that instance.
     *
     * @param profile the candidate profile
     * @param installed the currently installed profile, or {@code null} before the first install
     * @return the prepared install, carrying the guarded mapper
     * @throws IllegalArgumentException naming the profile id when the profile is not accepted
     */
    Prepared validate(JsonProfileCodec profile, @Nullable JsonProfileCodec installed);

    /**
     * Publishes the prepared profile and its guarded mapper together.
     *
     * @param prepared a result of {@link #validate} on this codec
     */
    void swap(Prepared prepared);

    /** Returns the codec to the raw profile. */
    void reset();

    /** The result of {@link #validate}: the profile, the guarded mapper it carries, and the widened settings. */
    interface Prepared {

        /**
         * Returns the validated profile.
         *
         * @return the profile
         */
        JsonProfileCodec profile();

        /**
         * Returns the read-leniency settings the profile widens compared to the installed profile, as
         * human-readable descriptions; empty when nothing widens or nothing was installed.
         *
         * @return the widened settings
         */
        List<String> widenedSettings();
    }
}
