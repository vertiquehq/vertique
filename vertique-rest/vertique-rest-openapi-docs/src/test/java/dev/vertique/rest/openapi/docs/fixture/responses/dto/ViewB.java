// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output view whose property names, {@code bravoLabel} and {@code bravoSize}, share none
 * with {@link ViewA}'s, so a published schema shows which of the two it describes.
 */
public class ViewB {

    /** A string member. */
    public String bravoLabel;

    /** A number member. */
    public int bravoSize;

    /** Creates an empty view. */
    public ViewB() {}
}
