// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output view whose property names, {@code alphaTitle} and {@code alphaCount}, share none
 * with {@link ViewB}'s, so a published schema shows which of the two it describes.
 */
public class ViewA {

    /** A string member. */
    public String alphaTitle;

    /** A number member. */
    public int alphaCount;

    /** Creates an empty view. */
    public ViewA() {}

    /**
     * Creates a view with both members set.
     *
     * @param alphaTitle the title
     * @param alphaCount the count
     */
    public ViewA(String alphaTitle, int alphaCount) {
        this.alphaTitle = alphaTitle;
        this.alphaCount = alphaCount;
    }
}
