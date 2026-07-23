// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A success/failure monad for event bus responses.
 * Provides functional operations for composing results without throwing exceptions.
 *
 * @param <T> the type of the success value
 */
public sealed interface Result<T> {

    /**
     * Creates a successful result.
     */
    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    /**
     * Creates a failed result.
     */
    static <T> Result<T> failure(Throwable cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        return new Failure<>(cause);
    }

    boolean isSuccess();

    boolean isFailure();

    /**
     * Returns the success value, or throws the failure cause wrapped in a RuntimeException.
     *
     * @throws NoSuchElementException if this is a failure
     */
    T get();

    /**
     * Returns the success value as an Optional.
     */
    Optional<T> toOptional();

    /**
     * Returns the failure cause, or null if this is a success.
     */
    Throwable cause();

    /**
     * Transforms the success value using the given function.
     * If this is a failure, returns the failure unchanged.
     */
    <U> Result<U> map(Function<T, U> mapper);

    /**
     * Transforms the success value using a function that returns a Result.
     * If this is a failure, returns the failure unchanged.
     */
    <U> Result<U> flatMap(Function<T, Result<U>> mapper);

    /**
     * Recovers from a failure using the given function.
     * If this is a success, returns it unchanged.
     */
    Result<T> recover(Function<Throwable, T> recoveryFn);

    /**
     * Applies one of two functions depending on success or failure.
     */
    <U> U fold(Function<T, U> onSuccess, Function<Throwable, U> onFailure);

    record Success<T>(T value) implements Result<T> {

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.ofNullable(value);
        }

        @Override
        public Throwable cause() {
            return null;
        }

        @Override
        public <U> Result<U> map(Function<T, U> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            try {
                return new Success<>(mapper.apply(value));
            } catch (Exception e) {
                return new Failure<>(e);
            }
        }

        @Override
        public <U> Result<U> flatMap(Function<T, Result<U>> mapper) {
            Objects.requireNonNull(mapper, "mapper must not be null");
            try {
                return mapper.apply(value);
            } catch (Exception e) {
                return new Failure<>(e);
            }
        }

        @Override
        public Result<T> recover(Function<Throwable, T> recoveryFn) {
            return this;
        }

        @Override
        public <U> U fold(Function<T, U> onSuccess, Function<Throwable, U> onFailure) {
            Objects.requireNonNull(onSuccess, "onSuccess must not be null");
            return onSuccess.apply(value);
        }
    }

    record Failure<T>(Throwable error) implements Result<T> {

        public Failure {
            Objects.requireNonNull(error, "error must not be null");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T get() {
            throw new NoSuchElementException("Result is a failure: " + error.getMessage(), error);
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.empty();
        }

        @Override
        public Throwable cause() {
            return error;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U> map(Function<T, U> mapper) {
            return (Result<U>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U> flatMap(Function<T, Result<U>> mapper) {
            return (Result<U>) this;
        }

        @Override
        public Result<T> recover(Function<Throwable, T> recoveryFn) {
            Objects.requireNonNull(recoveryFn, "recoveryFn must not be null");
            try {
                return new Success<>(recoveryFn.apply(error));
            } catch (Exception e) {
                return new Failure<>(e);
            }
        }

        @Override
        public <U> U fold(Function<T, U> onSuccess, Function<Throwable, U> onFailure) {
            Objects.requireNonNull(onFailure, "onFailure must not be null");
            return onFailure.apply(error);
        }
    }
}
