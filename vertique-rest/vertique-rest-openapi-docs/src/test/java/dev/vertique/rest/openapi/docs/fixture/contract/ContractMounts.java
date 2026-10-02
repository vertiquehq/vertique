// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.openapi.docs.fixture.startup.startupit.HandBuiltMounts;

/**
 * The hand-built JAX-RS mounts a served-contract composition may add, each with one new resource
 * instance and the default priority.
 */
public final class ContractMounts {

    /** The root mount path. */
    public static final String ROOT_MOUNT_PATH = "/*";

    /** A mount path under the default documentation prefix {@code /apidocs}. */
    public static final String EXTRA_DOCS_MOUNT_PATH = "/apidocs/extra/*";

    private ContractMounts() {}

    /**
     * Returns one hand-built mount at {@value #ROOT_MOUNT_PATH} holding {@link
     * ThreeSegmentsProbeResource} ({@code GET /{a}/{b}/{c}}).
     *
     * @return the mounts
     */
    public static HandBuiltMounts rootThreeSegments() {
        return HandBuiltMounts.of(HandBuiltMounts.Mount.at(ROOT_MOUNT_PATH, new ThreeSegmentsProbeResource()));
    }

    /**
     * Returns one hand-built mount at {@value #EXTRA_DOCS_MOUNT_PATH} holding {@link
     * ExtraDocsProbeResource} ({@code GET /probe}).
     *
     * @return the mounts
     */
    public static HandBuiltMounts extraDocs() {
        return HandBuiltMounts.of(HandBuiltMounts.Mount.at(EXTRA_DOCS_MOUNT_PATH, new ExtraDocsProbeResource()));
    }
}
