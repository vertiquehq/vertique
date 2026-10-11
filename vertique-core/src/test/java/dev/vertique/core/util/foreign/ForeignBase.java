// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util.foreign;

/**
 * A public generic base class in a package other than the one under test, with a package-private
 * method: a subclass in another package cannot override it (JLS 8.4.8.1).
 *
 * @param <T> the parameter type
 */
public class ForeignBase<T> {

    void hidden(T value) {}

    /**
     * A public method, overridable from anywhere.
     *
     * @param value the value
     */
    public void visible(T value) {}
}
