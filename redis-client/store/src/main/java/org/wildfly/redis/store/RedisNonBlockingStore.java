/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.commons.util.IntSet;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.marshall.persistence.PersistenceMarshaller;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.persistence.spi.MarshallableEntryFactory;
import org.infinispan.persistence.spi.NonBlockingStore;
import org.infinispan.persistence.spi.PersistenceException;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.extension.redis.injection.RedisConnectionRegistry;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * An Infinispan {@link NonBlockingStore} backed by Redis via Jedis.
 * <p>
 * Configuration is read from Infinispan store properties:
 * <ul>
 *   <li>{@code connection} — references a named redis-client subsystem connection
 *       via {@link RedisConnectionRegistry} (preferred)</li>
 *   <li>{@code cluster-nodes} — comma-separated host:port pairs (fallback, default: 127.0.0.1:6379)</li>
 *   <li>{@code password} — Redis password (optional)</li>
 * </ul>
 * <p>
 * Redis key format: {@code wf:ispn:{cacheName}:{base64(marshalledKey)}}
 */
public class RedisNonBlockingStore<K, V> implements NonBlockingStore<K, V> {

    private static final Logger LOG = Logger.getLogger(RedisNonBlockingStore.class.getName());

    private static final long CONNECTION_LOG_INTERVAL_MS = 60_000;
    private static final long RECONNECT_RETRY_INTERVAL_MS = 1_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 100;
    private static final long MAX_RECONNECT_INTERVAL_MS = 30_000;

    private UnifiedJedis jedis;
    private String keyPrefix;
    private PersistenceMarshaller marshaller;
    private MarshallableEntryFactory<K, V> entryFactory;
    private Executor nonBlockingExecutor;
    private volatile long lastConnectionLostLogMillis;

