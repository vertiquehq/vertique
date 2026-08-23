// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider-neutral around interceptor for cacheable object-facing methods. */
@Singleton
public final class CacheableAspect implements AspectProvider<Cacheable> {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private final CacheStore store;
    private final CacheConfig config;

    @Inject
    public CacheableAspect(CacheStore store, CacheConfig config) {
        this.store = store;
        this.config = config;
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Cacheable annotation) {
        CacheRegion region = new CacheRegion("cache", annotation.name(), 1);
        Type declaredType = valueType(target);
        long ttlSeconds = effectiveTtl(annotation);
        return invocation -> {
            if (!config.enabled() || annotation.mode() == CacheMode.CLUSTERED && target.returnType() != Future.class) {
                return invocation.proceed();
            }
            CacheKey key;
            try {
                key = new CacheKey(region, "NONE", renderSelector(annotation.key(), invocation.arguments()));
            } catch (RuntimeException invalidKey) {
                return invocation.proceed();
            }
            return lookupOrProceed(invocation, key, declaredType, ttlSeconds);
        };
    }

    private Future<Object> lookupOrProceed(Invocation invocation, CacheKey key, Type declaredType, long ttlSeconds) {
        Future<Optional<Object>> lookup;
        try {
            lookup = store.get(key, declaredType);
        } catch (Throwable failure) {
            return invocation.proceed();
        }
        return lookup.recover(ignored -> Future.succeededFuture(Optional.empty()))
                .compose(hit -> {
                    if (hit.isPresent()) {
                        return Future.succeededFuture(hit.get());
                    }
                    return invocation.proceed().compose(value -> {
                        if (value == null) {
                            return Future.succeededFuture(null);
                        }
                        try {
                            return store.put(key, value, declaredType, java.time.Duration.ofSeconds(ttlSeconds))
                                    .recover(ignored -> Future.succeededFuture())
                                    .map(value);
                        } catch (Throwable failure) {
                            return Future.succeededFuture(value);
                        }
                    });
                });
    }

    private long effectiveTtl(Cacheable annotation) {
        CacheEntryConfig entry = config.caches().get(annotation.name());
        if (entry != null && entry.ttlSeconds() >= 0) {
            return entry.ttlSeconds();
        }
        return annotation.ttlSeconds() >= 0 ? annotation.ttlSeconds() : config.defaultTtlSeconds();
    }

    private static Type valueType(MethodMetadata target) {
        Type returnType = target.genericReturnType();
        if (returnType instanceof ParameterizedType parameterized && target.returnType() == Future.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return returnType;
    }

    private static String renderSelector(String template, Object[] arguments) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= arguments.length || arguments[index] == null) {
                throw new IllegalArgumentException("cache key argument is missing");
            }
            String replacement = percentEncode(String.valueOf(arguments[index]));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if ((codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= '0' && codePoint <= '9')
                    || codePoint == '.'
                    || codePoint == '-'
                    || codePoint == '_'
                    || codePoint == '~') {
                encoded.appendCodePoint(codePoint);
            } else {
                byte[] bytes =
                        new String(Character.toChars(codePoint)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte valueByte : bytes) {
                    encoded.append('%');
                    encoded.append(Character.toUpperCase(Character.forDigit((valueByte >>> 4) & 0xf, 16)));
                    encoded.append(Character.toUpperCase(Character.forDigit(valueByte & 0xf, 16)));
                }
            }
        });
        return encoded.toString();
    }
}
