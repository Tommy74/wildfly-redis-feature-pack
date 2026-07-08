/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Static registry that bridges the redis-client WildFly subsystem configuration
 * to consumers that cannot access WildFly MSC services (e.g., custom Infinispan stores).
 * <p>
 * Populated by the subsystem's service configurator at boot time.
 * Read by the custom store's start() method.
 */
public final class RedisConnectionRegistry {

    private static final ConcurrentMap<String, RedisClientConfig> CONNECTIONS = new ConcurrentHashMap<>();

    private RedisConnectionRegistry() {}

    public static void register(String name, RedisClientConfig config) {
        CONNECTIONS.put(name, config);
    }

    public static RedisClientConfig get(String name) {
        return CONNECTIONS.get(name);
    }

    public static void unregister(String name) {
        CONNECTIONS.remove(name);
    }
}
