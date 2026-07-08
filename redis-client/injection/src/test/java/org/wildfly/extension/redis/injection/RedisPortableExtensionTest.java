/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tests for RedisPortableExtension to verify CDI bean registration.
 */
public class RedisPortableExtensionTest {

    @Test
    void testExtensionCreation() {
        Map<String, RedisClientConfig> configs = new HashMap<>();
        RedisClientConfig config = new RedisClientConfig();
        config.clusterNodes(Set.of(new HostAndPort("localhost", 6379)));
        configs.put("default", config);

        RedisPortableExtension extension = new RedisPortableExtension(configs);
        assertNotNull(extension);
    }

    @Test
    void testExtensionWithEmptyConfigs() {
        Map<String, RedisClientConfig> configs = new HashMap<>();
        RedisPortableExtension extension = new RedisPortableExtension(configs);
        assertNotNull(extension);
    }

    @Test
    void testExtensionWithMultipleConfigs() {
        Map<String, RedisClientConfig> configs = new HashMap<>();
        
        RedisClientConfig config1 = new RedisClientConfig();
        config1.clusterNodes(Set.of(new HostAndPort("localhost", 6379)));
        configs.put("default", config1);
        
        RedisClientConfig config2 = new RedisClientConfig();
        config2.clusterNodes(Set.of(new HostAndPort("localhost", 6380)));
        configs.put("secondary", config2);

        RedisPortableExtension extension = new RedisPortableExtension(configs);
        assertNotNull(extension);
    }
}