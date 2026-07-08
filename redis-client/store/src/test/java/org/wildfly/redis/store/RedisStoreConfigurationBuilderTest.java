/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.store;

import static org.junit.jupiter.api.Assertions.*;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.CustomStoreConfiguration;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.junit.jupiter.api.Test;

public class RedisStoreConfigurationBuilderTest {

    @Test
    void testCustomStoreClassIsSet() {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.persistence().addStore(RedisStoreConfigurationBuilder.class);
        var config = cb.build();
        var stores = config.persistence().stores();
        assertEquals(1, stores.size());
        StoreConfiguration storeConfig = stores.get(0);
        assertInstanceOf(CustomStoreConfiguration.class, storeConfig);
        assertEquals(RedisNonBlockingStore.class,
                ((CustomStoreConfiguration) storeConfig).customStoreClass());
    }

    @Test
    void testPropertiesCanBeAdded() {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.persistence()
                .addStore(RedisStoreConfigurationBuilder.class)
                .addProperty("connection", "default");
        var config = cb.build();
        var storeConfig = config.persistence().stores().get(0);
        assertEquals("default", storeConfig.properties().getProperty("connection"));
    }
}
