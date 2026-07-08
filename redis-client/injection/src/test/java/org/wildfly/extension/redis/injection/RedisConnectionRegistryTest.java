/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class RedisConnectionRegistryTest {

    private static final String KEY_PREFIX = "registry-test-";
    private String lastKey;

    @AfterEach
    void cleanup() {
        if (lastKey != null) {
            RedisConnectionRegistry.unregister(lastKey);
            lastKey = null;
        }
    }

    @Test
    void testRegisterAndGet() {
        lastKey = KEY_PREFIX + "register";
        RedisClientConfig config = new RedisClientConfig();
        RedisConnectionRegistry.register(lastKey, config);
        assertSame(config, RedisConnectionRegistry.get(lastKey));
    }

    @Test
    void testGetUnknownReturnsNull() {
        assertNull(RedisConnectionRegistry.get("nonexistent-key-xyz"));
    }

    @Test
    void testUnregister() {
        lastKey = KEY_PREFIX + "unregister";
        RedisClientConfig config = new RedisClientConfig();
        RedisConnectionRegistry.register(lastKey, config);
        assertNotNull(RedisConnectionRegistry.get(lastKey));
        RedisConnectionRegistry.unregister(lastKey);
        assertNull(RedisConnectionRegistry.get(lastKey));
        lastKey = null;
    }

    @Test
    void testUnregisterNonexistentDoesNotThrow() {
        assertDoesNotThrow(() -> RedisConnectionRegistry.unregister("never-registered"));
    }

    @Test
    void testRegisterOverwritesPrevious() {
        lastKey = KEY_PREFIX + "overwrite";
        RedisClientConfig config1 = new RedisClientConfig();
        RedisClientConfig config2 = new RedisClientConfig();
        RedisConnectionRegistry.register(lastKey, config1);
        RedisConnectionRegistry.register(lastKey, config2);
        assertSame(config2, RedisConnectionRegistry.get(lastKey));
    }

    @Test
    void testRegisterNullKeyThrows() {
        assertThrows(NullPointerException.class,
                () -> RedisConnectionRegistry.register(null, new RedisClientConfig()));
    }

    @Test
    void testRegisterNullConfigThrows() {
        assertThrows(NullPointerException.class,
                () -> RedisConnectionRegistry.register("null-config", null));
    }
}
