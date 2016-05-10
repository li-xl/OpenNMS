/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2010-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

/**
 * 
 */
package org.opennms.netmgt.provision.service;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.opennms.core.tasks.BatchTask;
import org.opennms.core.tasks.RunInBatch;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.provision.NodePolicy;
import org.opennms.netmgt.provision.service.snmp.SystemGroup;
import org.opennms.netmgt.snmp.SnmpAgentConfig;
import org.opennms.netmgt.snmp.SnmpResult;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

final class NodeInfoScan implements RunInBatch {
    private static final Logger LOG = LoggerFactory.getLogger(NodeInfoScan.class);

    private final SnmpAgentConfigFactory m_agentConfigFactory;
    private final InetAddress m_agentAddress;
    private final String m_foreignSource;
    private OnmsNode m_node;
    private Integer m_nodeId;
    private boolean restoreCategories = false;
    private final ProvisionService m_provisionService;
    private final ScanProgress m_scanProgress;
    private final LocationAwareSnmpClient m_locationAwareSnmpClient;

    NodeInfoScan(OnmsNode node, InetAddress agentAddress, String foreignSource, ScanProgress scanProgress,
            SnmpAgentConfigFactory agentConfigFactory, ProvisionService provisionService, Integer nodeId,
            final LocationAwareSnmpClient locationAwareSnmpClient) {
        m_node = node;
        m_agentAddress = agentAddress;
        m_foreignSource = foreignSource;
        m_scanProgress = scanProgress;
        m_agentConfigFactory = agentConfigFactory;
        m_provisionService = provisionService;
        m_nodeId = nodeId;
        m_locationAwareSnmpClient = Objects.requireNonNull(locationAwareSnmpClient);
    }

    /** {@inheritDoc} */
    @Override
    public void run(BatchTask phase) {
        
        phase.getBuilder().addSequence(
                new RunInBatch() {
                    @Override
                    public void run(BatchTask batch) {
                        collectNodeInfo();
                    }
                },
                new RunInBatch() {
                    @Override
                    public void run(BatchTask phase) {
                        doPersistNodeInfo();
                    }
                });
    }

    private InetAddress getAgentAddress() {
        return m_agentAddress;
    }

    private SnmpAgentConfig getAgentConfig(InetAddress primaryAddress) {
        return getAgentConfigFactory().getAgentConfig(primaryAddress);
    }

    private SnmpAgentConfigFactory getAgentConfigFactory() {
        return m_agentConfigFactory;
    }

    private String getForeignSource() {
        return m_foreignSource;
    }

    private ProvisionService getProvisionService() {
        return m_provisionService;
    }

    private void abort(String reason) {
        m_scanProgress.abort(reason);
    }

    private OnmsNode getNode() {
        return m_node;
    }
    
    private Integer getNodeId() {
        return m_nodeId;
    }

    private void setNode(OnmsNode node) {
        m_node = node;
    }

    private void collectNodeInfo() {
        Assert.notNull(getAgentConfigFactory(), "agentConfigFactory was not injected");
        InetAddress primaryAddress = getAgentAddress();
        SnmpAgentConfig agentConfig = getAgentConfig(primaryAddress);

        SystemGroup systemGroup = new SystemGroup(primaryAddress);

        final CompletableFuture<List<SnmpResult>> future = m_locationAwareSnmpClient.walk(agentConfig, systemGroup.getBaseOids())
                .atLocation(null)
                .withDescription("systemGroup")
                .execute();

        try {
            systemGroup.processResults(future.get());
            systemGroup.updateSnmpDataForNode(getNode());

            List<NodePolicy> nodePolicies = getProvisionService().getNodePoliciesForForeignSource(getEffectiveForeignSource());

            OnmsNode node = null;
            if (isAborted()) {
                if (getNodeId() != null && nodePolicies.size() > 0) {
                    restoreCategories = true;
                    node = m_provisionService.getDbNodeInitCat(getNodeId());
                    LOG.debug("collectNodeInfo: checking {} node policies for restoration of categories", nodePolicies.size());
                }
            } else {
                node = getNode();
            }
            for(NodePolicy policy : nodePolicies) {
                if (node != null) {
                    LOG.info("Applying NodePolicy {}({}) to {}", policy.getClass(), policy, node.getLabel());
                    node = policy.apply(node);
                }
            }
        
            if (node == null) {
                restoreCategories = false;
                if (!isAborted()) {
                    String reason = "Aborted scan of node due to configured policy";
                    abort(reason);
                }
            } else {
                setNode(node);
            }
        } catch (ExecutionException e) {
            LOG.info("SNMP Walk failed while scanning the system table on {}.", agentConfig, e);
            abort("Aborting node scan : Agent failed or timed out while scanning the system table : " + e.getMessage());
        } catch (InterruptedException e) {
            abort("Aborting node scan : Scan thread failed while waiting for the system table");
            Thread.currentThread().interrupt();
        }
    }

    private String getEffectiveForeignSource() {
        return getForeignSource()  == null ? "default" : getForeignSource();
    }

    private void doPersistNodeInfo() {
        if (restoreCategories) {
            LOG.debug("doPersistNodeInfo: Restoring {} categories to DB", getNode().getCategories().size());
        }
        if (!isAborted() || restoreCategories) {
            getProvisionService().updateNodeAttributes(getNode());
        }
    }

    private boolean isAborted() {
        return m_scanProgress.isAborted();
    }
}
