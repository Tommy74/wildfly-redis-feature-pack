/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Set;

import org.jboss.as.controller.OperationFailedException;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;

/**
 * Tests for input validation in RedisConnectionServiceConfigurator.
 */
public class RedisConnectionServiceConfiguratorTest {

    private Set<HostAndPort> parseClusterNodes(String clusterNodesValue) throws Exception {
        Method method = RedisConnectionServiceConfigurator.class.getDeclaredMethod("parseClusterNodes", String.class);
        method.setAccessible(true);
        try {
            return (Set<HostAndPort>) method.invoke(null, clusterNodesValue);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof OperationFailedException) {
                throw (OperationFailedException) e.getCause();
            }
            throw e;
        }
    }

    @Test
    void testValidSingleNode() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("localhost:6379");
        assertEquals(1, nodes.size());
        HostAndPort node = nodes.iterator().next();
        assertEquals("localhost", node.getHost());
        assertEquals(6379, node.getPort());
    }

    @Test
    void testValidMultipleNodes() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("host1:6379,host2:6380,host3:6381");
        assertEquals(3, nodes.size());
    }

    @Test
    void testValidIPv4() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("192.168.1.100:6379");
        assertEquals(1, nodes.size());
        HostAndPort node = nodes.iterator().next();
        assertEquals("192.168.1.100", node.getHost());
        assertEquals(6379, node.getPort());
    }

    @Test
    void testValidIPv6() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("[::1]:6379");
        assertEquals(1, nodes.size());
        HostAndPort node = nodes.iterator().next();
        assertEquals("::1", node.getHost());
        assertEquals(6379, node.getPort());
    }

    @Test
    void testValidIPv6FullAddress() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:6379");
        assertEquals(1, nodes.size());
        HostAndPort node = nodes.iterator().next();
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", node.getHost());
        assertEquals(6379, node.getPort());
    }

    @Test
    void testValidMixedIPv4AndIPv6() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("192.168.1.1:6379,[::1]:6380");
        assertEquals(2, nodes.size());
    }

    @Test
    void testValidWithWhitespace() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes(" host1:6379 , host2:6380 ");
        assertEquals(2, nodes.size());
    }

    @Test
    void testValidPortRange() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("localhost:1,localhost:65535");
        assertEquals(2, nodes.size());
    }

    @Test
    void testNullInput() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes(null);
        assertTrue(nodes.isEmpty());
    }

    @Test
    void testEmptyInput() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("");
        assertTrue(nodes.isEmpty());
    }

    @Test
    void testBlankInput() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("   ");
        assertTrue(nodes.isEmpty());
    }

    @Test
    void testEmptyEntriesIgnored() throws Exception {
        Set<HostAndPort> nodes = parseClusterNodes("host1:6379,,host2:6380");
        assertEquals(2, nodes.size());
    }

    @Test
    void testMissingPort() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost");
        });
        assertTrue(exception.getMessage().contains("Missing port"));
    }

    @Test
    void testMissingPortWithColon() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost:");
        });
        assertTrue(exception.getMessage().contains("Invalid port number"));
    }

    @Test
    void testInvalidPortNonNumeric() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost:abc");
        });
        assertTrue(exception.getMessage().contains("Invalid port number"));
    }

    @Test
    void testInvalidPortNegative() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost:-1");
        });
        assertTrue(exception.getMessage().contains("Port out of range"));
    }

    @Test
    void testInvalidPortZero() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost:0");
        });
        assertTrue(exception.getMessage().contains("Port out of range"));
    }

    @Test
    void testInvalidPortTooLarge() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("localhost:65536");
        });
        assertTrue(exception.getMessage().contains("Port out of range"));
    }

    @Test
    void testEmptyHost() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes(":6379");
        });
        assertTrue(exception.getMessage().contains("Empty host") || 
                   exception.getMessage().contains("Missing port"));
    }

    @Test
    void testInvalidIPv6MissingBracket() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("[::1:6379");
        });
        assertTrue(exception.getMessage().contains("Invalid IPv6 address format"));
    }

    @Test
    void testInvalidIPv6MissingColon() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("[::1]6379");
        });
        assertTrue(exception.getMessage().contains("Invalid IPv6 address format"));
    }

    @Test
    void testInvalidIPv6EmptyHost() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("[]:6379");
        });
        assertTrue(exception.getMessage().contains("Empty host"));
    }

    @Test
    void testPartiallyValidInput() {
        OperationFailedException exception = assertThrows(OperationFailedException.class, () -> {
            parseClusterNodes("valid:6379,invalid");
        });
        assertTrue(exception.getMessage().contains("Missing port"));
    }

    @Test
    void testMultipleColonsWithoutBrackets() {
        // IPv6 without brackets: "::1:6379" is actually valid - host="::1", port=6379
        // The lastIndexOf(':') correctly finds the port separator
        Set<HostAndPort> nodes = assertDoesNotThrow(() -> parseClusterNodes("::1:6379"));
        assertEquals(1, nodes.size());
        HostAndPort node = nodes.iterator().next();
        assertEquals("::1", node.getHost());
        assertEquals(6379, node.getPort());
    }
}
