/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeShutdown;
import jakarta.enterprise.inject.spi.Extension;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import redis.clients.jedis.UnifiedJedis;

public class RedisPortableExtension implements Extension {

    private final Map<String, RedisClientConfig> configs;
    private final Map<String, UnifiedJedis> pools = new ConcurrentHashMap<>();

    public RedisPortableExtension(Map<String, RedisClientConfig> configs) {
        this.configs = configs;
    }

    void afterBeanDiscovery(@Observes AfterBeanDiscovery abd) {
        for (Map.Entry<String, RedisClientConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            RedisClientConfig config = entry.getValue();
            abd.addBean()
                    .types(UnifiedJedis.class)
                    .qualifiers(new RedisConnectionLiteral(name))
                    .scope(Dependent.class)
                    .name(name)
                    .produceWith(instance -> pools.computeIfAbsent(name, k -> config.createUnifiedJedis()))
                    .disposeWith((jedis, instance) -> { });
        }
    }

    void beforeShutdown(@Observes BeforeShutdown event) {
        pools.values().forEach(UnifiedJedis::close);
        pools.clear();
    }
}
