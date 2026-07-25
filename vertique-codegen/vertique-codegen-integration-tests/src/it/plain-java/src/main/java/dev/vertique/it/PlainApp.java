// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

/** Plain Java consumer that deliberately uses no Vertique annotation or runtime type. */
public final class PlainApp {

    private PlainApp() {}

    public static String greeting() {
        return "hello";
    }
}
