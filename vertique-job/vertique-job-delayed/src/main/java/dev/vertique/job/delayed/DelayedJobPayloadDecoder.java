// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.json.VertiqueJson;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Converts a stored job payload to the type its handler declares.
 *
 * <p>A payload comes back from the job store in its JSON form, and the service invoker passes the
 * envelope payload to the handler by reference. {@link DelayedJobPoller} therefore converts it first.
 * The target type always comes from the handler's declaration, never from the stored row, so a stored
 * payload cannot choose the type it deserializes into.
 */
final class DelayedJobPayloadDecoder {

    private final Map<String, Type> typesByAddress;

    /**
     * Creates a decoder for the given handlers.
     *
     * @param typesByAddress declared payload types by handler event bus address
     */
    DelayedJobPayloadDecoder(Map<String, Type> typesByAddress) {
        this.typesByAddress = Map.copyOf(typesByAddress);
    }

    /**
     * Converts a stored payload to the type the handler at {@code address} declares.
     *
     * <p>The payload is returned unchanged when it is {@code null}, when the handler declares no
     * payload, or when it already is an instance of the declared type.
     *
     * @param address the handler's event bus address
     * @param stored the stored payload, or {@code null}
     * @return the converted payload, or {@code null}
     * @throws IllegalArgumentException if the payload cannot be converted. The message names only the
     *                                  type: Jackson's own message quotes the value, and this one
     *                                  reaches error columns and logs
     */
    Object decode(String address, Object stored) {
        Type type = typesByAddress.get(address);
        if (stored == null || type == null || (type instanceof Class<?> declared && declared.isInstance(stored))) {
            return stored;
        }
        try {
            var mapper = VertiqueJson.mapper();
            return mapper.convertValue(stored, mapper.constructType(type));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Job payload cannot be decoded to " + type.getTypeName());
        }
    }
}
