// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.db.RowMapper;
import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;

/**
 * A {@link ReadStream} that maps database rows to domain objects. Wraps a {@link RowStream} and
 * applies a {@link RowMapper} to each row delivered to the handler.
 *
 * <p>Manages the lifecycle of the underlying {@link PreparedStatement}: closes it when the stream
 * ends (normally or due to error) to prevent resource leaks.
 *
 * @param <T> the mapped domain type
 */
class MappedRowStream<T> implements ReadStream<T> {

    private final RowStream<Row> delegate;
    private final RowMapper<T> mapper;
    private final PreparedStatement preparedStatement;
    private final DbExceptionMapper exceptionMapper;
    private final String sql;
    private boolean preparedStatementClosed;

    /**
     * Constructs a new {@code MappedRowStream}.
     *
     * @param delegate          the underlying row stream from the driver
     * @param mapper            the mapper applied to each row
     * @param preparedStatement the prepared statement to close when the stream ends or errors
     * @param exceptionMapper     the exception mapper for exception translation
     * @param sql               the SQL string, used for error context messages
     */
    MappedRowStream(
            RowStream<Row> delegate,
            RowMapper<T> mapper,
            PreparedStatement preparedStatement,
            DbExceptionMapper exceptionMapper,
            String sql) {
        this.delegate = delegate;
        this.mapper = mapper;
        this.preparedStatement = preparedStatement;
        this.exceptionMapper = exceptionMapper;
        this.sql = sql;
    }

    /**
     * Sets the handler that receives mapped domain objects.
     *
     * @param handler the handler to receive each mapped row, or {@code null} to clear
     * @return this stream
     */
    @Override
    public MappedRowStream<T> handler(Handler<T> handler) {
        if (handler == null) {
            delegate.handler(null);
        } else {
            delegate.handler(row -> handler.handle(mapper.map(row)));
        }
        return this;
    }

    /**
     * Sets the exception handler. Closes the prepared statement before invoking the handler.
     * Translates exceptions via the configured {@link DbExceptionMapper}.
     *
     * @param handler the exception handler, or {@code null} to clear
     * @return this stream
     */
    @Override
    public MappedRowStream<T> exceptionHandler(Handler<Throwable> handler) {
        if (handler == null) {
            delegate.exceptionHandler(null);
        } else {
            delegate.exceptionHandler(err -> {
                closePreparedStatement();
                handler.handle(exceptionMapper.translate(err, "Query.stream() failed: " + sql));
            });
        }
        return this;
    }

    /**
     * Sets the end handler. Closes the prepared statement before invoking the handler.
     *
     * @param handler the end handler, or {@code null} to clear
     * @return this stream
     */
    @Override
    public MappedRowStream<T> endHandler(Handler<Void> handler) {
        if (handler == null) {
            delegate.endHandler(null);
        } else {
            delegate.endHandler(v -> {
                closePreparedStatement();
                handler.handle(null);
            });
        }
        return this;
    }

    /**
     * Pauses the stream.
     *
     * @return this stream
     */
    @Override
    public MappedRowStream<T> pause() {
        delegate.pause();
        return this;
    }

    /**
     * Resumes the stream.
     *
     * @return this stream
     */
    @Override
    public MappedRowStream<T> resume() {
        delegate.resume();
        return this;
    }

    /**
     * Requests the given number of elements from the stream.
     *
     * @param amount the number of elements to fetch
     * @return this stream
     */
    @Override
    public MappedRowStream<T> fetch(long amount) {
        delegate.fetch(amount);
        return this;
    }

    /**
     * Closes the underlying stream and prepared statement, releasing all associated resources. Safe
     * to call multiple times — subsequent calls are no-ops.
     */
    public void close() {
        delegate.close();
        closePreparedStatement();
    }

    private void closePreparedStatement() {
        if (!preparedStatementClosed) {
            preparedStatementClosed = true;
            closePreparedStatement();
        }
    }
}
