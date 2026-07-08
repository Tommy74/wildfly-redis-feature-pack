/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

public class RedisSingleNodeIT {

    private static RedisContainer redis;

    @BeforeAll
    static void startRedis() {
        redis = new RedisContainer("redis:7-alpine");
        redis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) redis.stop();
    }

    @Test
    void testSingleNodeCreatesJedisPooled() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redis.getHost(), redis.getMappedPort(6379))));
        UnifiedJedis jedis = config.createUnifiedJedis();
        assertNotNull(jedis);
        assertTrue(jedis instanceof JedisPooled);
        jedis.close();
    }

    @Test
    void testPing() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redis.getHost(), redis.getMappedPort(6379))));
        try (UnifiedJedis jedis = config.createUnifiedJedis()) {
            assertEquals("PONG", jedis.ping());
        }
    }

    @Test
    void testSetGetDelete() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redis.getHost(), redis.getMappedPort(6379))));
        try (UnifiedJedis jedis = config.createUnifiedJedis()) {
            String key = "test-" + System.currentTimeMillis();
            jedis.set(key, "value");
            assertEquals("value", jedis.get(key));
            jedis.del(key);
            assertNull(jedis.get(key));
        }
    }

    @Test
    void testPasswordConfig() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort("localhost", 6379)))
                .password("secret")
                .connectionTimeout(3000)
                .maxPoolSize(4)
                .minIdle(1);
        assertNotNull(config);
    }
}
