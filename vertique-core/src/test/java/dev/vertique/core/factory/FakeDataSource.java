// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.factory;

/**
 * A plain Java host-native bean type used by the AC-12 positive test fixtures.
 *
 * <p>It models a "normal Java type" a host (Spring/Quarkus) might own — not a service locator. The
 * test wires an implementation through a typed Dagger adapter module to prove that host beans reach
 * framework consumers via ordinary typed bindings, with no host-bean lookup.
 */
public interface FakeDataSource {

    /**
     * Returns a stable label identifying this data source, used by the test to assert the typed
     * bean was delivered through Dagger rather than fabricated.
     *
     * @return the data source label
     */
    String label();
}
