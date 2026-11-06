/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.kubevirt;

import hudson.ExtensionList;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NoDelayProvisionerStrategy} and its inner
 * {@link NoDelayProvisionerStrategy.FastProvisioning} queue listener.
 */
@WithJenkins
public class NoDelayProvisionerStrategyTest {

    private JenkinsRule jenkins;
    private boolean savedDisableCloudRotation;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        savedDisableCloudRotation = NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION;
        NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION = false;
        NoDelayProvisionerStrategy.resetCloudRotationForTesting();
        OrderTrackingKubeVirtCloud.PROVISION_ORDER.clear();
    }

    @AfterEach
    void tearDown() {
        NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION = savedDisableCloudRotation;
        NoDelayProvisionerStrategy.resetCloudRotationForTesting();
    }

    // ==================== Extension Registration ====================

    /**
     * Verifies that NoDelayProvisionerStrategy is discovered and registered
     * as a NodeProvisioner.Strategy extension.
     */
    @Test
    void strategyIsRegisteredAsExtension() {
        ExtensionList<NodeProvisioner.Strategy> strategies =
                jenkins.jenkins.getExtensionList(NodeProvisioner.Strategy.class);
        assertTrue(
                strategies.stream().anyMatch(s -> s instanceof NoDelayProvisionerStrategy),
                "NoDelayProvisionerStrategy should be registered as a Strategy extension");
    }

    /**
     * Verifies that FastProvisioning is discovered and registered
     * as a QueueListener extension.
     */
    @Test
    void fastProvisioningIsRegisteredAsExtension() {
        ExtensionList<QueueListener> listeners =
                jenkins.jenkins.getExtensionList(QueueListener.class);
        assertTrue(
                listeners.stream().anyMatch(l -> l instanceof NoDelayProvisionerStrategy.FastProvisioning),
                "FastProvisioning should be registered as a QueueListener extension");
    }

    // ==================== Strategy: No KubeVirt Clouds ====================

    /**
     * When no KubeVirt clouds are configured, the strategy should defer to
     * remaining strategies since it has nothing to provision with.
     */
    @Test
    void noClouds_consultsRemainingStrategies() {
        jenkins.jenkins.clouds.clear();

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 5,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES, decision,
                "Should defer to other strategies when no KubeVirt clouds are configured");
    }

    // ==================== Strategy: Demand Already Satisfied ====================

    /**
     * When available capacity already meets or exceeds demand, the strategy
     * should return PROVISIONING_COMPLETED without provisioning anything.
     */
    @Test
    void demandSatisfied_returnsProvisioningCompleted() {
        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 2,
                /* availableExecutors */ 3,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Should return PROVISIONING_COMPLETED when demand is already satisfied");
        verify(state, never()).recordPendingLaunches(any(NodeProvisioner.PlannedNode[].class));
    }

    /**
     * Connecting executors ARE counted by the strategy to prevent redundant
     * provision() calls during the ~2-minute SSH tunnel setup window.
     * KubeVirt agents use pre-resolved futures, so planned capacity drops to
     * zero immediately.  Without counting connecting executors here, every
     * NodeProvisioner cycle would re-enter provision() unnecessarily.
     */
    @Test
    void connectingExecutorsCountedByStrategy() {
        jenkins.jenkins.clouds.clear();

        // demand=3, available=1, connecting=2 → totalAvailable=3, excessWorkload=0
        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 3,
                /* availableExecutors */ 1,
                /* connectingExecutors */ 2,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        // Connecting executors satisfy the remaining demand — no provisioning needed
        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Connecting executors should be counted by the strategy to prevent redundant provision() calls");
    }

    /**
     * When planned capacity from a previous round satisfies the demand,
     * no additional provisioning is needed.
     */
    @Test
    void demandSatisfiedByPlannedCapacity_returnsProvisioningCompleted() {
        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 2,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 3,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Planned capacity should count towards available capacity");
    }

    /**
     * When demand exactly equals capacity (available + planned + additionalPlanned),
     * excess workload is zero and no provisioning is needed.
     */
    @Test
    void demandExactlyEqualsCapacity_returnsProvisioningCompleted() {
        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 5,
                /* availableExecutors */ 3,
                /* plannedCapacity */ 1,
                /* additionalPlanned */ 1
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "When demand exactly equals capacity, no provisioning is needed");
    }

    // ==================== Strategy: Non-KubeVirt Clouds Only ====================

    /**
     * When only non-KubeVirt clouds are configured (e.g. EC2, Docker), the
     * strategy should defer to remaining strategies even with excess workload.
     */
    @Test
    void onlyNonKubeVirtClouds_consultsRemainingStrategies() {
        jenkins.jenkins.clouds.clear();
        jenkins.jenkins.clouds.add(new DummyCloud("other-cloud"));

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 5,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES, decision,
                "Should defer to other strategies when only non-KubeVirt clouds are configured");
    }

    // ==================== Strategy: Cloud Cannot Provision Label ====================

    /**
     * When there is a KubeVirtCloud but it cannot provision the requested label,
     * the strategy should defer to remaining strategies.
     */
    @Test
    void cloudCannotProvisionLabel_consultsRemainingStrategies() {
        jenkins.jenkins.clouds.clear();
        KubeVirtCloud cloud = new KubeVirtCloud(
                "test-cloud", "https://fake-server:6443", "creds", "default",
                true, "10", false, Collections.emptyList()
        );
        jenkins.jenkins.clouds.add(cloud);

        Label requestedLabel = Label.get("some-label-no-template-matches");

        NodeProvisioner.StrategyState state = mockStrategyState(
                requestedLabel,
                /* queueLength */ 3,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES, decision,
                "Should defer when KubeVirtCloud can't provision the requested label");
    }

    // ==================== Strategy: Multi-cloud round-robin ====================

    /**
     * With multiple KubeVirt clouds, each provisioning attempt should start with the next
     * cloud in Jenkins list order (round-robin), not always the first cloud.
     */
    @Test
    void multipleClouds_roundRobinRotatesStartingCloud() {
        jenkins.jenkins.clouds.clear();
        jenkins.jenkins.clouds.add(new OrderTrackingKubeVirtCloud("cloud-a"));
        jenkins.jenkins.clouds.add(new OrderTrackingKubeVirtCloud("cloud-b"));

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();

        NodeProvisioner.StrategyState state1 = mockStrategyState(
                null, 1, 0, 0, 0);
        strategy.apply(state1);

        NodeProvisioner.StrategyState state2 = mockStrategyState(
                null, 1, 0, 0, 0);
        strategy.apply(state2);

        assertEquals(List.of("cloud-a", "cloud-b"), OrderTrackingKubeVirtCloud.PROVISION_ORDER,
                "Second provisioning attempt should start with the next cloud in list order");
    }

    /**
     * When cloud rotation is disabled, clouds are always tried in Jenkins list order.
     */
    @Test
    void multipleClouds_rotationDisabled_alwaysUsesListOrder() {
        NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION = true;
        jenkins.jenkins.clouds.clear();
        jenkins.jenkins.clouds.add(new OrderTrackingKubeVirtCloud("cloud-a"));
        jenkins.jenkins.clouds.add(new OrderTrackingKubeVirtCloud("cloud-b"));

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();

        for (int i = 0; i < 2; i++) {
            strategy.apply(mockStrategyState(null, 1, 0, 0, 0));
        }

        assertEquals(List.of("cloud-a", "cloud-a"), OrderTrackingKubeVirtCloud.PROVISION_ORDER);
    }

    // ==================== Strategy: Provisioning Path ====================

    /**
     * When a KubeVirtCloud can provision and there is excess workload, the
     * strategy should call provision(), record the planned launches, and
     * return PROVISIONING_COMPLETED.
     */
    @Test
    void excessWorkload_provisionsAndRecordsLaunches() {
        jenkins.jenkins.clouds.clear();

        // Create a testable cloud that returns 3 planned nodes
        List<NodeProvisioner.PlannedNode> fakePlanned = createFakePlannedNodes(3);
        jenkins.jenkins.clouds.add(new TestableKubeVirtCloud("test-cloud", true, fakePlanned));

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 3,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Should return PROVISIONING_COMPLETED when all demand is satisfied");
        // Verify that recordPendingLaunches was called with the provisioned nodes
        verify(state).recordPendingLaunches(fakePlanned);
    }

    /**
     * When a KubeVirtCloud provisions some nodes but can't fully satisfy
     * the demand (e.g. cap reached), the strategy should return
     * CONSULT_REMAINING_STRATEGIES so other strategies can handle the rest.
     */
    @Test
    void partialProvisioning_consultsRemainingStrategies() {
        jenkins.jenkins.clouds.clear();

        // Cloud returns only 2 nodes even though 5 are needed
        List<NodeProvisioner.PlannedNode> fakePlanned = createFakePlannedNodes(2);
        jenkins.jenkins.clouds.add(new TestableKubeVirtCloud("test-cloud", true, fakePlanned));

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 5,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES, decision,
                "Should defer to remaining strategies when demand is only partially met");
        verify(state).recordPendingLaunches(fakePlanned);
    }

    // ==================== Strategy: Exception Safety ====================

    /**
     * When cloud.provision() throws an exception, the strategy should catch it,
     * log it, and continue with remaining clouds instead of crashing.
     */
    @Test
    void provisionThrowsException_catchesAndContinues() {
        jenkins.jenkins.clouds.clear();

        // First cloud throws, second cloud succeeds
        jenkins.jenkins.clouds.add(new ThrowingKubeVirtCloud("bad-cloud"));
        List<NodeProvisioner.PlannedNode> fakePlanned = createFakePlannedNodes(3);
        jenkins.jenkins.clouds.add(new TestableKubeVirtCloud("good-cloud", true, fakePlanned));

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 3,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        // Should not throw — the exception from "bad-cloud" is caught
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Second cloud should satisfy demand after first cloud's exception is caught");
        verify(state).recordPendingLaunches(fakePlanned);
    }

    /**
     * When the only KubeVirtCloud throws an exception during provisioning,
     * the strategy should catch it and defer to remaining strategies.
     */
    @Test
    void singleCloudThrows_consultsRemainingStrategies() {
        jenkins.jenkins.clouds.clear();
        jenkins.jenkins.clouds.add(new ThrowingKubeVirtCloud("bad-cloud"));

        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 3,
                /* availableExecutors */ 0,
                /* plannedCapacity */ 0,
                /* additionalPlanned */ 0
        );

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        // Should not throw
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES, decision,
                "Should defer when the only cloud throws an exception");
    }

    // ==================== Strategy: Excess Workload Calculation ====================

    /**
     * Verifies that the excess workload is correctly computed as
     * demand - (available + connecting + planned + additionalPlanned).
     */
    @Test
    void excessWorkloadCalculation_includesConnectingExecutors() {
        // demand=10, available=2, connecting=3, planned=1, additional=1
        // → totalAvailable=7, excess=3
        NodeProvisioner.StrategyState state = mockStrategyState(
                /* label */ null,
                /* queueLength */ 10,
                /* availableExecutors */ 2,
                /* connectingExecutors */ 3,
                /* plannedCapacity */ 1,
                /* additionalPlanned */ 1
        );

        jenkins.jenkins.clouds.clear();
        // Cloud returns 3 nodes to satisfy the remaining demand
        List<NodeProvisioner.PlannedNode> fakePlanned = createFakePlannedNodes(3);
        jenkins.jenkins.clouds.add(new TestableKubeVirtCloud("test-cloud", true, fakePlanned));

        NoDelayProvisionerStrategy strategy = new NoDelayProvisionerStrategy();
        NodeProvisioner.StrategyDecision decision = strategy.apply(state);

        assertEquals(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED, decision,
                "Excess workload should be demand minus (available + connecting + planned + additional)");
        verify(state).recordPendingLaunches(fakePlanned);
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a mocked {@link NodeProvisioner.StrategyState} with the given parameters.
     */
    private NodeProvisioner.StrategyState mockStrategyState(
            Label label, int queueLength, int availableExecutors,
            int connectingExecutors, int plannedCapacity, int additionalPlanned) {

        LoadStatistics.LoadStatisticsSnapshot snapshot =
                mock(LoadStatistics.LoadStatisticsSnapshot.class);
        when(snapshot.getQueueLength()).thenReturn(queueLength);
        when(snapshot.getAvailableExecutors()).thenReturn(availableExecutors);
        when(snapshot.getConnectingExecutors()).thenReturn(connectingExecutors);

        NodeProvisioner.StrategyState state =
                mock(NodeProvisioner.StrategyState.class);
        when(state.getLabel()).thenReturn(label);
        when(state.getSnapshot()).thenReturn(snapshot);
        when(state.getPlannedCapacitySnapshot()).thenReturn(plannedCapacity);
        when(state.getAdditionalPlannedCapacity()).thenReturn(additionalPlanned);

        return state;
    }

    /**
     * Convenience overload for tests that don't care about connecting executors.
     */
    private NodeProvisioner.StrategyState mockStrategyState(
            Label label, int queueLength, int availableExecutors,
            int plannedCapacity, int additionalPlanned) {
        return mockStrategyState(label, queueLength, availableExecutors,
                0, plannedCapacity, additionalPlanned);
    }

    /**
     * Creates a list of fake {@link NodeProvisioner.PlannedNode} instances
     * with pre-resolved futures for testing.
     */
    private List<NodeProvisioner.PlannedNode> createFakePlannedNodes(int count) {
        List<NodeProvisioner.PlannedNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompletableFuture<Node> future = new CompletableFuture<>();
            // Don't resolve the future — we just need the PlannedNode container
            nodes.add(new NodeProvisioner.PlannedNode("fake-node-" + i, future, 1));
        }
        return nodes;
    }

    // ==================== Test Cloud Implementations ====================

    /**
     * Minimal concrete {@link Cloud} subclass (non-KubeVirt) used in tests to
     * avoid serialization issues that occur when adding Mockito mocks to the
     * Jenkins cloud list.
     */
    private static class DummyCloud extends Cloud {
        DummyCloud(String name) {
            super(name);
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
            return Collections.emptyList();
        }

        @Override
        public boolean canProvision(CloudState state) {
            return false;
        }
    }

    /**
     * A {@link KubeVirtCloud} subclass that returns controlled results from
     * {@code canProvision()} and {@code provision()} without making real API calls.
     * Fields are transient to avoid XStream serialization issues with CompletableFuture
     * when Jenkins saves its cloud configuration to disk.
     */
    private static class TestableKubeVirtCloud extends KubeVirtCloud {
        private transient final boolean canProvisionResult;
        private transient final Collection<NodeProvisioner.PlannedNode> provisionResult;

        TestableKubeVirtCloud(String name, boolean canProvision,
                              Collection<NodeProvisioner.PlannedNode> provisionResult) {
            super(name, "https://fake:6443", "creds", "default",
                    true, "10", false, Collections.emptyList());
            this.canProvisionResult = canProvision;
            this.provisionResult = provisionResult;
        }

        @Override
        public boolean canProvision(CloudState state) {
            return canProvisionResult;
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
            return provisionResult;
        }
    }

    /**
     * A {@link KubeVirtCloud} subclass whose {@code provision()} method always
     * throws a {@link RuntimeException}, used to test exception safety.
     */
    /**
     * Records cloud names in {@link #PROVISION_ORDER} whenever {@code provision()} runs.
     */
    private static class OrderTrackingKubeVirtCloud extends KubeVirtCloud {
        static final List<String> PROVISION_ORDER = new ArrayList<>();

        OrderTrackingKubeVirtCloud(String name) {
            super(name, "https://fake:6443", "creds", "default",
                    true, "10", false, Collections.emptyList());
        }

        @Override
        public boolean canProvision(CloudState state) {
            return true;
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
            PROVISION_ORDER.add(name);
            return List.of(new NodeProvisioner.PlannedNode(
                    name + "-node", new CompletableFuture<>(), 1));
        }
    }

    private static class ThrowingKubeVirtCloud extends KubeVirtCloud {
        ThrowingKubeVirtCloud(String name) {
            super(name, "https://fake:6443", "creds", "default",
                    true, "10", false, Collections.emptyList());
        }

        @Override
        public boolean canProvision(CloudState state) {
            return true;
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
            throw new RuntimeException("Simulated provisioning failure");
        }
    }
}
