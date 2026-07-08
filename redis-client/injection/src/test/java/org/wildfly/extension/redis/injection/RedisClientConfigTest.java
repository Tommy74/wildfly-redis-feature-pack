/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.NoSuchElementException;
import java.util.Set;

import redis.clients.jedis.HostAndPort;

public class RedisClientConfigTest {

    @org.junit.jupiter.api.Test
    void testDefaultIsNotClusterMode() {
        RedisClientConfig config = new RedisClientConfig();
        assertFalse(config.isClusterMode());
    }

    @org.junit.jupiter.api.Test
    void testSingleNodeIsNotClusterMode() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort("localhost", 6379)));
        assertFalse(config.isClusterMode());
    }

    @org.junit.jupiter.api.Test
    void testMultipleNodesIsClusterMode() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(
                        new HostAndPort("host1", 6379),
                        new HostAndPort("host2", 6379)));
        assertTrue(config.isClusterMode());
    }

    @org.junit.jupiter.api.Test
    void testNullNodesIsNotClusterMode() {
        RedisClientConfig config = new RedisClientConfig().clusterNodes(null);
        assertFalse(config.isClusterMode());
    }

    @org.junit.jupiter.api.Test
    void testBuilderChainingReturnsSameInstance() {
        RedisClientConfig config = new RedisClientConfig();
        assertSame(config, config.password("pass"));
        assertSame(config, config.ssl(true));
        assertSame(config, config.connectionTimeout(5000));
        assertSame(config, config.maxPoolSize(16));
        assertSame(config, config.minIdle(2));
        assertSame(config, config.clusterNodes(Set.of(new HostAndPort("localhost", 6379))));
    }

    @org.junit.jupiter.api.Test
    void testSslConfigDoesNotThrow() {
        assertDoesNotThrow(() -> {
            new RedisClientConfig()
                    .ssl(true)
                    .sslSocketFactory(null);
        });
    }

    @org.junit.jupiter.api.Test
    void testCreateUnifiedJedisWithEmptyNodesThrows() {
        RedisClientConfig config = new RedisClientConfig();
        assertThrows(NoSuchElementException.class, config::createUnifiedJedis);
    }
}
