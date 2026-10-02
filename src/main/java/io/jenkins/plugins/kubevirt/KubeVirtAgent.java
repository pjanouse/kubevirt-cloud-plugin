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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;

/**
 * Jenkins agent running on a KubeVirt virtual machine.
 * Implements TrackedItem for cloud-stats plugin integration.
 */
public class KubeVirtAgent extends AbstractCloudSlave implements TrackedItem {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(KubeVirtAgent.class.getName());

    /**
     * Reasons why a KubeVirt agent can be terminated.
     */
    public enum TerminationReason {
        /** Agent was idle for too long ({@link CloudRetentionStrategy}) */
        IDLE_TIMEOUT,
        /** Single-use agent completed its build ({@link OnceRetentionStrategy}) */
        SINGLE_USE_COMPLETED,
        /** User deleted the agent from the Jenkins UI */
        USER_DELETED,
        /** Agent failed to launch */
        LAUNCH_FAILURE,
        /** VM provisioning timed out */
        PROVISIONING_TIMEOUT
    }

    private final String cloudName;
    private final String templateName;
    private String provisioningInfo;
    private final int idleMinutes;
    private final ProvisioningActivity.Id provisioningId;
    
    /** 
     * Tracks why this agent is being terminated. 
     * Transient because it's only relevant during the termination process.
     */
    private transient TerminationReason terminationReason;

    /**
     * Whether this launch already decided whether to count a provisioning failure.
     * Prevents {@code abortProvisioning} and {@code onLaunchFailure} from
     * double-counting, and lets an interrupt mark the launch handled without
     * incrementing the retry budget.
     */
    private transient volatile boolean provisioningFailureHandled;

    /**
     * Creates a new KubeVirt agent.
     *
     * @param name            The node name (also the VM name)
     * @param cloudName       The name of the KubeVirt cloud that created this agent
     * @param templateName    The name of the template used to create this agent
     * @param remoteFS        The remote filesystem path for the agent
     * @param launcher        The computer launcher
     * @param labelString     Labels for this agent
     * @param provisioningInfo Provisioning log information
     * @param idleMinutes     Idle timeout in minutes (0 = single-use)
     * @param provisioningId  The cloud-stats provisioning activity ID
     */
    public KubeVirtAgent(String name, String cloudName, String templateName, String remoteFS,
                         ComputerLauncher launcher, String labelString,
                         String provisioningInfo, int idleMinutes,
                         ProvisioningActivity.Id provisioningId)
            throws Descriptor.FormException, IOException {
        super(name, remoteFS, launcher);
        this.cloudName = cloudName;
        this.templateName = templateName;
        this.provisioningInfo = provisioningInfo;
        this.idleMinutes = idleMinutes >= 0 ? idleMinutes : KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
        this.provisioningId = provisioningId;
        
        // Set labels so builds can be matched to this agent
        setLabelString(labelString);
        setMode(Mode.EXCLUSIVE);  // Only run builds that match our labels
        
        // Use appropriate retention strategy based on idle minutes
        RetentionStrategy<?> retentionStrategy = createRetentionStrategy(this.idleMinutes);
        setRetentionStrategy(retentionStrategy);
        setNodeProperties(Collections.emptyList());
        
        String retentionDesc = this.idleMinutes == 0 
                ? "single-use (disposed after first build)" 
                : this.idleMinutes + " minute idle termination";
        LOGGER.log(Level.FINE, "Created agent {0} with labels ''{1}'' and {2}",
                new Object[]{name, labelString, retentionDesc});
    }
    
    /**
     * Creates the appropriate retention strategy based on idle minutes.
     * 
     * @param idleMinutes The idle timeout in minutes. 0 means dispose after first build.
     * @return The retention strategy to use
     */
    private static RetentionStrategy<?> createRetentionStrategy(int idleMinutes) {
        if (idleMinutes == 0) {
            // Terminate after one build (works for freestyle and Pipeline agents).
            // Backup idle timeout if executor completion is missed.
            return new OnceRetentionStrategy(1);
        }
        return new CloudRetentionStrategy(idleMinutes);
    }
    
    /**
     * Returns true if this is a single-use agent (idleMinutes == 0).
     * Single-use agents should be disposed immediately after the first build completes.
     * 
     * @return true if this is a single-use agent
     */
    public boolean isSingleUse() {
        return idleMinutes == 0;
    }
    
    /**
     * Gets the configured idle minutes for this agent.
     * @return The idle timeout in minutes (0 means single-use)
     */
    public int getIdleMinutes() {
        return idleMinutes;
    }

    /**
     * Gets the provisioning information captured during VM creation.
     * This is displayed in the computer log when the agent comes online.
     * 
     * @return The provisioning info, or null if not available
     */
    public String getProvisioningInfo() {
        return provisioningInfo;
    }

    /**
     * Sets the provisioning information. Called by the
     * {@link KubeVirtProvisioningLauncher} after provisioning completes
     * (or fails) so the build console can display it.
     *
     * @param provisioningInfo The provisioning log text
     */
    public void setProvisioningInfo(String provisioningInfo) {
        this.provisioningInfo = provisioningInfo;
    }

