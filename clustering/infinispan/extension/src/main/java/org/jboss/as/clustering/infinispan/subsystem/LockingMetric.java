/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.clustering.infinispan.subsystem;

import java.util.function.UnaryOperator;

import org.infinispan.util.concurrent.locks.impl.DefaultLockManager;
import org.jboss.as.clustering.controller.Metric;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Enumeration of locking management metrics for a cache.
 *
 * @author Paul Ferraro
 */
public enum LockingMetric implements Metric<DefaultLockManager>, UnaryOperator<SimpleAttributeDefinitionBuilder> {

    CURRENT_CONCURRENCY_LEVEL("current-concurrency-level", ModelType.INT, AttributeAccess.Flag.GAUGE_METRIC) {
        @Override
        public ModelNode execute(DefaultLockManager manager) {
            return new ModelNode(manager.getConcurrencyLevel());
        }

        @Override
        public SimpleAttributeDefinitionBuilder apply(SimpleAttributeDefinitionBuilder builder) {
            return builder.setDeprecated(InfinispanModel.VERSION_17_0_0.getVersion());
        }
    },
    NUMBER_OF_LOCKS_AVAILABLE("number-of-locks-available", ModelType.INT, AttributeAccess.Flag.GAUGE_METRIC) {
        @Override
        public ModelNode execute(DefaultLockManager manager) {
            return new ModelNode(manager.getNumberOfLocksAvailable());
        }
    },
    NUMBER_OF_LOCKS_HELD("number-of-locks-held", ModelType.INT, AttributeAccess.Flag.GAUGE_METRIC) {
        @Override
        public ModelNode execute(DefaultLockManager manager) {
            return new ModelNode(manager.getNumberOfLocksHeld());
        }
    },
    ;
    private final AttributeDefinition definition;

    LockingMetric(String name, ModelType type, AttributeAccess.Flag metricType) {
        this.definition = this.apply(new SimpleAttributeDefinitionBuilder(name, type)
                .setFlags(metricType)
                .setStorageRuntime()
                ).build();
    }

    @Override
    public AttributeDefinition getDefinition() {
        return this.definition;
    }

    @Override
    public SimpleAttributeDefinitionBuilder apply(SimpleAttributeDefinitionBuilder builder) {
        return builder;
    }
}
