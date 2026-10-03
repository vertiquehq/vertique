// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Fixture methods declaring method-level {@code @Tag}s, read onto synthetic operations without
 * parameters by {@code MetadataPublications.annotate}; nothing invokes them. The class itself carries
 * no annotation. The descriptions {@code DESCAZX} and {@code DESCBZX} are sentinels no failure message
 * may echo.
 */
@SuppressWarnings("unused")
public final class TaggedResource {

    private TaggedResource() {}

    /** (a) The first declaration of tag {@code shared}. */
    @Tag(name = "shared", description = "DESCAZX")
    public void sharedFirst() {}

    /** (a) A second declaration of tag {@code shared} with another description. */
    @Tag(name = "shared", description = "DESCBZX")
    public void sharedSecond() {}

    /** (b) Tag {@code plain} with a default description. */
    @Tag(name = "plain")
    public void plainUndescribed() {}

    /** (b) Tag {@code plain} with a description. */
    @Tag(name = "plain", description = "Plain tag")
    public void plainDescribed() {}
}