    public String getCloudName() {
        return cloudName;
    }

    /**
     * Gets the name of the template that was used to create this agent.
     *
     * @return The template name
     */
    public String getTemplateName() {
        return templateName;
    }
    
    /**
     * Sets the reason for termination. Call this before calling terminate()
     * to ensure the correct offline cause is reported.
     * 
     * @param reason The reason for termination
     */
    public void setTerminationReason(TerminationReason reason) {
        this.terminationReason = reason;
    }

    /**
     * Claims this launch for provisioning-failure tracking.
     *
     * @return {@code true} if this is the first claim and the caller should
     *         record (or explicitly skip) the failure; {@code false} if another
     *         path already handled this launch
     */
    public synchronized boolean tryMarkProvisioningFailureHandled() {
        if (provisioningFailureHandled) {
            return false;
        }
        provisioningFailureHandled = true;
        return true;
    }
    
    /**
     * Gets the termination reason, or infers it from the agent configuration
     * if not explicitly set.
     * 
     * @return The termination reason, never null
     */
    @NonNull
    public TerminationReason getTerminationReason() {
        if (terminationReason != null) {
            return terminationReason;
        }
        // Infer from configuration if not explicitly set
        return idleMinutes == 0 ? TerminationReason.SINGLE_USE_COMPLETED : TerminationReason.IDLE_TIMEOUT;
    }

    /**
     * Gets the provisioning activity ID for cloud-stats tracking.
     * This links the agent to its provisioning activity in the cloud-stats plugin.
     *
     * @return The provisioning activity ID
     */
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new KubeVirtComputer(this);
    }

    @Override
    public SlaveDescriptor getDescriptor() {
        return (SlaveDescriptor) super.getDescriptor();
    }
    
    /**
     * Returns a human-readable description of the termination reason for logging.
     */
    private String getTerminationDescription(TerminationReason reason) {
        switch (reason) {
            case IDLE_TIMEOUT:
                return "idle timeout: " + idleMinutes + " minutes";
            case SINGLE_USE_COMPLETED:
                return "single-use agent completed";
            case USER_DELETED:
                return "deleted by user";
            case LAUNCH_FAILURE:
                return "launch failure";
            case PROVISIONING_TIMEOUT:
                return "provisioning timeout";
            default:
                // Should never happen - all enum values are covered above
                throw new AssertionError("Unexpected termination reason: " + reason);
        }
    }
    
    /**
     * Returns the appropriate OfflineCause for the given termination reason.
     */
    private OfflineCause getOfflineCause(TerminationReason reason) {
        switch (reason) {
            case IDLE_TIMEOUT:
                return OfflineCause.create(Messages._KubeVirtAgent_TerminatedDueToIdleTimeout(idleMinutes));
            case SINGLE_USE_COMPLETED:
                return OfflineCause.create(Messages._KubeVirtAgent_TerminatedSingleUse());
            case USER_DELETED:
                return OfflineCause.create(Messages._KubeVirtAgent_TerminatedByUser());
            case LAUNCH_FAILURE:
                return OfflineCause.create(Messages._KubeVirtAgent_TerminatedLaunchFailure());
            case PROVISIONING_TIMEOUT:
                return OfflineCause.create(Messages._KubeVirtAgent_TerminatedProvisioningTimeout());
            default:
                // Should never happen - all enum values are covered above
                throw new AssertionError("Unexpected termination reason: " + reason);
        }
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        TerminationReason reason = getTerminationReason();
        String reasonDescription = getTerminationDescription(reason);
        
        LOGGER.log(Level.FINE, "Agent {0} terminating ({1}). VM will be deleted.",
                new Object[]{getNodeName(), reasonDescription});
        KubeVirtLog.log(listener.getLogger(), "Terminating VM: " + getNodeName() + 
                " (" + reasonDescription + ")");
        
        // First, ensure the computer is disconnected gracefully
        // This gives the SSH channel time to close cleanly before we delete the VM
        KubeVirtComputer computer = (KubeVirtComputer) toComputer();
        if (computer != null && computer.isOnline()) {
            KubeVirtLog.log(listener.getLogger(), "Disconnecting agent before VM deletion...");
            OfflineCause cause = getOfflineCause(reason);
            computer.disconnect(cause);

            // Wait briefly for the channel to close gracefully
            try {
                Thread.sleep(KubeVirtConfiguration.DISCONNECT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        try {
            KubeVirtCloud cloud = (KubeVirtCloud) jenkins.model.Jenkins.get().getCloud(cloudName);
            if (cloud != null) {
                cloud.deleteVM(getNodeName(), templateName);
                KubeVirtLog.log(listener.getLogger(), "VM terminated successfully.");
            } else {
                KubeVirtLog.error(listener, "Cloud '" + cloudName + "' not found, could not delete VM.");
            }
        } catch (Exception e) {
            KubeVirtLog.error(listener, "Failed to terminate VM: " + KubeVirtLog.messageOf(e));
            throw new IOException(e);
        }
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @Override
        public String getDisplayName() {
            return "KubeVirt Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
