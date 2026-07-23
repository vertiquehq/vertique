// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.factory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Test-only consumer that receives the host-native {@link FakeDataSource} via constructor
 * injection.
 *
 * <p>Receiving the bean as a typed constructor parameter — not by querying a locator — is the crux
 * of the AC-12 positive proof: a host bean reaches framework code through ordinary Dagger typed
 * injection.
 */
@Singleton
public final class FakeDataSourceConsumer {

    private final FakeDataSource dataSource;

    /**
     * Constructs the consumer with the injected data source.
     *
     * @param dataSource the host-native data source bound by {@link FakeHostAdapterModule}
     */
    @Inject
    FakeDataSourceConsumer(FakeDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Returns the injected data source.
     *
     * @return the data source received via constructor injection
     */
    public FakeDataSource dataSource() {
        return dataSource;
    }
}
