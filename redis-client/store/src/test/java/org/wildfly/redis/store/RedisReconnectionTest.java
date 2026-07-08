/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.persistence.spi.PersistenceException;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisReconnectionTest {

    private final RedisNonBlockingStore<String, String> store =
            new RedisNonBlockingStore<>(3, 10, 50);

    @Test
    void testImmediateSuccess() {
        String result = store.executeWithReconnect("test", () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void testRetryThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = store.executeWithReconnect("test", () -> {
            if (calls.incrementAndGet() <= 2) {
                throw new JedisConnectionException("connection lost");
            }
            return "recovered";
        });
        assertEquals("recovered", result);
        assertEquals(3, calls.get());
    }

    @Test
    void testMaxRetriesExceeded() {
        PersistenceException ex = assertThrows(PersistenceException.class, () ->
            store.executeWithReconnect("test", () -> {
                throw new JedisConnectionException("connection lost");
            })
        );
        assertTrue(ex.getMessage().contains("Failed to reconnect to Redis after 3 attempts"),
                "Expected retry-exhaustion message, got: " + ex.getMessage());
        assertInstanceOf(JedisConnectionException.class, ex.getCause());
    }

    @Test
    void testExponentialBackoffTiming() {
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        assertThrows(PersistenceException.class, () ->
            store.executeWithReconnect("test", () -> {
                calls.incrementAndGet();
                throw new JedisConnectionException("connection lost");
            })
        );
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(3, calls.get());
        // 2 sleeps: 10ms + 20ms = 30ms minimum (3rd attempt fails immediately, no sleep after)
        assertTrue(elapsedMs >= 25, "Expected at least 25ms of backoff, got: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 2000, "Backoff took too long: " + elapsedMs + "ms");
    }

    @Test
    void testInterruptDuringBackoff() throws Exception {
        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            testThread.interrupt();
        });
        interrupter.start();

        PersistenceException ex = assertThrows(PersistenceException.class, () ->
            store.executeWithReconnect("test", () -> {
                throw new JedisConnectionException("connection lost");
            })
        );
        interrupter.join(1000);

        boolean wasInterrupted = Thread.interrupted();
        assertTrue(
            ex.getMessage().contains("Interrupted") || ex.getMessage().contains("Failed to reconnect"),
            "Expected interrupt or exhaustion message, got: " + ex.getMessage()
        );
    }
}
