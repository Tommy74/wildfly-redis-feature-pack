/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.AdditionalMatchers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import com.redis.testcontainers.RedisContainer;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.marshall.persistence.PersistenceMarshaller;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.persistence.spi.MarshallableEntryFactory;
import org.infinispan.persistence.spi.NonBlockingStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.extension.redis.injection.RedisConnectionRegistry;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

public class RedisNonBlockingStoreIT {

    private static RedisContainer redis;
    private static final String CONN_NAME = "store-it-conn";

    private UnifiedJedis directJedis;
    private RedisNonBlockingStore<String, String> store;

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
        directJedis = config.createUnifiedJedis();
        directJedis.flushAll();
    }

    @AfterEach
    void teardown() {
        if (store != null) {
            store.stop().toCompletableFuture().join();
            store = null;
        }
        if (directJedis != null) directJedis.close();
    }

    private RedisNonBlockingStore<String, String> createAndStartStore(String cacheName, boolean useConnectionRegistry) throws Exception {
        return createAndStartStore(cacheName, useConnectionRegistry, CONN_NAME);
    }

    @SuppressWarnings("unchecked")
    private RedisNonBlockingStore<String, String> createAndStartStore(String cacheName, boolean useConnectionRegistry, String connectionName) throws Exception {
        PersistenceMarshaller marshaller = mock(PersistenceMarshaller.class);
        when(marshaller.objectToByteBuffer(any())).thenAnswer(inv ->
                inv.getArgument(0).toString().getBytes(StandardCharsets.UTF_8));

        MarshallableEntryFactory<String, String> entryFactory = mock(MarshallableEntryFactory.class);
        doAnswer(inv -> {
            ByteBuffer keyBuf = inv.getArgument(0);
            ByteBuffer valBuf = inv.getArgument(1);
            long created = inv.getArgument(4);
            long lastUsed = inv.getArgument(5);
            return mockEntry(
                    keyBuf != null ? new String(keyBuf.getBuf(), keyBuf.getOffset(), keyBuf.getLength(), StandardCharsets.UTF_8) : null,
                    valBuf != null ? new String(valBuf.getBuf(), valBuf.getOffset(), valBuf.getLength(), StandardCharsets.UTF_8) : null,
                    created, lastUsed);
        }).when(entryFactory).create(
                nullable(ByteBuffer.class), nullable(ByteBuffer.class),
                nullable(ByteBuffer.class), nullable(ByteBuffer.class),
                anyLong(), anyLong());

        var cache = mock(org.infinispan.AdvancedCache.class);
        when(cache.getName()).thenReturn(cacheName);

        Properties props = new Properties();
        if (useConnectionRegistry) {
            props.setProperty("connection", connectionName);
        } else {
            props.setProperty("cluster-nodes", redis.getHost() + ":" + redis.getMappedPort(6379));
        }
        StoreConfiguration storeConfig = mock(StoreConfiguration.class);
        when(storeConfig.properties()).thenReturn(props);

        InitializationContext ctx = mock(InitializationContext.class);
        when(ctx.getPersistenceMarshaller()).thenReturn(marshaller);
        doReturn(entryFactory).when(ctx).getMarshallableEntryFactory();
        when(ctx.getNonBlockingExecutor()).thenReturn(ForkJoinPool.commonPool());
        doReturn(cache).when(ctx).getCache();
        when(ctx.getConfiguration()).thenReturn(storeConfig);

        RedisNonBlockingStore<String, String> s = new RedisNonBlockingStore<>();
        s.start(ctx).toCompletableFuture().get(10, TimeUnit.SECONDS);
        return s;
    }

    @SuppressWarnings("unchecked")
    private MarshallableEntry<String, String> mockEntry(String key, String value, long created, long lastUsed) {
        MarshallableEntry<String, String> entry = mock(MarshallableEntry.class);
        when(entry.getKey()).thenReturn(key);
        byte[] keyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] valBytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        when(entry.getKeyBytes()).thenReturn(ByteBufferImpl.create(keyBytes, 0, keyBytes.length));
        when(entry.getValueBytes()).thenReturn(ByteBufferImpl.create(valBytes, 0, valBytes.length));
        when(entry.getMetadataBytes()).thenReturn(null);
        when(entry.getInternalMetadataBytes()).thenReturn(null);
        when(entry.created()).thenReturn(created);
        when(entry.lastUsed()).thenReturn(lastUsed);
        when(entry.expiryTime()).thenReturn(-1L);
        return entry;
    }

    // --- characteristics ---

    @Test
    void testCharacteristics() {
        RedisNonBlockingStore<?, ?> s = new RedisNonBlockingStore<>();
        var chars = s.characteristics();
        assertTrue(chars.contains(NonBlockingStore.Characteristic.BULK_READ));
        assertTrue(chars.contains(NonBlockingStore.Characteristic.EXPIRATION));
        assertTrue(chars.contains(NonBlockingStore.Characteristic.SHAREABLE));
        assertEquals(3, chars.size());
    }

    // --- start ---

    @Test
    void testStartViaConnectionRegistry() throws Exception {
        store = createAndStartStore("start-registry", true);
        assertNotNull(store);
        directJedis.set("ping-test", "pong");
        assertEquals("pong", directJedis.get("ping-test"));
    }

    @Test
    void testStartViaClusterNodes() throws Exception {
        store = createAndStartStore("start-cluster", false);
        assertNotNull(store);
    }

    // --- write + load (serialization roundtrip) ---

    @Test
    void testWriteAndLoad() throws Exception {
        store = createAndStartStore("write-load", true);
        MarshallableEntry<String, String> entry = mockEntry("mykey", "myvalue", 1000L, 2000L);

        store.write(0, entry).toCompletableFuture().get(5, TimeUnit.SECONDS);

        MarshallableEntry<String, String> loaded = store.load(0, "mykey").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(loaded, "Loaded entry should not be null");
        assertEquals("mykey", loaded.getKey());
    }

    @Test
    void testWriteStoresInRedis() throws Exception {
        store = createAndStartStore("write-redis", true);
        store.write(0, mockEntry("rkey", "rval", 100L, 200L)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        ScanParams params = new ScanParams().match("wf:ispn:write-redis:*").count(100);
        ScanResult<String> result = directJedis.scan(ScanParams.SCAN_POINTER_START, params);
        assertFalse(result.getResult().isEmpty(), "Expected Redis keys with store prefix");
    }

    @Test
    void testLoadMiss() throws Exception {
        store = createAndStartStore("load-miss", true);
        MarshallableEntry<String, String> loaded = store.load(0, "nonexistent").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNull(loaded);
    }

    // --- delete ---

    @Test
    void testDelete() throws Exception {
        store = createAndStartStore("delete", true);
        store.write(0, mockEntry("delkey", "delval", 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        Boolean removed = store.delete(0, "delkey").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(removed);

        MarshallableEntry<String, String> loaded = store.load(0, "delkey").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNull(loaded);
    }

    @Test
    void testDeleteMiss() throws Exception {
        store = createAndStartStore("delete-miss", true);
        Boolean removed = store.delete(0, "nope").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(removed);
    }

    // --- clear ---

    @Test
    void testClear() throws Exception {
        store = createAndStartStore("clear", true);
        for (int i = 0; i < 3; i++) {
            store.write(0, mockEntry("ck" + i, "cv" + i, 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        store.clear().toCompletableFuture().get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 3; i++) {
            assertNull(store.load(0, "ck" + i).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    // --- size ---

    @Test
    void testSize() throws Exception {
        store = createAndStartStore("size", true);
        for (int i = 0; i < 4; i++) {
            store.write(0, mockEntry("sk" + i, "sv" + i, 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        Long size = store.size(null).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(4L, size);
    }

    // --- publishEntries ---

    @Test
    void testPublishEntries() throws Exception {
        store = createAndStartStore("publish", true);
        for (int i = 0; i < 3; i++) {
            store.write(0, mockEntry("pk" + i, "pv" + i, 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        List<MarshallableEntry<String, String>> collected = collectPublished(store, null);
        assertEquals(3, collected.size());
    }

    @Test
    void testPublishEntriesWithFilter() throws Exception {
        store = createAndStartStore("publish-filter", true);
        store.write(0, mockEntry("alpha", "v1", 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        store.write(0, mockEntry("beta", "v2", 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        store.write(0, mockEntry("gamma", "v3", 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        List<MarshallableEntry<String, String>> collected = collectPublished(store, k -> k.startsWith("a") || k.startsWith("g"));
        assertEquals(2, collected.size());
    }

    @Test
    void testPublishEntriesEmpty() throws Exception {
        store = createAndStartStore("publish-empty", true);
        List<MarshallableEntry<String, String>> collected = collectPublished(store, null);
        assertTrue(collected.isEmpty());
    }

    // --- stop ---

    @Test
    void testStopClosesActiveConnection() throws Exception {
        store = createAndStartStore("stop-active", true);
        store.write(0, mockEntry("stopk", "stopv", 0L, 0L)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertDoesNotThrow(() -> store.stop().toCompletableFuture().get(5, TimeUnit.SECONDS));
        store = null;
    }

    @Test
    void testStopWithoutStart() {
        RedisNonBlockingStore<?, ?> s = new RedisNonBlockingStore<>();
        assertDoesNotThrow(() -> s.stop().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void testStartFailsWhenRedisUnreachable() {
        String connName = "unreachable-conn";
        RedisConnectionRegistry.register(connName,
            new RedisClientConfig().clusterNodes(Set.of(new HostAndPort("127.0.0.1", 1))));
        try {
            assertThrows(Exception.class, () -> createAndStartStore("unreachable-test", true, connName));
        } finally {
            RedisConnectionRegistry.unregister(connName);
        }
    }

    // --- raw Redis tests (kept for direct Redis behavior coverage) ---

    @Test
    void testKeyPrefixIsolation() {
        directJedis.set("wf:ispn:cache-a:key1", "val1");
        directJedis.set("wf:ispn:cache-b:key1", "val1");
        directJedis.set("unrelated-key", "val");

        ScanParams paramsA = new ScanParams().match("wf:ispn:cache-a:*").count(100);
        ScanResult<String> resultA = directJedis.scan(ScanParams.SCAN_POINTER_START, paramsA);
        assertEquals(1, resultA.getResult().size());
        assertTrue(resultA.getResult().get(0).startsWith("wf:ispn:cache-a:"));
    }

    @Test
    void testTtlExpiration() throws Exception {
        String redisKey = "wf:ispn:ttl-cache:expiring";
        directJedis.psetex(redisKey.getBytes(), 500, "ttl-value".getBytes());

        assertNotNull(directJedis.get(redisKey.getBytes()));
        Thread.sleep(700);
        assertNull(directJedis.get(redisKey.getBytes()));
    }

    @Test
    void testConnectionRegistryLookup() {
        RedisClientConfig config = RedisConnectionRegistry.get(CONN_NAME);
        assertNotNull(config);
        try (UnifiedJedis j = config.createUnifiedJedis()) {
            assertEquals("PONG", j.ping());
        }
    }

    // --- helpers ---

    private List<MarshallableEntry<String, String>> collectPublished(
            RedisNonBlockingStore<String, String> s,
            java.util.function.Predicate<? super String> filter) throws Exception {

        List<MarshallableEntry<String, String>> result = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];

        s.publishEntries(null, filter, true).subscribe(new Subscriber<>() {
            @Override public void onSubscribe(Subscription sub) { sub.request(Long.MAX_VALUE); }
            @Override public void onNext(MarshallableEntry<String, String> entry) { result.add(entry); }
            @Override public void onError(Throwable t) { error[0] = t; latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "publishEntries did not complete in time");
        if (error[0] != null) fail("publishEntries errored: " + error[0]);
        return result;
    }
}
