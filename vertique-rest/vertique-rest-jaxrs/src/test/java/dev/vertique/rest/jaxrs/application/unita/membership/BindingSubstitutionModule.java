// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita.membership;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger binding substitution: the sole source of {@code Provider<SubstitutedBindingResource>}
 * (since {@link SubstitutedBindingResource} carries no {@code @Inject} constructor), returning a
 * {@link SubstitutedBindingSubclassResource} instance instead of a plain {@link SubstitutedBindingResource}.
 */
@Module
public final class BindingSubstitutionModule {

    /**
     * Provides the {@link SubstitutedBindingResource} binding, substituted with a
     * {@link SubstitutedBindingSubclassResource} instance.
     *
     * @return a {@link SubstitutedBindingSubclassResource} instance, typed as {@link SubstitutedBindingResource}
     */
    @Provides
    static SubstitutedBindingResource substitutedBindingResource() {
        return new SubstitutedBindingSubclassResource();
    }
}
