// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dagger.BindsOptionalOf;
import dagger.Module;

/**
 * Dagger module that declares {@link DbSchemas} as an optional binding. Adapters and migration
 * modules include it so they can consume {@code Optional<DbSchemas>} and fall back to
 * {@link DbSchemas#empty()} in components that include no module providing the placement, such as
 * {@link DbModule}.
 */
@Module
public abstract class DbSchemasModule {

    /**
     * Declares {@link DbSchemas} as an optional binding.
     *
     * @return the optional declaration; never called
     */
    @BindsOptionalOf
    abstract DbSchemas optionalDbSchemas();
}
