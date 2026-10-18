// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema.fixtures;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload types whose Jackson property order differs from the canonical (sorted) order of their schema documents, one
 * per source of ordering the property order rule distinguishes. They are public so that the registration path can
 * load them by their binary class name.
 */
public final class OrderedPayloads {

    private OrderedPayloads() {}

    /** Declared as {@code requester, amountCents, department}, which is not the sorted order of the document. */
    public record Expense(String requester, long amountCents, String department) {}

    /** An explicit order that leads with two of four properties. */
    @JsonPropertyOrder({"department", "requester"})
    public record Ordered(String requester, long amountCents, String department, String note) {}

    /** One property with an alias spelling that sorts between the two primary names. */
    public record Aliased(
            String requester, @JsonAlias("dept") String department) {}

    /** A creator whose parameter order is neither the sorted order nor the getter order. */
    public static final class Created {

        private final String alpha;
        private final String mid;
        private final String zeta;

        /**
         * Creates the payload.
         *
         * @param zeta the first creator parameter
         * @param alpha the second creator parameter
         * @param mid the third creator parameter
         */
        @JsonCreator
        public Created(
                @JsonProperty("zeta") String zeta,
                @JsonProperty("alpha") String alpha,
                @JsonProperty("mid") String mid) {
            this.zeta = zeta;
            this.alpha = alpha;
            this.mid = mid;
        }

        /**
         * Returns alpha.
         *
         * @return alpha
         */
        public String getAlpha() {
            return alpha;
        }

        /**
         * Returns mid.
         *
         * @return mid
         */
        public String getMid() {
            return mid;
        }

        /**
         * Returns zeta.
         *
         * @return zeta
         */
        public String getZeta() {
            return zeta;
        }
    }

    /** Two named properties and an any-setter, whose extra keys the schema describes through additional properties. */
    @JsonPropertyOrder({"zulu", "alpha"})
    public static final class WithExtras {

        /** The first named property. */
        public String zulu;

        /** The second named property. */
        public String alpha;

        /** The any-setter storage. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** A type the "replaced" profile publishes a different schema for; its own property is not in that schema. */
    public record Replaced(String ghost) {}

    /** Camel case components, which a snake case profile renames on the wire. */
    public record SnakeNamed(String displayName, long amountCents) {}
}
