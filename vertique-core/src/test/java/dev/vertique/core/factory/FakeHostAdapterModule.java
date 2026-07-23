// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.factory;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;

/**
 * Test-only Dagger module that adapts a host-native bean ({@link FakeDataSource}) into the
 * framework graph as an ordinary typed binding.
 *
 * <p>This stands in for the typed adapter module an embedding host bridge supplies (FR-APP-004):
 * the host bean is wired by type, not fetched from a locator. Its presence is what makes the
 * AC-12 positive component buildable; its absence is what makes the AC-12 negative case fail to
 * compile.
 */
@Module
public final class FakeHostAdapterModule {

    /**
     * Provides the host-native {@link FakeDataSource} bean.
     *
     * @return a stub data source the consumer is expected to receive by type
     */
    @Provides
    @Singleton
    FakeDataSource fakeDataSource() {
        return () -> "host-provided";
    }
}
