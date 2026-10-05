// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Test-only JDK resolver provider (registered via {@code META-INF/services}) that records every
 * host name handed to the name-resolution layer and then delegates to the built-in resolver.
 *
 * <p>{@code InetAddress.getByName} on a string the JDK does not parse as an IP literal ends up here,
 * so a test can assert that attacker-shaped input never triggers a lookup.
 */
public final class RecordingInetAddressResolverProvider extends InetAddressResolverProvider {

    private static final List<String> LOOKED_UP = new CopyOnWriteArrayList<>();

    /**
     * Returns a snapshot of every host name resolved so far in this JVM.
     *
     * @return host names in lookup order
     */
    public static List<String> lookedUpHosts() {
        return List.copyOf(LOOKED_UP);
    }

    /** Number of recorded lookups so far; use with {@link #lookedUpHosts()} for delta assertions. */
    public static int lookupCount() {
        return LOOKED_UP.size();
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                LOOKED_UP.add(host);
                return builtin.lookupByName(host, policy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "vertique-recording-test-resolver";
    }
}
