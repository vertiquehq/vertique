// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Resource methods whose names state their return shape, for classifying responses from captured
 * response facts. Each method takes no parameter and returns {@code null}: only its generic return
 * type is read.
 *
 * <p>The methods carry no JAX-RS annotation and no {@code @Produces}: a test supplies the produces
 * list of each response shape it builds, so one method stands for several rows (for example {@link
 * #dto()} with no produces type, with {@code application/xml}, or with {@code text/event-stream}).
 * The class is never deployed: the runtime scanner rejects some of these shapes (a {@code Future}
 * of a type variable or wildcard), which only the documentation classifies.
 */
public class ShapeResource {

    /** Creates the resource. */
    public ShapeResource() {}

    /** Returns {@code void}. */
    public void voidReturn() {
        // Nothing to return.
    }

    /**
     * Returns {@code Void}.
     *
     * @return {@code null}
     */
    public Void voidObject() {
        return null;
    }

    /**
     * Returns {@code Future<Void>}.
     *
     * @return {@code null}
     */
    public Future<Void> futureOfVoid() {
        return null;
    }

    /**
     * Returns {@code Future<Dto>}.
     *
     * @return {@code null}
     */
    public Future<Dto> futureOfDto() {
        return null;
    }

    /**
     * Returns a plain {@code Dto}.
     *
     * @return {@code null}
     */
    public Dto dto() {
        return null;
    }

    /**
     * Returns {@code Future<List<Dto>>}.
     *
     * @return {@code null}
     */
    public Future<List<Dto>> futureOfListOfDto() {
        return null;
    }

    /**
     * Returns {@code String}.
     *
     * @return {@code null}
     */
    public String stringReturn() {
        return null;
    }

    /**
     * Returns {@code Future<String>}.
     *
     * @return {@code null}
     */
    public Future<String> futureOfString() {
        return null;
    }

    /**
     * Returns {@code Response}.
     *
     * @return {@code null}
     */
    public Response response() {
        return null;
    }

    /**
     * Returns {@code Future<Response>}.
     *
     * @return {@code null}
     */
    public Future<Response> futureOfResponse() {
        return null;
    }

    /**
     * Returns {@code CompletionStage<Dto>}.
     *
     * @return {@code null}
     */
    public CompletionStage<Dto> completionStageOfDto() {
        return null;
    }

    /**
     * Returns {@code Optional<Dto>}.
     *
     * @return {@code null}
     */
    public Optional<Dto> optionalOfDto() {
        return null;
    }

    /**
     * Returns {@code ReadStream<Buffer>}.
     *
     * @return {@code null}
     */
    public ReadStream<Buffer> readStreamOfBuffer() {
        return null;
    }

    /**
     * Returns {@code byte[]}.
     *
     * @return {@code null}
     */
    public byte[] bytes() {
        return null;
    }

    /**
     * Returns {@code Buffer}.
     *
     * @return {@code null}
     */
    public Buffer buffer() {
        return null;
    }

    /**
     * Returns {@code Custom}, a type with a registered response producer.
     *
     * @return {@code null}
     */
    public Custom custom() {
        return null;
    }

    /**
     * Returns {@code SubCustom}, a subclass of a type with a registered response producer.
     *
     * @return {@code null}
     */
    public SubCustom subCustom() {
        return null;
    }

    /**
     * Returns a raw {@code Future} without a type argument.
     *
     * @return {@code null}
     */
    @SuppressWarnings("rawtypes")
    public Future rawFuture() {
        return null;
    }

    /**
     * Returns the method-level type variable {@code T}, which no resource class binds.
     *
     * @param <T> the method's own type variable
     * @return {@code null}
     */
    public <T> T any() {
        return null;
    }

    /**
     * Returns {@code List<?>}, which holds a wildcard.
     *
     * @return {@code null}
     */
    public List<?> listOfWildcard() {
        return null;
    }
}
