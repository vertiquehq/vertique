// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small HTTP/1.1 client for one node ingress, with keep-alive connections.
 *
 * <p>It opens as many connections as there are requests in flight and never queues a request behind another, so the
 * driver itself adds no waiting that could hide a slow node. A request that finds its kept connection closed by the
 * node is sent again once on a new connection; nothing else is retried.
 */
final class IngressClient implements AutoCloseable {

    /**
     * An answer.
     *
     * @param status the HTTP status
     * @param body the body text
     */
    record Reply(int status, String body) {}

    private static final class StaleConnection extends IOException {
        StaleConnection(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Connection {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream(), 4096);
            this.out = socket.getOutputStream();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a broken connection
            }
        }
    }

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();
    private final AtomicLong freshRetries = new AtomicLong();

    IngressClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Returns how many requests were sent again on a new connection because the kept one was closed before it answered.
     *
     * @return the count
     */
    long freshRetries() {
        return freshRetries.get();
    }

    /**
     * Sends a {@code POST} without a body.
     *
     * @param target the request target, such as {@code /start?key=a}
     * @param onWire called once, right before the first byte of the request is written, after any connect
     * @return the answer
     * @throws IOException if the request fails or times out
     */
    Reply post(String target, Runnable onWire) throws IOException {
        Connection connection = idle.pollFirst();
        boolean reused = connection != null;
        if (connection == null) {
            connection = open();
        }
        try {
            return exchange(connection, target, onWire);
        } catch (StaleConnection stale) {
            connection.close();
            if (!reused) {
                throw stale;
            }
            // A kept connection that was closed before any byte of an answer: the request is sent once more on a new
            // connection. A start is idempotent by its key, so a repeat records nothing new.
            freshRetries.incrementAndGet();
            Connection fresh = open();
            try {
                return exchange(fresh, target, () -> {});
            } catch (IOException e) {
                fresh.close();
                throw e;
            }
        } catch (IOException e) {
            connection.close();
            throw e;
        }
    }

    private Connection open() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return new Connection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private Reply exchange(Connection connection, String target, Runnable onWire) throws IOException {
        byte[] request = ("POST " + target + " HTTP/1.1\r\nHost: " + host + ":" + port
                        + "\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        onWire.run();
        try {
            connection.out.write(request);
            connection.out.flush();
        } catch (IOException e) {
            throw new StaleConnection("write failed", e);
        }
        int first;
        try {
            first = connection.in.read();
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            throw new StaleConnection("reset before an answer", e);
        }
        if (first < 0) {
            throw new StaleConnection("closed before an answer", new EOFException());
        }
        String statusLine = readLine(connection.in, first);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("bad status line");
        }
        int status = Integer.parseInt(parts[1]);
        int length = -1;
        boolean close = false;
        String line;
        while (!(line = readLine(connection.in, connection.in.read())).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    length = Integer.parseInt(value);
                } else if (name.equals("connection") && value.equalsIgnoreCase("close")) {
                    close = true;
                }
            }
        }
        byte[] body;
        if (length >= 0) {
            body = connection.in.readNBytes(length);
            if (body.length != length) {
                throw new EOFException("short body");
            }
        } else {
            body = connection.in.readAllBytes();
            close = true;
        }
        if (close) {
            connection.close();
        } else {
            idle.addFirst(connection);
        }
        return new Reply(status, new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in, int firstByte) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int b = firstByte;
        while (true) {
            if (b < 0) {
                throw new EOFException("end of stream");
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 16_384) {
                throw new IOException("header line too long");
            }
            b = in.read();
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() {
        Connection connection;
        while ((connection = idle.pollFirst()) != null) {
            connection.close();
        }
    }
}
