/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.extension.redis.injection.RedisConnectionRegistry;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Tests the {@link RedisNonBlockingStore} Redis operations directly.
 * <p>
 * The WildFly BOM manages protostream 6.0.x while Infinispan 15.0.x requires 5.0.x,
 * making {@code EmbeddedCacheManager} unusable in this module. Instead, we wire
 * the store's Jedis client directly and test the raw Redis read/write paths.
 */
public class RedisNonBlockingStoreIT {

    private static RedisContainer redis;
    private static final String CONN_NAME = "store-it-conn";

    private UnifiedJedis jedis;

    @BeforeAll
    static void startRedis() {
        redis = new RedisContainer("redis:7-alpine");
        redis.start();

        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redis.getHost(), redis.getMappedPort(6379))));
        RedisConnectionRegistry.register(CONN_NAME, config);
    }

    @AfterAll
    static void stopRedis() {
        RedisConnectionRegistry.unregister(CONN_NAME);
        if (redis != null) redis.stop();
    }

    @BeforeEach
    void setup() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redis.getHost(), redis.getMappedPort(6379))));
        jedis = config.createUnifiedJedis();
        jedis.flushAll();
    }

    @AfterEach
    void teardown() {
        if (jedis != null) jedis.close();
    }

    @Test
    void testCharacteristics() {
        RedisNonBlockingStore<?, ?> store = new RedisNonBlockingStore<>();
        var chars = store.characteristics();
        assertTrue(chars.contains(org.infinispan.persistence.spi.NonBlockingStore.Characteristic.BULK_READ));
        assertTrue(chars.contains(org.infinispan.persistence.spi.NonBlockingStore.Characteristic.EXPIRATION));
        assertTrue(chars.contains(org.infinispan.persistence.spi.NonBlockingStore.Characteristic.SHAREABLE));
        assertEquals(3, chars.size());
    }

    @Test
    void testWriteAndReadRawBytes() {
        String keyPrefix = "wf:ispn:test-cache:";
        String redisKey = keyPrefix + "testkey";
        byte[] data = "testvalue".getBytes();

        jedis.set(redisKey.getBytes(), data);
        byte[] result = jedis.get(redisKey.getBytes());
        assertArrayEquals(data, result);
    }

    @Test
    void testDeleteKey() {
        String redisKey = "wf:ispn:test-cache:delkey";
        jedis.set(redisKey, "value");
        assertEquals("value", jedis.get(redisKey));

        long removed = jedis.del(redisKey);
        assertEquals(1, removed);
        assertNull(jedis.get(redisKey));
    }

    @Test
    void testDeleteNonexistentKey() {
        long removed = jedis.del("wf:ispn:test-cache:nonexistent");
        assertEquals(0, removed);
    }

    @Test
    void testClearViaScan() {
        String keyPrefix = "wf:ispn:clear-cache:";
        for (int i = 0; i < 10; i++) {
            jedis.set(keyPrefix + "key" + i, "val" + i);
        }

        ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
        String cursor = ScanParams.SCAN_POINTER_START;
        int deleted = 0;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                jedis.del(key);
                deleted++;
            }
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

        assertEquals(10, deleted);

        ScanResult<String> check = jedis.scan(ScanParams.SCAN_POINTER_START, params);
        assertTrue(check.getResult().isEmpty());
    }

    @Test
    void testSizeViaScan() {
        String keyPrefix = "wf:ispn:size-cache:";
        for (int i = 0; i < 5; i++) {
            jedis.set(keyPrefix + "key" + i, "val" + i);
        }

        long count = 0;
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            count += result.getResult().size();
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

        assertEquals(5, count);
    }

    @Test
    void testKeyPrefixIsolation() {
        jedis.set("wf:ispn:cache-a:key1", "val1");
        jedis.set("wf:ispn:cache-b:key1", "val1");
        jedis.set("unrelated-key", "val");

        ScanParams paramsA = new ScanParams().match("wf:ispn:cache-a:*").count(100);
        ScanResult<String> resultA = jedis.scan(ScanParams.SCAN_POINTER_START, paramsA);
        assertEquals(1, resultA.getResult().size());
        assertTrue(resultA.getResult().get(0).startsWith("wf:ispn:cache-a:"));
    }

    @Test
    void testTtlExpiration() throws Exception {
        String redisKey = "wf:ispn:ttl-cache:expiring";
        jedis.psetex(redisKey.getBytes(), 500, "ttl-value".getBytes());

        assertNotNull(jedis.get(redisKey.getBytes()));
        Thread.sleep(700);
        assertNull(jedis.get(redisKey.getBytes()));
    }

    @Test
    void testConnectionRegistryLookup() {
        RedisClientConfig config = RedisConnectionRegistry.get(CONN_NAME);
        assertNotNull(config, "Connection '" + CONN_NAME + "' should be in registry");
        try (UnifiedJedis registryJedis = config.createUnifiedJedis()) {
            assertEquals("PONG", registryJedis.ping());
        }
    }

    @Test
    void testConnectionRegistryMissReturnsNull() {
        assertNull(RedisConnectionRegistry.get("nonexistent-connection"));
    }

    @Test
    void testStopClosesGracefully() {
        RedisNonBlockingStore<?, ?> store = new RedisNonBlockingStore<>();
        assertDoesNotThrow(() -> store.stop().toCompletableFuture().get());
    }
}
