// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.Result;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResultTest {

    @Nested
    @DisplayName("Success")
    class SuccessTests {

        @Test
        void shouldBeSuccess() {
            Result<String> result = Result.success("hello");
            assertTrue(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        void shouldReturnValue() {
            Result<String> result = Result.success("hello");
            assertEquals("hello", result.get());
        }

        @Test
        void shouldReturnOptionalWithValue() {
            Result<String> result = Result.success("hello");
            assertTrue(result.toOptional().isPresent());
            assertEquals("hello", result.toOptional().get());
        }

        @Test
        void shouldReturnNullCause() {
            Result<String> result = Result.success("hello");
            assertNull(result.cause());
        }

        @Test
        void shouldAllowNullValue() {
            Result<String> result = Result.success(null);
            assertTrue(result.isSuccess());
            assertNull(result.get());
            assertTrue(result.toOptional().isEmpty());
        }

        @Test
        void shouldMapValue() {
            Result<Integer> result = Result.success("hello").map(String::length);
            assertTrue(result.isSuccess());
            assertEquals(5, result.get());
        }

        @Test
        void shouldCaptureMapException() {
            Result<Integer> result = Result.<String>success(null).map(s -> s.length());
            assertTrue(result.isFailure());
            assertInstanceOf(NullPointerException.class, result.cause());
        }

        @Test
        void shouldFlatMapValue() {
            Result<Integer> result = Result.success("hello").flatMap(s -> Result.success(s.length()));
            assertTrue(result.isSuccess());
            assertEquals(5, result.get());
        }

        @Test
        void shouldFlatMapToFailure() {
            Result<Integer> result = Result.success("hello").flatMap(s -> Result.failure(new RuntimeException("oops")));
            assertTrue(result.isFailure());
            assertEquals("oops", result.cause().getMessage());
        }

        @Test
        void shouldNotRecover() {
            Result<String> result = Result.success("hello").recover(t -> "recovered");
            assertTrue(result.isSuccess());
            assertEquals("hello", result.get());
        }

        @Test
        void shouldFoldToSuccess() {
            String folded = Result.success("hello").fold(s -> "success: " + s, t -> "failure: " + t.getMessage());
            assertEquals("success: hello", folded);
        }
    }

    @Nested
    @DisplayName("Failure")
    class FailureTests {

        @Test
        void shouldBeFailure() {
            Result<String> result = Result.failure(new RuntimeException("oops"));
            assertFalse(result.isSuccess());
            assertTrue(result.isFailure());
        }

        @Test
        void shouldThrowOnGet() {
            Result<String> result = Result.failure(new RuntimeException("oops"));
            assertThrows(NoSuchElementException.class, result::get);
        }

        @Test
        void shouldReturnEmptyOptional() {
            Result<String> result = Result.failure(new RuntimeException("oops"));
            assertTrue(result.toOptional().isEmpty());
        }

        @Test
        void shouldReturnCause() {
            RuntimeException cause = new RuntimeException("oops");
            Result<String> result = Result.failure(cause);
            assertSame(cause, result.cause());
        }

        @Test
        void shouldRejectNullCause() {
            assertThrows(NullPointerException.class, () -> Result.failure(null));
        }

        @Test
        void shouldNotMap() {
            Result<Integer> result =
                    Result.<String>failure(new RuntimeException("oops")).map(String::length);
            assertTrue(result.isFailure());
            assertEquals("oops", result.cause().getMessage());
        }

        @Test
        void shouldNotFlatMap() {
            Result<Integer> result =
                    Result.<String>failure(new RuntimeException("oops")).flatMap(s -> Result.success(s.length()));
            assertTrue(result.isFailure());
            assertEquals("oops", result.cause().getMessage());
        }

        @Test
        void shouldRecover() {
            Result<String> result = Result.<String>failure(new RuntimeException("oops"))
                    .recover(t -> "recovered from " + t.getMessage());
            assertTrue(result.isSuccess());
            assertEquals("recovered from oops", result.get());
        }

        @Test
        void shouldCaptureRecoverException() {
            Result<String> result = Result.<String>failure(new RuntimeException("oops"))
                    .recover(t -> {
                        throw new RuntimeException("recover failed");
                    });
            assertTrue(result.isFailure());
            assertEquals("recover failed", result.cause().getMessage());
        }

        @Test
        void shouldFoldToFailure() {
            String folded = Result.<String>failure(new RuntimeException("oops"))
                    .fold(s -> "success: " + s, t -> "failure: " + t.getMessage());
            assertEquals("failure: oops", folded);
        }
    }

    @Nested
    @DisplayName("Chaining")
    class ChainingTests {

        @Test
        void shouldChainMultipleMaps() {
            Result<String> result = Result.success(5).map(n -> n * 2).map(n -> "result: " + n);
            assertTrue(result.isSuccess());
            assertEquals("result: 10", result.get());
        }

        @Test
        void shouldShortCircuitOnFailure() {
            Result<String> result = Result.success(5)
                    .map(n -> n / 0) // ArithmeticException
                    .map(n -> "result: " + n);
            assertTrue(result.isFailure());
            assertInstanceOf(ArithmeticException.class, result.cause());
        }

        @Test
        void shouldChainFlatMapAndMap() {
            Result<String> result = Result.success("42")
                    .flatMap(s -> {
                        try {
                            return Result.success(Integer.parseInt(s));
                        } catch (NumberFormatException e) {
                            return Result.failure(e);
                        }
                    })
                    .map(n -> "parsed: " + n);
            assertTrue(result.isSuccess());
            assertEquals("parsed: 42", result.get());
        }
    }
}
