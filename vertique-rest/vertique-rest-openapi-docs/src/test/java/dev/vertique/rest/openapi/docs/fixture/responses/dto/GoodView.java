// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output view with one string member {@code name}. Every built-in profile, {@code
 * vertique-strict} included, generates its output schema; it is the control beside {@link BadView}.
 */
public class GoodView {

    /** The name. */
    public String name;

    /** Creates an empty view. */
    public GoodView() {}
}
