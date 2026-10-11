// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util.foreign;

import dev.vertique.core.util.foreign.other.Relay;

/**
 * Lives in the package of {@link RelayBase} but extends it through {@link Relay} of another
 * package, so its {@code m(String)} does not override {@link RelayBase#m(Object)}: javac emits no
 * bridge and the base method is not a member of this class.
 */
public class RelayLeaf extends Relay<String> {

    void m(String value) {}
}
