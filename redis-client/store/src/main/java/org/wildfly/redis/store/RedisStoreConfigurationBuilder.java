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
 *     </store>
 * </invalidation-cache>
 * }</pre>
 * <p>
 * Configuration is passed via Infinispan properties. Supported properties:
 * <ul>
 *   <li>{@code connection} — name of a redis-client subsystem connection (preferred)</li>
 *   <li>{@code cluster-nodes} — comma-separated host:port pairs (fallback, default: 127.0.0.1:6379)</li>
 *   <li>{@code password} — Redis password (optional)</li>
 * </ul>
 */
public class RedisStoreConfigurationBuilder extends CustomStoreConfigurationBuilder {

    public RedisStoreConfigurationBuilder(PersistenceConfigurationBuilder builder) {
        super(builder);
        customStoreClass(RedisNonBlockingStore.class);
    }
}
