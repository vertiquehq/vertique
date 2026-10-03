// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The declaring interface of the synthetic application mounts unit tests build with {@link
 * Publications}. It carries {@code @ApiDocs(access = PUBLIC)}, so it stands for an application with
 * a public document. No server deploys it, so it declares no {@code @RestApplication}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
public interface UnitDocumentedApi {}
