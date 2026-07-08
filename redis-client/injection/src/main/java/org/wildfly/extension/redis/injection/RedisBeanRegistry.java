/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import jakarta.enterprise.inject.spi.Extension;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedisBeanRegistry {

    private static final Map<String, RedisClientConfig> redisConnections = new HashMap<>();

    public static void register(String name, RedisClientConfig config) {
        redisConnections.put(name, config);
    }

    public static void unregister(String name) {
        redisConnections.remove(name);
    }

    public static List<Extension> getCDIExtensions() {
        return List.of(new RedisPortableExtension(new HashMap<>(redisConnections)));
    }
}