    @Override
    public CompletionStage<Void> start(InitializationContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                doStart(ctx);
            } catch (Exception e) {
                throw new PersistenceException("Failed to start Redis store", e);
            }
            return null;
        }, ctx.getNonBlockingExecutor());
    }

    private void doStart(InitializationContext ctx) {
        this.marshaller = ctx.getPersistenceMarshaller();
        this.entryFactory = ctx.getMarshallableEntryFactory();
        this.nonBlockingExecutor = ctx.getNonBlockingExecutor();

        String cacheName = ctx.getCache().getName();
        this.keyPrefix = "wf:ispn:" + cacheName + ":";

        StoreConfiguration config = ctx.getConfiguration();
        Properties props = config.properties();

        String connectionName = props != null ? props.getProperty("connection") : null;
        if (connectionName != null) {
            RedisClientConfig clientConfig = RedisConnectionRegistry.get(connectionName);
            if (clientConfig != null) {
                this.jedis = clientConfig.createUnifiedJedis();
                LOG.info("Redis store started using connection '" + connectionName + "'");
                validateConnection();
                return;
            }
            LOG.warning("Redis connection '" + connectionName + "' not found in registry, falling back to properties");
        }

        String clusterNodes = props != null ? props.getProperty("cluster-nodes", "127.0.0.1:6379") : "127.0.0.1:6379";
        String password = props != null ? props.getProperty("password") : null;

        String[] parts = clusterNodes.split(",");
        RedisClientConfig fallbackConfig = new RedisClientConfig()
                .clusterNodes(parseAllNodes(parts));
        if (password != null && !password.isEmpty()) {
            fallbackConfig.password(password);
        }
        this.jedis = fallbackConfig.createUnifiedJedis();
        LOG.info("Redis store started using direct connection to " + clusterNodes);
        validateConnection();
    }

    private void validateConnection() {
        try {
            String pong = jedis.ping();
            LOG.info("Redis connection validated: " + pong);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Redis connection validation failed", e);
            throw new PersistenceException("Redis connection validation failed", e);
        }
    }

    private <T> T executeWithReconnect(String operation, java.util.function.Supplier<T> supplier) {
        int attempts = 0;
        long backoffMs = RECONNECT_RETRY_INTERVAL_MS;
        
        while (attempts < MAX_RECONNECT_ATTEMPTS) {
            try {
                T result = supplier.get();
                if (lastConnectionLostLogMillis != 0) {
                    LOG.info("Redis connection restored during " + operation);
                    lastConnectionLostLogMillis = 0;
                }
                return result;
            } catch (JedisConnectionException e) {
                attempts++;
                long now = System.currentTimeMillis();
                long lastLog = lastConnectionLostLogMillis;
                if (lastLog == 0 || now - lastLog >= CONNECTION_LOG_INTERVAL_MS) {
                    LOG.log(Level.SEVERE, 
                        String.format("Redis connection lost during %s (attempt %d/%d), retrying in %dms", 
                            operation, attempts, MAX_RECONNECT_ATTEMPTS, backoffMs), e);
                    lastConnectionLostLogMillis = now;
                }
                
                if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                    throw new PersistenceException(
                        "Failed to reconnect to Redis after " + MAX_RECONNECT_ATTEMPTS + " attempts during " + operation, e);
                }
                
                try {
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_RECONNECT_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PersistenceException("Interrupted while reconnecting to Redis during " + operation, ie);
                }
            }
        }
        throw new PersistenceException("Unexpected exit from reconnect loop during " + operation);
    }

    @Override
    public CompletionStage<Void> stop() {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error closing Redis connection", e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Set<Characteristic> characteristics() {
        return EnumSet.of(Characteristic.BULK_READ, Characteristic.EXPIRATION, Characteristic.SHAREABLE);
    }

    @Override
    public CompletionStage<MarshallableEntry<K, V>> load(int segment, Object key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithReconnect("load", () -> {
                    String redisKey = toRedisKey(key);
                    byte[] data = jedis.get(redisKey.getBytes());
                    if (data == null) {
                        return null;
                    }
                    return bytesToEntry(data);
                });
            } catch (Exception e) {
                throw new PersistenceException("Failed to load key from Redis", e);
            }
        }, nonBlockingExecutor);
    }

    @Override
    public CompletionStage<Void> write(int segment, MarshallableEntry<? extends K, ? extends V> entry) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithReconnect("write", () -> {
                    String redisKey = toRedisKey(entry.getKey());
                    byte[] data = entryToBytes(entry);

                    long expiryTime = entry.expiryTime();
                    if (expiryTime > 0 && expiryTime < Long.MAX_VALUE) {
                        long ttlMs = expiryTime - System.currentTimeMillis();
                        if (ttlMs > 0) {
                            jedis.psetex(redisKey.getBytes(), ttlMs, data);
                        }
                    } else {
                        jedis.set(redisKey.getBytes(), data);
                    }
                    return (Void) null;
                });
            } catch (Exception e) {
                throw new PersistenceException("Failed to write entry to Redis", e);
            }
        }, nonBlockingExecutor);
    }

    @Override
    public CompletionStage<Boolean> delete(int segment, Object key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithReconnect("delete", () -> {
                    String redisKey = toRedisKey(key);
                    long removed = jedis.del(redisKey);
                    return removed > 0;
                });
            } catch (Exception e) {
                throw new PersistenceException("Failed to delete key from Redis", e);
            }
        }, nonBlockingExecutor);
    }

    @Override
    public CompletionStage<Void> clear() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithReconnect("clear", () -> {
                    ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
                    String cursor = ScanParams.SCAN_POINTER_START;
                    do {
                        ScanResult<String> result = jedis.scan(cursor, params);
                        for (String key : result.getResult()) {
                            jedis.del(key);
                        }
                        cursor = result.getCursor();
                    } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
                    return (Void) null;
                });
            } catch (Exception e) {
                throw new PersistenceException("Failed to clear Redis store", e);
            }
        }, nonBlockingExecutor);
    }

    @Override
    public CompletionStage<Long> size(IntSet segments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithReconnect("size", () -> {
                    long count = 0;
                    ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
                    String cursor = ScanParams.SCAN_POINTER_START;
                    do {
                        ScanResult<String> result = jedis.scan(cursor, params);
                        count += result.getResult().size();
                        cursor = result.getCursor();
                    } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
                    return count;
                });
            } catch (Exception e) {
                throw new PersistenceException("Failed to get Redis store size", e);
            }
        }, nonBlockingExecutor);
    }

    @Override
    public CompletionStage<Long> approximateSize(IntSet segments) {
        return size(segments);
    }

    @Override
    public Publisher<MarshallableEntry<K, V>> publishEntries(IntSet segments, Predicate<? super K> filter, boolean includeValues) {
        return subscriber -> nonBlockingExecutor.execute(() -> {
            List<MarshallableEntry<K, V>> entries;
            try {
                entries = executeWithReconnect("publishEntries", () -> scanAllEntries(filter));
            } catch (Exception e) {
                subscriber.onSubscribe(EMPTY_SUBSCRIPTION);
                subscriber.onError(new PersistenceException("Failed to scan entries from Redis", e));
                return;
            }
            subscriber.onSubscribe(new ListSubscription<>(entries, subscriber));
        });
    }

    private List<MarshallableEntry<K, V>> scanAllEntries(Predicate<? super K> filter) {
        List<MarshallableEntry<K, V>> entries = new ArrayList<>();
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                byte[] data = jedis.get(key.getBytes());
                if (data != null) {
                    MarshallableEntry<K, V> entry = bytesToEntry(data);
                    if (entry != null && (filter == null || filter.test(entry.getKey()))) {
                        entries.add(entry);
                    }
                }
            }
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return entries;
    }

    private static final Subscription EMPTY_SUBSCRIPTION = new Subscription() {
        @Override public void request(long n) {}
        @Override public void cancel() {}
    };

    private static final class ListSubscription<T> implements Subscription {
        private final List<T> items;
        private final Subscriber<? super T> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicInteger wip = new AtomicInteger();
        private int index;
        private volatile boolean cancelled;

        ListSubscription(List<T> items, Subscriber<? super T> subscriber) {
            this.items = items;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0 || cancelled) return;
            demand.getAndAdd(n);
            drain();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void drain() {
            if (wip.getAndIncrement() != 0) return;
            do {
                long d = demand.get();
                long emitted = 0;
                while (emitted < d && index < items.size() && !cancelled) {
                    subscriber.onNext(items.get(index++));
                    emitted++;
                }
                if (index >= items.size() && !cancelled) {
                    subscriber.onComplete();
                    return;
                }
                if (cancelled) return;
                demand.addAndGet(-emitted);
            } while (wip.decrementAndGet() != 0);
        }
    }

    private String toRedisKey(Object key) {
        try {
            byte[] keyBytes = marshaller.objectToByteBuffer(key);
            return keyPrefix + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
        } catch (Exception e) {
            throw new PersistenceException("Failed to serialize key", e);
        }
    }

    private byte[] entryToBytes(MarshallableEntry<? extends K, ? extends V> entry) {
        ByteBuffer keyBytes = entry.getKeyBytes();
        ByteBuffer valueBytes = entry.getValueBytes();
        ByteBuffer metadataBytes = entry.getMetadataBytes();
        ByteBuffer internalMetadataBytes = entry.getInternalMetadataBytes();

        int keyLen = keyBytes != null ? keyBytes.getLength() : 0;
        int valueLen = valueBytes != null ? valueBytes.getLength() : 0;
        int metaLen = metadataBytes != null ? metadataBytes.getLength() : 0;
        int internalMetaLen = internalMetadataBytes != null ? internalMetadataBytes.getLength() : 0;

        int totalLen = 4 + keyLen + 4 + valueLen + 4 + metaLen + 4 + internalMetaLen + 8 + 8;
        byte[] data = new byte[totalLen];
        int offset = 0;

        offset = writeInt(data, offset, keyLen);
        if (keyLen > 0) { System.arraycopy(keyBytes.getBuf(), keyBytes.getOffset(), data, offset, keyLen); offset += keyLen; }

        offset = writeInt(data, offset, valueLen);
        if (valueLen > 0) { System.arraycopy(valueBytes.getBuf(), valueBytes.getOffset(), data, offset, valueLen); offset += valueLen; }

        offset = writeInt(data, offset, metaLen);
        if (metaLen > 0) { System.arraycopy(metadataBytes.getBuf(), metadataBytes.getOffset(), data, offset, metaLen); offset += metaLen; }

        offset = writeInt(data, offset, internalMetaLen);
        if (internalMetaLen > 0) { System.arraycopy(internalMetadataBytes.getBuf(), internalMetadataBytes.getOffset(), data, offset, internalMetaLen); offset += internalMetaLen; }

        offset = writeLong(data, offset, entry.created());
        writeLong(data, offset, entry.lastUsed());

        return data;
    }

    private MarshallableEntry<K, V> bytesToEntry(byte[] data) {
        try {
            int offset = 0;
            int keyLen = readInt(data, offset); offset += 4;
            ByteBuffer keyBytes = keyLen > 0 ? ByteBufferImpl.create(data, offset, keyLen) : null; offset += keyLen;

            int valueLen = readInt(data, offset); offset += 4;
            ByteBuffer valueBytes = valueLen > 0 ? ByteBufferImpl.create(data, offset, valueLen) : null; offset += valueLen;

            int metaLen = readInt(data, offset); offset += 4;
            ByteBuffer metadataBytes = metaLen > 0 ? ByteBufferImpl.create(data, offset, metaLen) : null; offset += metaLen;

            int internalMetaLen = readInt(data, offset); offset += 4;
            ByteBuffer internalMetadataBytes = internalMetaLen > 0 ? ByteBufferImpl.create(data, offset, internalMetaLen) : null; offset += internalMetaLen;

            long created = readLong(data, offset); offset += 8;
            long lastUsed = readLong(data, offset);

            return entryFactory.create(keyBytes, valueBytes, metadataBytes, internalMetadataBytes, created, lastUsed);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize entry from Redis", e);
            return null;
        }
    }

    private static int writeInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
        return offset + 4;
    }

    private static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24) |
               ((buf[offset + 1] & 0xFF) << 16) |
               ((buf[offset + 2] & 0xFF) << 8) |
               (buf[offset + 3] & 0xFF);
    }

    private static int writeLong(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >>> 56);
        buf[offset + 1] = (byte) (value >>> 48);
        buf[offset + 2] = (byte) (value >>> 40);
        buf[offset + 3] = (byte) (value >>> 32);
        buf[offset + 4] = (byte) (value >>> 24);
        buf[offset + 5] = (byte) (value >>> 16);
        buf[offset + 6] = (byte) (value >>> 8);
        buf[offset + 7] = (byte) value;
        return offset + 8;
    }

    private static long readLong(byte[] buf, int offset) {
        return ((long)(buf[offset] & 0xFF) << 56) |
               ((long)(buf[offset + 1] & 0xFF) << 48) |
               ((long)(buf[offset + 2] & 0xFF) << 40) |
               ((long)(buf[offset + 3] & 0xFF) << 32) |
               ((long)(buf[offset + 4] & 0xFF) << 24) |
               ((long)(buf[offset + 5] & 0xFF) << 16) |
               ((long)(buf[offset + 6] & 0xFF) << 8) |
               ((long)(buf[offset + 7] & 0xFF));
    }

    private static HostAndPort parseHostAndPort(String s) {
        int lastColon = s.lastIndexOf(':');
        if (lastColon > 0) {
            return new HostAndPort(s.substring(0, lastColon), Integer.parseInt(s.substring(lastColon + 1)));
        }
        return new HostAndPort(s, 6379);
    }

    private static Set<HostAndPort> parseAllNodes(String[] parts) {
        Set<HostAndPort> nodes = new HashSet<>();
        for (String part : parts) {
            nodes.add(parseHostAndPort(part.trim()));
        }
        return nodes;
    }
}
