/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;

/**
 * Tests for concurrent access to RedisConnectionRegistry.
 */
public class RedisConnectionRegistryConcurrencyTest {

    @AfterEach
    void cleanup() {
        // Clean up any registered connections
        for (int i = 0; i < 100; i++) {
            RedisConnectionRegistry.unregister("conn-" + i);
        }
    }

    @Test
    void testConcurrentRegisterAndGet() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // Create configs
        RedisClientConfig[] configs = new RedisClientConfig[operationsPerThread];
        for (int i = 0; i < operationsPerThread; i++) {
            configs[i] = new RedisClientConfig()
                    .clusterNodes(Set.of(new HostAndPort("localhost", 6379 + i)));
        }

        // Submit concurrent register/get operations
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        String name = "conn-" + i;
                        
                        // Register
                        RedisConnectionRegistry.register(name, configs[i]);
                        
                        // Get
                        RedisClientConfig retrieved = RedisConnectionRegistry.get(name);
                        assertNotNull(retrieved, "Config should be retrievable after registration");
                        
                        // Verify it's the same config (don't create actual connection)
                        assertSame(configs[i], retrieved, "Retrieved config should be the same instance");
                        
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Start all threads at once
        startLatch.countDown();
        
        // Wait for completion with timeout
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), 
            "All threads should complete within 30 seconds");

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify all operations succeeded
        assertTrue(successCount.get() > 0, "At least some operations should succeed");
        
        // Verify final state - all configs should be registered
        for (int i = 0; i < operationsPerThread; i++) {
            assertNotNull(RedisConnectionRegistry.get("conn-" + i), 
                "Config conn-" + i + " should be registered");
        }
    }

    @Test
    void testConcurrentRegisterAndUnregister() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger registerCount = new AtomicInteger(0);
        AtomicInteger unregisterCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        String name = "conn-" + threadId + "-" + i;
                        RedisClientConfig config = new RedisClientConfig()
                                .clusterNodes(Set.of(new HostAndPort("localhost", 6379)));
                        
                        // Register
                        RedisConnectionRegistry.register(name, config);
                        registerCount.incrementAndGet();
                        
                        // Verify it's there
                        assertNotNull(RedisConnectionRegistry.get(name));
                        
                        // Unregister
                        RedisConnectionRegistry.unregister(name);
                        unregisterCount.incrementAndGet();
                        
                        // Verify it's gone
                        assertNull(RedisConnectionRegistry.get(name));
                    }
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(threadCount * operationsPerThread, registerCount.get());
        assertEquals(threadCount * operationsPerThread, unregisterCount.get());
    }

    @Test
    void testConcurrentGetNonExistent() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < 100; i++) {
                        // Try to get non-existent connection
                        RedisClientConfig config = RedisConnectionRegistry.get("non-existent-" + i);
                        assertNull(config, "Non-existent connection should return null");
                    }
                } catch (Exception e) {
                    fail("Thread failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void testRegisterSameNameConcurrently() throws Exception {
        String connectionName = "shared-conn";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int port = 6379 + t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    // All threads try to register the same connection name
                    RedisClientConfig config = new RedisClientConfig()
                            .clusterNodes(Set.of(new HostAndPort("localhost", port)));
                    RedisConnectionRegistry.register(connectionName, config);
                    
                } catch (Exception e) {
                    fail("Thread failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify one config is registered (last one wins)
        RedisClientConfig finalConfig = RedisConnectionRegistry.get(connectionName);
        assertNotNull(finalConfig, "Connection should be registered");
        
        // Clean up
        RedisConnectionRegistry.unregister(connectionName);
    }

    @Test
    void testStressTest() throws Exception {
        int threadCount = 20;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger totalOperations = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        String name = "stress-" + (threadId % 5) + "-" + (i % 10);
                        
                        if (i % 3 == 0) {
                            // Register
                            RedisClientConfig config = new RedisClientConfig()
                                    .clusterNodes(Set.of(new HostAndPort("localhost", 6379)));
                            RedisConnectionRegistry.register(name, config);
                        } else if (i % 3 == 1) {
                            // Get
                            RedisConnectionRegistry.get(name);
                        } else {
                            // Unregister
                            RedisConnectionRegistry.unregister(name);
                        }
                        
                        totalOperations.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Thread " + threadId + " failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(60, TimeUnit.SECONDS), 
            "Stress test should complete within 60 seconds");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(threadCount * operationsPerThread, totalOperations.get());
    }
}
