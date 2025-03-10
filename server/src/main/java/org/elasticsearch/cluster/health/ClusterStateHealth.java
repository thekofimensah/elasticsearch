/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.cluster.health;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ClusterStateHealth implements Writeable {

    private final int numberOfNodes;
    private final int numberOfDataNodes;
    private final int activeShards;
    private final int relocatingShards;
    private final int activePrimaryShards;
    private final int initializingShards;
    private final int unassignedShards;
    private final int unassignedPrimaryShards;
    private final double activeShardsPercent;
    private final ClusterHealthStatus status;
    private final Map<String, ClusterIndexHealth> indices;

    /**
     * Creates a new <code>ClusterStateHealth</code> instance considering the current cluster state and all indices in the cluster.
     *
     * @param clusterState The current cluster state. Must not be null.
     * @param concreteAllIndices An array of index names to consider. Must not be null but may be empty.
     * @param projectId The project id that should be used to access project-specific data from the cluster state. Must not be null.
     */
    public ClusterStateHealth(final ClusterState clusterState, final String[] concreteAllIndices, final ProjectId projectId) {
        this(
            clusterState.metadata().getProject(projectId),
            clusterState.routingTable(projectId),
            clusterState.nodes(),
            clusterState.blocks(),
            concreteAllIndices
        );
    }

    /**
     * Creates a new <code>ClusterStateHealth</code> instance considering the current cluster state and the provided index names.
     *
     * @param concreteIndices An array of index names to consider. Must not be null but may be empty.
     */
    public ClusterStateHealth(
        final ProjectMetadata project,
        final RoutingTable routingTable,
        final DiscoveryNodes nodes,
        final ClusterBlocks blocks,
        final String[] concreteIndices
    ) {
        numberOfNodes = nodes.getSize();
        numberOfDataNodes = nodes.getDataNodes().size();
        indices = new HashMap<>();
        ClusterHealthStatus computeStatus = ClusterHealthStatus.GREEN;
        int computeActivePrimaryShards = 0;
        int computeActiveShards = 0;
        int computeRelocatingShards = 0;
        int computeInitializingShards = 0;
        int computeUnassignedPrimaryShards = 0;
        int computeUnassignedShards = 0;
        int totalShardCount = 0;

        for (String index : concreteIndices) {
            IndexRoutingTable indexRoutingTable = routingTable.index(index);
            IndexMetadata indexMetadata = project.index(index);
            if (indexRoutingTable == null) {
                continue;
            }

            ClusterIndexHealth indexHealth = new ClusterIndexHealth(indexMetadata, indexRoutingTable);
            indices.put(indexHealth.getIndex(), indexHealth);

            totalShardCount += indexMetadata.getTotalNumberOfShards();
            computeActivePrimaryShards += indexHealth.getActivePrimaryShards();
            computeActiveShards += indexHealth.getActiveShards();
            computeRelocatingShards += indexHealth.getRelocatingShards();
            computeInitializingShards += indexHealth.getInitializingShards();
            computeUnassignedShards += indexHealth.getUnassignedShards();
            computeUnassignedPrimaryShards += indexHealth.getUnassignedPrimaryShards();
            if (indexHealth.getStatus() == ClusterHealthStatus.RED) {
                computeStatus = ClusterHealthStatus.RED;
            } else if (indexHealth.getStatus() == ClusterHealthStatus.YELLOW && computeStatus != ClusterHealthStatus.RED) {
                computeStatus = ClusterHealthStatus.YELLOW;
            }
        }

        if (blocks.hasGlobalBlockWithStatus(RestStatus.SERVICE_UNAVAILABLE)) {
            computeStatus = ClusterHealthStatus.RED;
        }

        this.status = computeStatus;
        this.activePrimaryShards = computeActivePrimaryShards;
        this.activeShards = computeActiveShards;
        this.relocatingShards = computeRelocatingShards;
        this.initializingShards = computeInitializingShards;
        this.unassignedShards = computeUnassignedShards;
        this.unassignedPrimaryShards = computeUnassignedPrimaryShards;

        // shortcut on green
        if (computeStatus.equals(ClusterHealthStatus.GREEN)) {
            this.activeShardsPercent = 100;
        } else {
            this.activeShardsPercent = (((double) this.activeShards) / totalShardCount) * 100;
        }
    }

    public ClusterStateHealth(final StreamInput in) throws IOException {
        activePrimaryShards = in.readVInt();
        activeShards = in.readVInt();
        relocatingShards = in.readVInt();
        initializingShards = in.readVInt();
        unassignedShards = in.readVInt();
        numberOfNodes = in.readVInt();
        numberOfDataNodes = in.readVInt();
        status = ClusterHealthStatus.readFrom(in);
        indices = in.readMapValues(ClusterIndexHealth::new, ClusterIndexHealth::getIndex);
        activeShardsPercent = in.readDouble();
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0)) {
            unassignedPrimaryShards = in.readVInt();
        } else {
            unassignedPrimaryShards = 0;
        }
    }

    /**
     * For ClusterHealthResponse's XContent Parser
     */
    public ClusterStateHealth(
        int activePrimaryShards,
        int activeShards,
        int relocatingShards,
        int initializingShards,
        int unassignedShards,
        int unassignedPrimaryShards,
        int numberOfNodes,
        int numberOfDataNodes,
        double activeShardsPercent,
        ClusterHealthStatus status,
        Map<String, ClusterIndexHealth> indices
    ) {
        this.activePrimaryShards = activePrimaryShards;
        this.activeShards = activeShards;
        this.relocatingShards = relocatingShards;
        this.initializingShards = initializingShards;
        this.unassignedShards = unassignedShards;
        this.unassignedPrimaryShards = unassignedPrimaryShards;
        this.numberOfNodes = numberOfNodes;
        this.numberOfDataNodes = numberOfDataNodes;
        this.activeShardsPercent = activeShardsPercent;
        this.status = status;
        this.indices = indices;
    }

    public int getActiveShards() {
        return activeShards;
    }

    public int getRelocatingShards() {
        return relocatingShards;
    }

    public int getActivePrimaryShards() {
        return activePrimaryShards;
    }

    public int getInitializingShards() {
        return initializingShards;
    }

    public int getUnassignedPrimaryShards() {
        return unassignedPrimaryShards;
    }

    public int getUnassignedShards() {
        return unassignedShards;
    }

    public int getNumberOfNodes() {
        return this.numberOfNodes;
    }

    public int getNumberOfDataNodes() {
        return this.numberOfDataNodes;
    }

    public ClusterHealthStatus getStatus() {
        return status;
    }

    public Map<String, ClusterIndexHealth> getIndices() {
        return Collections.unmodifiableMap(indices);
    }

    public double getActiveShardsPercent() {
        return activeShardsPercent;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeVInt(activePrimaryShards);
        out.writeVInt(activeShards);
        out.writeVInt(relocatingShards);
        out.writeVInt(initializingShards);
        out.writeVInt(unassignedShards);
        out.writeVInt(numberOfNodes);
        out.writeVInt(numberOfDataNodes);
        out.writeByte(status.value());
        out.writeMapValues(indices);
        out.writeDouble(activeShardsPercent);
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_16_0)) {
            out.writeVInt(unassignedPrimaryShards);
        }
    }

    @Override
    public String toString() {
        return "ClusterStateHealth{"
            + "numberOfNodes="
            + numberOfNodes
            + ", numberOfDataNodes="
            + numberOfDataNodes
            + ", activeShards="
            + activeShards
            + ", relocatingShards="
            + relocatingShards
            + ", activePrimaryShards="
            + activePrimaryShards
            + ", initializingShards="
            + initializingShards
            + ", unassignedShards="
            + unassignedShards
            + ", unassignedPrimaryShards="
            + unassignedPrimaryShards
            + ", activeShardsPercent="
            + activeShardsPercent
            + ", status="
            + status
            + ", indices.size="
            + (indices == null ? "null" : indices.size())
            + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterStateHealth that = (ClusterStateHealth) o;
        return numberOfNodes == that.numberOfNodes
            && numberOfDataNodes == that.numberOfDataNodes
            && activeShards == that.activeShards
            && relocatingShards == that.relocatingShards
            && activePrimaryShards == that.activePrimaryShards
            && initializingShards == that.initializingShards
            && unassignedShards == that.unassignedShards
            && unassignedPrimaryShards == that.unassignedPrimaryShards
            && Double.compare(that.activeShardsPercent, activeShardsPercent) == 0
            && status == that.status
            && Objects.equals(indices, that.indices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            numberOfNodes,
            numberOfDataNodes,
            activeShards,
            relocatingShards,
            activePrimaryShards,
            initializingShards,
            unassignedShards,
            unassignedPrimaryShards,
            activeShardsPercent,
            status,
            indices
        );
    }
}
