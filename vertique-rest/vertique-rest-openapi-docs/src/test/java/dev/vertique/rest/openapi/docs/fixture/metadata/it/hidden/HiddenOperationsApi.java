// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code hidden} at {@code /api/hidden}, listing one resource with mixed
 * hidden and visible operations ({@link MixedOperationsResource}), one hidden resource class ({@link
 * HiddenClassResource}), one resource implementing a hidden interface ({@link
 * HiddenContractResource}), and one resource implementing an interface with one hidden method
 * ({@link PartlyHiddenContractResource}). Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = HiddenOperationsApi.NAME,
        path = HiddenOperationsApi.PATH,
        resources = {
            MixedOperationsResource.class,
            HiddenClassResource.class,
            HiddenContractResource.class,
            PartlyHiddenContractResource.class
        })
public interface HiddenOperationsApi {

    /** The application's name, which also names its document. */
    String NAME = "hidden";

    /** The application's path. */
    String PATH = "/api/hidden";

    /** The tag every hidden operation declares and no visible one does. */
    String HIDDEN_TAG = "hiddenOnlyZx";
}
