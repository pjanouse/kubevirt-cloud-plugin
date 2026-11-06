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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements instant provisioning for KubeVirt cloud agents.
 *
 * <p>By default, Jenkins' {@link NodeProvisioner} runs on a periodic timer with
 * dampening heuristics that delay provisioning. This strategy bypasses those delays by:</p>
 * <ol>
 *   <li>Running at high priority ({@code ordinal=100}), before the default strategy.</li>
 *   <li>Provisioning the exact number of agents needed immediately.</li>
 *   <li>Returning {@code PROVISIONING_COMPLETED} to skip the default strategy entirely.</li>
 * </ol>
 *
 * <p>While KubeVirt VMs take longer to boot than containers, we still want to
 * <em>start</em> provisioning immediately. The delay eliminated here is between
 * "build enters queue" and "provisioning is triggered", not the VM boot time itself.</p>
 *
 * <p>This works in tandem with the {@link FastProvisioning} queue listener (inner class),
 * which wakes up the {@code NodeProvisioner} the instant a buildable item enters the
 * queue, converting Jenkins' periodic polling model into an event-driven one.</p>
 *
 * <p>Can be disabled via system property at JVM startup:</p>
 * <pre>-Dio.jenkins.plugins.kubevirt.disableNoDelayProvisioning=true</pre>
 * <p>or at runtime in the script console (no restart):</p>
 * <pre>io.jenkins.plugins.kubevirt.NoDelayProvisionerStrategy.DISABLE_NO_DELAY = true</pre>
 *
 * <p>When several {@link KubeVirtCloud} instances are configured, this strategy visits them in
 * round-robin order (see plugin README). Rotation can be disabled to always use Jenkins cloud
 * list order:</p>
 * <pre>-DNoDelayProvisionerStrategy.disableCloudRotation=true</pre>
 * <p>or {@code NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION = true} in the script console.
 * The legacy property {@code NoDelayProvisionerStrategy.disableCloudShuffle} is still honored.</p>
 */
