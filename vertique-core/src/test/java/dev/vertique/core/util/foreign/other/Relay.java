// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util.foreign.other;

import dev.vertique.core.util.foreign.RelayBase;

/**
 * A class of another package between {@link RelayBase} and a subclass that is back in the base
 * class's package: it does not inherit the package-private method.
 *
 * @param <T> the parameter type
 */
public class Relay<T> extends RelayBase<T> {}
