// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import java.util.List;

/**
 * INTERNAL framework seam: validates a complete mount composition before any mount router is
 * created. Not an application contract and outside the maturity promise. An application uses the
 * extension points and configuration this module documents and never names this type. Not ordered.
 */
public interface MountCompositionValidator {
    /**
     * Validates the mounts. Implementations run on the verticle's event loop and must not block.
     * They must not call {@code createRouter} and must not change a mount's path, priority,
     * resources, or routes. Validation state that a mount's own module keeps package-private is
     * allowed. Exceptions thrown by this callback propagate and are fatal to the enclosing
     * operation; processing does not continue.
     *
     * @param mounts every mount, in mounting order; unmodifiable; every path already valid
     * @return violation messages, empty when the composition is valid; never null
     */
    List<String> validate(List<RouterMount> mounts);
}
