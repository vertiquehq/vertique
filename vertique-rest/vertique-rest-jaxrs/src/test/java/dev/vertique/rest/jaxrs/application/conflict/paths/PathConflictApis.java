// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import dev.vertique.rest.core.application.RestApplication;

/**
 * TP-016's ported path-conflict fixtures for {@link JaxRsApplicationMountConflictTest}, each a
 * native {@code @RestApplication} declaring interface in place of the former {@code Application}
 * subclass.
 */
public final class PathConflictApis {

    private PathConflictApis() {}

    /** Case 1: the same normalized path as {@link BetaApi}, {@code /api/dup}. */
    @RestApplication(name = "paths-alpha", path = "/api/dup", resources = AlphaResource.class)
    public interface AlphaApi {}

    /** Case 1: the same normalized path as {@link AlphaApi}, {@code /api/dup}. */
    @RestApplication(name = "paths-beta", path = "/api/dup", resources = BetaResource.class)
    public interface BetaApi {}

    /** Case 2: {@code /api}, conflicting with {@link DeltaApi}'s {@code /api/mgmt}. */
    @RestApplication(name = "paths-gamma", path = "/api", resources = GammaResource.class)
    public interface GammaApi {}

    /** Cases 2 and 3: {@code /api/mgmt}, conflicting with {@link GammaApi}'s {@code /api} and {@link RootApi}'s {@code /}. */
    @RestApplication(name = "paths-delta", path = "/api/mgmt", resources = DeltaResource.class)
    public interface DeltaApi {}

    /** Case 3: the root path {@code /}, conflicting with every other application. */
    @RestApplication(name = "paths-root", path = "/", resources = RootResource.class)
    public interface RootApi {}

    /** Case 4, control: {@code /api/public}, non-conflicting with {@link PublicityProbeApi}. */
    @RestApplication(name = "paths-public-probe", path = "/api/public", resources = PublicProbeResource.class)
    public interface PublicProbeApi {}

    /** Case 4, control: {@code /api/publicity}, non-conflicting with {@link PublicProbeApi}. */
    @RestApplication(name = "paths-publicity-probe", path = "/api/publicity", resources = PublicityProbeResource.class)
    public interface PublicityProbeApi {}
}
