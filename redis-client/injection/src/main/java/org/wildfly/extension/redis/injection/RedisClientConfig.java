/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLSocketFactory;

import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

public class RedisClientConfig {

    private String password;
    private boolean ssl = false;
    private SSLSocketFactory sslSocketFactory;
    private int connectionTimeout = 2000;
    private int maxPoolSize = 8;
    private int minIdle = 0;
    private Set<HostAndPort> clusterNodes = new HashSet<>();

    public RedisClientConfig password(String password) {
        this.password = password;
        return this;
    }

    public RedisClientConfig ssl(boolean ssl) {
        this.ssl = ssl;
        return this;
    }

    public RedisClientConfig sslSocketFactory(SSLSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
        return this;
    }

    public RedisClientConfig connectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        return this;
    }

    public RedisClientConfig maxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
        return this;
    }

    public RedisClientConfig minIdle(int minIdle) {
        this.minIdle = minIdle;
        return this;
    }

    public RedisClientConfig clusterNodes(Set<HostAndPort> clusterNodes) {
        this.clusterNodes = clusterNodes;
        return this;
    }

    public boolean isClusterMode() {
        return clusterNodes != null && clusterNodes.size() > 1;
    }

    public UnifiedJedis createUnifiedJedis() {
        if (isClusterMode()) {
            return createJedisCluster();
        }
        return createJedisPooled();
    }

    private JedisPooled createJedisPooled() {
        if (clusterNodes == null || clusterNodes.isEmpty()) {
            throw new IllegalStateException(
                "Cannot create Redis connection: no cluster nodes configured. " +
                "Please configure 'cluster-nodes' or 'outbound-socket-bindings'.");
        }
        
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(maxPoolSize);
        poolConfig.setMinIdle(minIdle);

        HostAndPort hostAndPort = clusterNodes.iterator().next();
        return new JedisPooled(poolConfig, hostAndPort, buildClientConfig());
    }

    private JedisCluster createJedisCluster() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(maxPoolSize);
        poolConfig.setMinIdle(minIdle);

        return new JedisCluster(clusterNodes, buildClientConfig(), maxPoolSize, poolConfig);
    }

    private DefaultJedisClientConfig buildClientConfig() {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(connectionTimeout)
                .ssl(ssl);

        if (password != null && !password.isEmpty()) {
            builder.password(password);
        }
        if (sslSocketFactory != null) {
            builder.sslSocketFactory(sslSocketFactory);
        }

        return builder.build();
    }
}
