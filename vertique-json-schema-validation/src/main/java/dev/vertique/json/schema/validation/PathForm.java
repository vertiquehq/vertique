// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

/**
 * The spelling a violation detail uses for the location of the failing instance.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract.
 */
public enum PathForm {

    /**
     * The engine's own instance location, returned verbatim: a URI fragment such as {@code #/a%20b/0}, with the
     * root spelled {@code #}. A missing location is returned as the empty string.
     */
    URI_FRAGMENT,

    /**
     * An RFC 6901 JSON pointer such as {@code /a b/0}: the leading {@code #} is removed, each segment is
     * percent-decoded, and every spelling of the root becomes the empty string.
     */
    RFC6901
}
