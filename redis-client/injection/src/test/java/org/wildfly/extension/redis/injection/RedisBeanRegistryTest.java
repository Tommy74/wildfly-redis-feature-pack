/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.enterprise.inject.spi.Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class RedisBeanRegistryTest {

    private static final String KEY_PREFIX = "bean-test-";
    private String lastKey;
    private String lastKey2;

    @AfterEach
    void cleanup() {
        if (lastKey != null) {
            RedisBeanRegistry.unregister(lastKey);
            lastKey = null;
        }
        if (lastKey2 != null) {
            RedisBeanRegistry.unregister(lastKey2);
            lastKey2 = null;
        }
    }

    @Test
    void testRegisterAndGetCDIExtensions() {
        lastKey = KEY_PREFIX + "register";
        RedisBeanRegistry.register(lastKey, new RedisClientConfig());
        List<Extension> extensions = RedisBeanRegistry.getCDIExtensions();
        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        assertInstanceOf(RedisPortableExtension.class, extensions.get(0));
    }

    @Test
    void testGetCDIExtensionsWhenEmpty() {
        List<Extension> extensions = RedisBeanRegistry.getCDIExtensions();
        assertNotNull(extensions);
        assertEquals(1, extensions.size());
        assertInstanceOf(RedisPortableExtension.class, extensions.get(0));
    }

    @Test
    void testUnregisterRemovesEntry() {
        lastKey = KEY_PREFIX + "unregister";
        RedisBeanRegistry.register(lastKey, new RedisClientConfig());
        RedisBeanRegistry.unregister(lastKey);
        lastKey = null;
        List<Extension> extensions = RedisBeanRegistry.getCDIExtensions();
        assertNotNull(extensions);
        assertEquals(1, extensions.size());
    }

    @Test
    void testGetCDIExtensionsReturnsImmutableList() {
        List<Extension> extensions = RedisBeanRegistry.getCDIExtensions();
        assertThrows(UnsupportedOperationException.class, () -> extensions.add(null));
    }

    @Test
    void testMultipleRegistrations() {
        lastKey = KEY_PREFIX + "multi-a";
        lastKey2 = KEY_PREFIX + "multi-b";
        RedisBeanRegistry.register(lastKey, new RedisClientConfig());
        RedisBeanRegistry.register(lastKey2, new RedisClientConfig());
        List<Extension> extensions = RedisBeanRegistry.getCDIExtensions();
        assertEquals(1, extensions.size());
        assertInstanceOf(RedisPortableExtension.class, extensions.get(0));
    }
}
