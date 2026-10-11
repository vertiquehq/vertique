// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.gip.relay;

import dev.vertique.codegen.jaxrs.gip.chain.RelayBase;

/**
 * A class of another package between {@link RelayBase} and a subclass of its package: it does not
 * inherit the package-private route.
 *
 * @param <T> the identifier type
 */
public class Relay<T> extends RelayBase<T> {}
