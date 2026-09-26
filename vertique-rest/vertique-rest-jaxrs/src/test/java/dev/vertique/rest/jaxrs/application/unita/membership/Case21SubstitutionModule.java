// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;

/**
 * TP-005 case 21's Dagger binding substitution: the sole source of {@code Provider<Case21Resource>}
 * (since {@link Case21Resource} carries no {@code @Inject} constructor), returning a
 * {@link Case21SubclassResource} instance instead of a plain {@link Case21Resource}.
 */
@Module
public final class Case21SubstitutionModule {

    /**
     * Provides the {@link Case21Resource} binding, substituted with a
     * {@link Case21SubclassResource} instance.
     *
     * @return a {@link Case21SubclassResource} instance, typed as {@link Case21Resource}
     */
    @Provides
    static Case21Resource case21Resource() {
        return new Case21SubclassResource();
    }
}