@Extension(ordinal = 100)
public class NoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(NoDelayProvisionerStrategy.class.getName());

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL",
            justification = "Mutable for runtime tuning via Jenkins script console without JVM restart")
    public static boolean DISABLE_NO_DELAY =
            Boolean.getBoolean("io.jenkins.plugins.kubevirt.disableNoDelayProvisioning");

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL",
            justification = "Mutable for runtime tuning via Jenkins script console without JVM restart")
    public static boolean DISABLE_CLOUD_ROTATION =
            Boolean.getBoolean("NoDelayProvisionerStrategy.disableCloudRotation")
                    || Boolean.getBoolean("NoDelayProvisionerStrategy.disableCloudShuffle");

    /** Advances the starting cloud for each provisioning attempt (multiple KubeVirt clouds). */
    private static final AtomicInteger CLOUD_ROTATION_INDEX = new AtomicInteger();

    /**
     * Resets multi-cloud round-robin state. Intended for unit tests only.
     */
    static void resetCloudRotationForTesting() {
        CLOUD_ROTATION_INDEX.set(0);
    }

    @Override
    public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState strategyState) {
        if (DISABLE_NO_DELAY) {
            LOGGER.log(Level.FINE, "NoDelayProvisionerStrategy is disabled via system property");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        final Label label = strategyState.getLabel();

        // Gather current state from the provisioner snapshot.
        // We include connectingExecutors because KubeVirt agents use pre-resolved
        // futures (CompletableFuture.completedFuture), which means planned capacity
        // drops to zero as soon as the node is registered.  Without counting
        // connecting executors here, every NodeProvisioner cycle during the ~2-minute
        // SSH tunnel setup would see excess workload and call provision() again,
        // forcing provision() to run redundant connecting-agent checks each time.
        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
        int availableExecutors = snapshot.getAvailableExecutors();
        int connectingExecutors = snapshot.getConnectingExecutors();
        int plannedCapacity = strategyState.getPlannedCapacitySnapshot();
        int additionalPlanned = strategyState.getAdditionalPlannedCapacity();

        int totalAvailable = availableExecutors + connectingExecutors + plannedCapacity + additionalPlanned;
        int currentDemand = snapshot.getQueueLength();

        LOGGER.log(Level.FINE,
                "Label ''{0}'': demand={1}, totalAvailable={2} "
                        + "(available={3}, connecting={4}, planned={5}, additionalPlanned={6})",
                new Object[]{label, currentDemand, totalAvailable,
                        availableExecutors, connectingExecutors, plannedCapacity, additionalPlanned});

        int excessWorkload = currentDemand - totalAvailable;

        if (excessWorkload <= 0) {
            LOGGER.log(Level.FINE, "Label ''{0}'': demand already satisfied, no provisioning needed", label);
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        }

        // Collect all KubeVirtCloud instances
        List<KubeVirtCloud> kubeVirtClouds = new ArrayList<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof KubeVirtCloud kubeVirtCloud) {
                kubeVirtClouds.add(kubeVirtCloud);
            }
        }

        if (kubeVirtClouds.isEmpty()) {
            // No KubeVirt clouds configured — let other strategies handle it
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        // Round-robin starting cloud (Jenkins cloud list order when rotation is disabled)
        if (kubeVirtClouds.size() > 1 && !DISABLE_CLOUD_ROTATION) {
            int start = Math.floorMod(CLOUD_ROTATION_INDEX.getAndIncrement(), kubeVirtClouds.size());
            if (start > 0) {
                List<KubeVirtCloud> rotated = new ArrayList<>(kubeVirtClouds.size());
                for (int i = 0; i < kubeVirtClouds.size(); i++) {
                    rotated.add(kubeVirtClouds.get((start + i) % kubeVirtClouds.size()));
                }
                kubeVirtClouds = rotated;
            }
        }

        // Track whether any nodes were actually provisioned
        boolean provisionedAny = false;

        // Provision from each cloud until demand is met
        for (KubeVirtCloud cloud : kubeVirtClouds) {
            if (excessWorkload <= 0) {
                break;
            }

            Cloud.CloudState cloudState = new Cloud.CloudState(label, 0);
            if (!cloud.canProvision(cloudState)) {
                continue;
            }

            Collection<NodeProvisioner.PlannedNode> plannedNodes;
            try {
                plannedNodes = cloud.provision(cloudState, excessWorkload);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Cloud ''" + cloud.name + "'' threw an exception during provisioning", e);
                continue;
            }

            LOGGER.log(Level.FINE, "Cloud ''{0}'' provisioned {1} node(s) for label ''{2}''",
                    new Object[]{cloud.name, plannedNodes.size(), label});

            if (!plannedNodes.isEmpty()) {
                // Notify CloudProvisioningListeners (including cloud-stats) that
                // provisioning has started.  Jenkins core's StandardStrategyImpl
                // fires this automatically, but custom Strategy implementations
                // must do it themselves — recordPendingLaunches does NOT fire it.
                fireOnStarted(cloud, label, plannedNodes);

                strategyState.recordPendingLaunches(plannedNodes);
                excessWorkload -= plannedNodes.size();
                provisionedAny = true;
            }
        }

        // Schedule a follow-up review 1 second later to handle state transitions
        // or newly queued items that arrived during provisioning.
        // Only schedule when nodes were actually provisioned — otherwise we'd create
        // a tight 1-second polling loop when no cloud can serve the requested label.
        if (provisionedAny) {
            NodeProvisioner provisioner = label == null
                    ? Jenkins.get().unlabeledNodeProvisioner
                    : label.nodeProvisioner;
            Timer.get().schedule(provisioner::suggestReviewNow, 1L, TimeUnit.SECONDS);
        }

        if (excessWorkload <= 0) {
            LOGGER.log(Level.FINE, "Label ''{0}'': all demand satisfied by KubeVirt clouds", label);
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        }

        // Some demand remains that KubeVirt clouds couldn't satisfy — let other strategies try
        return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
    }

    /**
     * Notifies all {@link CloudProvisioningListener} extensions that provisioning
     * has started.  This mirrors the call Jenkins core's
     * {@code StandardStrategyImpl} makes inside
     * {@link NodeProvisioner#update} — custom strategies must call it
     * explicitly because {@link NodeProvisioner.StrategyState#recordPendingLaunches}
     * does <em>not</em> fire this event.
     */
    private static void fireOnStarted(Cloud cloud, Label label,
                                       Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
            try {
                listener.onStarted(cloud, label, plannedNodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE,
                        "Unexpected exception in onStarted() listener " + listener
                                + " for label " + label, e);
            }
        }
    }

    /**
     * Event-driven queue listener that wakes up the {@link NodeProvisioner} immediately
     * when a new buildable item enters the queue and a {@link KubeVirtCloud} can serve it.
     *
     * <p>Without this listener, the provisioner only runs on a periodic timer (with
     * exponential back-off). This converts that polling model into an event-driven one,
     * so that the {@link NoDelayProvisionerStrategy} runs within milliseconds of a build
     * entering the queue.</p>
     *
     * <p>Disabled when
     * {@code -Dio.jenkins.plugins.kubevirt.disableNoDelayProvisioning=true} is set.</p>
     */
    @Extension
    public static class FastProvisioning extends QueueListener {

        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            if (DISABLE_NO_DELAY) {
                return;
            }

            final Jenkins jenkins = Jenkins.get();
            final Label label = item.getAssignedLabel();

            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof KubeVirtCloud kubeVirtCloud
                        && kubeVirtCloud.canProvision(new Cloud.CloudState(label, 0))) {
                    final NodeProvisioner provisioner =
                            label == null ? jenkins.unlabeledNodeProvisioner : label.nodeProvisioner;
                    provisioner.suggestReviewNow();
                    // Only need to wake up once — the strategy will iterate all clouds
                    return;
                }
            }
        }
    }
}
