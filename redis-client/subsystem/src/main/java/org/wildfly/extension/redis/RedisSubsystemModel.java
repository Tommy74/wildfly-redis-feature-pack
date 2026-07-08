/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.SubsystemModel;

enum RedisSubsystemModel implements SubsystemModel {
    VERSION_1_0_0(1, 0, 0),
    VERSION_1_1_0(1, 1, 0),
    ;

    static final RedisSubsystemModel CURRENT = VERSION_1_1_0;

    private final ModelVersion version;

    RedisSubsystemModel(int major, int minor, int micro) {
        this.version = ModelVersion.create(major, minor, micro);
    }

    @Override
    public ModelVersion getVersion() {
        return this.version;
    }
}
