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

import hudson.model.Node;
import hudson.slaves.NodeProvisioner;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A PlannedNode implementation that integrates with the cloud-stats plugin.
 *
 * <p>With the provisioning-launcher approach, the {@link Future} is typically
 * pre-resolved ({@code CompletableFuture.completedFuture(agent)}), so the
 * agent node is registered by Jenkins core almost immediately. VM lifecycle
 * (creation, waiting, SSH) is handled inside
 * {@link KubeVirtProvisioningLauncher#launch}, not in the future.</p>
 *
 * <p>This class still handles the edge case where agent construction itself
 * fails (the future completes exceptionally) — there is no VM to clean up
 * in that case since provisioning hasn't started yet.</p>
 */
public class KubeVirtPlannedNode extends NodeProvisioner.PlannedNode implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(KubeVirtPlannedNode.class.getName());

    private final ProvisioningActivity.Id provisioningId;
    private final String cloudName;
    private final String templateName;

    /**
     * Creates a new tracked planned node.
     *
     * @param displayName    The display name for this node
     * @param future         The future that will provide the node
     * @param numExecutors   The number of executors this node will provide
     * @param provisioningId The cloud-stats provisioning activity ID
     * @param templateName   The name of the template being used for provisioning
     */
    public KubeVirtPlannedNode(String displayName, Future<Node> future,
                               int numExecutors, ProvisioningActivity.Id provisioningId,
                               String templateName) {
        super(displayName, future, numExecutors);
        this.provisioningId = provisioningId;
        this.cloudName = provisioningId.getCloudName();
        this.templateName = templateName;
    }

    /**
     * Gets the name of the cloud that is provisioning this node.
     *
     * @return The cloud name
     */
    public String getCloudName() {
        return cloudName;
    }

    /**
     * Gets the name of the template being used to provision this node.
     *
     * @return The template name
     */
    public String getTemplateName() {
        return templateName;
    }

    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    /**
     * Called when this planned node is being consumed by Jenkins.
     *
     * <p>With the pre-resolved future approach, this is called shortly after
     * {@code provision()} returns. In the success case (the normal path), the
     * node was already created and Jenkins will add it — no cleanup needed.
     * The only case requiring action is when the future completed exceptionally
     * (agent construction failed), but since no VM was created at that point,
     * there is nothing to clean up.</p>
     */
    @Override
    public void spent() {
        super.spent();

        String nodeName = provisioningId.getNodeName();

        if (future.isCancelled()) {
            LOGGER.log(Level.FINE, "PlannedNode {0} was cancelled", nodeName);
        } else if (future.isDone()) {
            try {
                Node node = future.get();
                if (node != null) {
                    LOGGER.log(Level.FINE,
                            "PlannedNode {0} successfully resolved, VM lifecycle managed by launcher",
                            nodeName);
                } else {
                    LOGGER.log(Level.WARNING,
                            "PlannedNode {0} completed with null node", nodeName);
                }
            } catch (InterruptedException | ExecutionException e) {
                // Agent construction failed — no VM was created, nothing to clean up
                LOGGER.log(Level.FINE,
                        "PlannedNode {0} failed during agent construction (no VM to clean up): {1}",
                        new Object[]{nodeName, e.getMessage()});
            }
        }
    }
}
