// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpStatusRejectedExceptionTest {

    /** AC-010: each call returns its own copy of the headers, without {@code Set-Cookie}. */
    @Test
    @DisplayName("AC-010: headers() returns a fresh copy on each call")
    void headersReturnsAFreshCopyOnEachCall() {
        MultiMap response = MultiMap.caseInsensitiveMultiMap()
                .add("Content-Type", "text/plain")
                .add("Set-Cookie", "session=secret");
        HttpStatusRejectedException failure = new HttpStatusRejectedException(503, response, null, null);

        MultiMap first = failure.headers();
        first.add("X-Changed", "by-caller");
        first.remove("Content-Type");
        MultiMap second = failure.headers();

        assertThat(second).isNotSameAs(first);
        assertThat(second.get("Content-Type")).isEqualTo("text/plain");
        assertThat(second.contains("X-Changed")).isFalse();
        assertThat(second.contains("Set-Cookie")).isFalse();
    }
}
