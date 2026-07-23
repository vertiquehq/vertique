// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the exception type {@code T} from a set of {@link ExceptionMapper ExceptionMapper&lt;T&gt;}
 * instances and produces a map from exception type to mapper.
 *
 * <p>Uses {@link TypeResolver} to walk the class hierarchy of each mapper and extract
 * the concrete type argument. Mappers whose type argument cannot be resolved are logged and skipped.
 */
@Slf4j
final class ExceptionMapperResolver {

    private ExceptionMapperResolver() {}

    /**
     * Resolves the exception type from each mapper in the given set and returns an ordered map
     * from exception type to mapper.
     *
     * @param mappers the set of typed exception mapper instances to resolve
     * @return an ordered map from exception type to mapper; never {@code null}
     */
    @SuppressWarnings("unchecked")
    static Map<Class<? extends Throwable>, ExceptionMapper<?>> resolve(Set<ExceptionMapper<?>> mappers) {
        Map<Class<? extends Throwable>, ExceptionMapper<?>> results = new LinkedHashMap<>();
        for (ExceptionMapper<?> mapper : mappers) {
            Class<?> mapperClass = mapper.getClass();
            Class<?> typeArg = TypeResolver.resolveTypeArgument(mapperClass, ExceptionMapper.class);

            if (typeArg == null || !Throwable.class.isAssignableFrom(typeArg)) {
                log.warn("Could not resolve ExceptionMapper type parameter for {} — skipping", mapperClass.getName());
                continue;
            }

            results.put((Class<? extends Throwable>) typeArg, mapper);
        }
        return results;
    }
}
