/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import org.infinispan.configuration.cache.CustomStoreConfigurationBuilder;
import org.infinispan.configuration.cache.PersistenceConfigurationBuilder;

/**
 * Configuration builder for the Redis-backed Infinispan cache store.
 * <p>
 * Extends {@link CustomStoreConfigurationBuilder} so Infinispan knows to instantiate
 * {@link RedisNonBlockingStore} at runtime. This class is loaded reflectively by WildFly's
 * {@code CustomStoreResourceDefinitionRegistrar} when the user configures:
 * <pre>{@code
 * <invalidation-cache name="mycache" modules="org.wildfly.redis.store">
 *     <store class="org.wildfly.redis.store.RedisStoreConfigurationBuilder">
 *         <property name="connection">default</property>
 *         <property name="cluster-nodes">${jboss.redis-client.redis-connection.cluster-nodes:127.0.0.1:6379}</property>
 *     </store>
 * </invalidation-cache>
 * }</pre>
 * <p>
 * Configuration is passed via Infinispan properties (checked in this order):
 * <ol>
 *   <li>{@code cluster-nodes} — comma-separated host:port pairs (takes precedence when present).
 *       Use the same system property expression as the redis-client subsystem's
 *       {@code cluster-nodes} attribute to keep both in sync.</li>
 *   <li>{@code connection} — name of a redis-client subsystem connection (fallback when
 *       cluster-nodes is not set)</li>
 *   <li>If neither is set, connects to {@code 127.0.0.1:6379}</li>
 * </ol>
 * <p>
 * Additional properties:
 * <ul>
 *   <li>{@code password} — Redis password (optional, used with cluster-nodes)</li>
 * </ul>
 */
public class RedisStoreConfigurationBuilder extends CustomStoreConfigurationBuilder {

    public RedisStoreConfigurationBuilder(PersistenceConfigurationBuilder builder) {
        super(builder);
        customStoreClass(RedisNonBlockingStore.class);
    }
}
