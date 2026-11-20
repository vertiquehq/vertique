// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.http.HttpVersion;
import java.util.List;

/**
 * A response head a stream's expectation refused, together with the first bytes of the body that were
 * read before the exchange was reset. {@link HttpExpectations#status} reads the excerpt from it.
 *
 * @param head the refused response head
 * @param excerpt the body bytes read so far
 */
record ExcerptHead(HttpResponseHead head, Buffer excerpt) implements HttpResponseHead {

    @Override
    public HttpVersion version() {
        return head.version();
    }

    @Override
    public int statusCode() {
        return head.statusCode();
    }

    @Override
    public String statusMessage() {
        return head.statusMessage();
    }

    @Override
    public MultiMap headers() {
        return head.headers();
    }

    @Override
    public String getHeader(String headerName) {
        return head.getHeader(headerName);
    }

    @Override
    public String getHeader(CharSequence headerName) {
        return head.getHeader(headerName);
    }

    @Override
    public List<String> cookies() {
        return head.cookies();
    }
}
