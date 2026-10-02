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

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.jenkins.plugins.kubevirt.exception.ErrorCode;
import io.jenkins.plugins.kubevirt.exception.ProvisioningException;
import io.jenkins.plugins.kubevirt.internal.KubernetesClientExceptions;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A provisioning-aware launcher that handles the full VM lifecycle before
 * delegating to the real SSH launcher.
 *
 * <p>This launcher is set on the {@link KubeVirtAgent} at construction time,
 * allowing Jenkins to register the node immediately (making it visible in the
 * UI as "offline / connecting"). When Jenkins core invokes {@link #launch},
 * the launcher creates the VM, waits for it to become ready, obtains the IP
 * address (if needed), creates the real SSH launcher, and then delegates the
 * actual SSH connection to it.</p>
 *
 * <p>All provisioning log messages are written to the {@link TaskListener}
 * provided by Jenkins, so they appear live on the agent's
 * {@code /computer/<name>/log} page.</p>
 */
public class KubeVirtProvisioningLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(KubeVirtProvisioningLauncher.class.getName());

    private final String cloudName;
    private final String templateName;

    /**
     * The real launcher that will handle the SSH connection once provisioning
     * is complete. Transient because it is created during {@link #launch}.
     */
    private transient volatile ComputerLauncher delegate;

    /**
     * Whether the launch has completed successfully at least once.
     * Prevents duplicate launches.
     */
    private transient volatile boolean launched = false;

    /**
     * Creates a new provisioning launcher.
     *
     * @param cloudName    The name of the KubeVirt cloud (used to look up
     *                     the cloud at launch time)
     * @param templateName The name of the template to provision from
     */
    public KubeVirtProvisioningLauncher(String cloudName, String templateName) {
        this.cloudName = cloudName;
        this.templateName = templateName;
    }

    @Override
    public boolean isLaunchSupported() {
        return !launched;
    }

    @Override
    public synchronized void launch(SlaveComputer computer, TaskListener listener)
            throws IOException, InterruptedException {

        if (!(computer instanceof KubeVirtComputer)) {
            throw new IllegalArgumentException(
                    "KubeVirtProvisioningLauncher can only be used with KubeVirtComputer");
        }

        KubeVirtComputer kvc = (KubeVirtComputer) computer;
        KubeVirtAgent agent = kvc.getNode();
        if (agent == null) {
            throw new IllegalStateException("Node has been removed, cannot launch " + computer.getName());
        }

        if (launched && delegate != null) {
            LOGGER.log(Level.FINE, "Agent already launched, re-delegating: {0}", agent.getNodeName());
            delegate.launch(computer, listener);
            return;
        }

        String nodeName = agent.getNodeName();
        PrintStream log = listener.getLogger();

        KubeVirtLog.log(log, "══════════════════════════════════════════════════════════════");
        KubeVirtLog.log(log, "Provisioning VM: " + nodeName);
        KubeVirtLog.log(log, "Cloud: " + cloudName + " | Template: " + templateName);
        KubeVirtLog.log(log, "══════════════════════════════════════════════════════════════");

        // Look up cloud and template from Jenkins at launch time
        KubeVirtCloud cloud = lookupCloud();
        KubeVirtTemplate template = lookupTemplate(cloud);
        KubeVirtCloudConfig config = cloud.getCloudConfig();

        // Capture provisioning log for build console output
        StringBuilder provisioningLog = new StringBuilder();

        // Create a callback that writes to both the computer log (TaskListener)
        // and captures text for the build console (provisioningInfo)
        ProvisioningCallback callback = message -> {
            LOGGER.log(Level.FINE, "[{0}] {1}", new Object[]{nodeName, message});
            KubeVirtLog.log(log, message);
            provisioningLog.append(message).append("\n");
        };

        logProvisioningAttempt(callback, template);

        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        KubeVirtLauncherFactory launcherFactory = new KubeVirtLauncherFactory();
        boolean inFlightDecremented = false;

        try (KubeVirt virt = clientFactory.createClient(config)) {
            // Phase 1: Create VM
            String cloudInitContent = template.getCloudInitContent();
            if (cloudInitContent != null && !cloudInitContent.isEmpty()) {
                callback.log("Using cloud-init configuration: " + template.getCloudInitId());
            } else {
                callback.log("WARNING: No cloud-init config selected. VM will boot without SSH key injection.");
            }

            final String dvName = nodeName + "-disk";
            createVM(virt, template, nodeName, config.getCloudName(), cloudInitContent, callback);

            // VM now exists in Kubernetes — decrement the in-flight counter so
            // the K8s label query counts it instead.
            cloud.decrementInFlightCount(templateName);
            inFlightDecremented = true;

            // Phase 2: Wait for VM to be ready and get IP
            String ip = waitForVMReady(virt, template, nodeName, dvName, callback);

            callback.log("VM provisioning completed successfully");

            // Phase 3: Validate SSH credentials
            validateSshCredentials(template, nodeName, callback);

            // Phase 4: Create real launcher and delegate
            delegate = launcherFactory.createLauncher(template, nodeName, ip, config, callback);

            // Log retention policy
            logRetentionPolicy(template, callback);

            // Store provisioning info on the agent for build console output
            agent.setProvisioningInfo(provisioningLog.toString());

            // Update the agent's launcher to the real one for subsequent reconnects.
            // If Jenkins restarts, the persisted launcher will be the real SSH one.
            agent.setLauncher(delegate);
            saveAgent(agent);

            KubeVirtLog.log(log, "──────────────────────────────────────────────────────────");
            KubeVirtLog.log(log, "VM provisioning complete, launching SSH connection...");
            KubeVirtLog.log(log, "──────────────────────────────────────────────────────────");

            // Delegate to real SSH launcher.
            // VirtctlPortForwardLauncher handles its own auth retry internally.
            // For direct SSH, wrap with auth retry since cloud-init may still be
            // injecting authorized_keys when sshd is already up.
            if (delegate instanceof VirtctlPortForwardLauncher) {
                delegate.launch(computer, listener);
            } else {
                SshAuthHelper.launchWithAuthRetry(computer, listener,
                        (c, l) -> delegate.launch(c, l), nodeName);
            }

            launched = true;

        } catch (ProvisioningException e) {
            if (!inFlightDecremented) { cloud.decrementInFlightCount(templateName); }
            KubeVirtLog.error(listener, "Provisioning failed: " + KubeVirtLog.messageOf(e));
            LOGGER.log(Level.SEVERE, "Provisioning failed for " + nodeName, e);
            agent.setProvisioningInfo(provisioningLog.toString());
            KubeVirtAgent.TerminationReason reason = e.getErrorCode() == ErrorCode.PROVISIONING_TIMEOUT
                    ? KubeVirtAgent.TerminationReason.PROVISIONING_TIMEOUT
                    : KubeVirtAgent.TerminationReason.LAUNCH_FAILURE;
            abortProvisioning(agent, listener, reason);
            throw e;
        } catch (KubernetesClientException e) {
            if (!inFlightDecremented) { cloud.decrementInFlightCount(templateName); }
            String errorMsg = buildKubernetesErrorMessage(e, nodeName);
            KubeVirtLog.error(listener, errorMsg);
            LOGGER.log(Level.SEVERE, errorMsg, e);
            agent.setProvisioningInfo(provisioningLog.toString());
            abortProvisioning(agent, listener, KubeVirtAgent.TerminationReason.LAUNCH_FAILURE);
            throw new ProvisioningException(nodeName, errorMsg, e);
        } catch (IOException e) {
            if (!inFlightDecremented) { cloud.decrementInFlightCount(templateName); }
            KubeVirtLog.error(listener, KubeVirtLog.messageOf(e));
            LOGGER.log(Level.SEVERE, "IO error during provisioning for " + nodeName, e);
            agent.setProvisioningInfo(provisioningLog.toString());
            abortProvisioning(agent, listener, KubeVirtAgent.TerminationReason.LAUNCH_FAILURE);
            throw e;
        } catch (InterruptedException e) {
            if (!inFlightDecremented) { cloud.decrementInFlightCount(templateName); }
            KubeVirtLog.error(listener, "Provisioning interrupted for " + nodeName);
            LOGGER.log(Level.WARNING, "Provisioning interrupted for " + nodeName, e);
            agent.setProvisioningInfo(provisioningLog.toString());
            // Do not count interruption against the retry limit; this is not a
            // provisioning failure (shutdown, reconnect abort, etc.).
            agent.tryMarkProvisioningFailureHandled();
            terminateAgent(agent);
            throw e;
        } catch (RuntimeException e) {
            if (!inFlightDecremented) { cloud.decrementInFlightCount(templateName); }
            KubeVirtLog.error(listener, "Unexpected error: " + KubeVirtLog.messageOf(e));
            LOGGER.log(Level.SEVERE, "Unexpected error during provisioning for " + nodeName, e);
            agent.setProvisioningInfo(provisioningLog.toString());
            abortProvisioning(agent, listener, KubeVirtAgent.TerminationReason.LAUNCH_FAILURE);
            throw new ProvisioningException(nodeName, "Unexpected error during provisioning: " + KubeVirtLog.messageOf(e), e);
        }
    }

    // ==================== VM Lifecycle Methods ====================
    // These are moved from KubeVirtProvisioningService to run inside launch()

    private void createVM(KubeVirt virt, KubeVirtTemplate template, String nodeName,
                          String cloudName, String cloudInitContent, ProvisioningCallback callback) {
        boolean injectTmpDiskConfig = template.isInjectTmpDiskConfig();
        String templateName = template.getName();
        String resourceLog = buildResourceLogMessage(template);

        if (template.isDataSourceImport()) {
            callback.log("Creating VM from DataSource Import: " + template.getImage());
            callback.log("DataSource: " + template.getDataSourceNamespace() + "/" + template.getDataSourceName());
            callback.log("Disk size: " + template.getDiskSize() + ", Access mode: " + template.getAccessMode() + resourceLog);

            if (template.getDataSourceNamespace() == null || template.getDataSourceNamespace().trim().isEmpty()) {
                throw new IllegalArgumentException("DataSource namespace is required but not configured");
            }
            if (template.getDataSourceName() == null || template.getDataSourceName().trim().isEmpty()) {
                throw new IllegalArgumentException("DataSource name is required but not configured");
            }

            virt.createVMFromDataSourceImport(
                    nodeName, cloudName, templateName,
                    template.getDataSourceNamespace(), template.getDataSourceName(),
                    template.getImage(), template.getDiskSize(), template.getAccessMode(),
                    template.getCpu(), template.getMemory(),
                    template.getCpuLimit(), template.getMemoryLimit(),
                    cloudInitContent != null ? cloudInitContent : "",
                    injectTmpDiskConfig, callback
            );
        } else if (template.isDataSource()) {
            callback.log("Creating VM from DataSource: " + template.getDataSourceNamespace() + "/" + template.getDataSourceName());
            callback.log("Disk size: " + template.getDiskSize() + ", Access mode: " + template.getAccessMode() + resourceLog);

            if (template.getDataSourceNamespace() == null || template.getDataSourceNamespace().trim().isEmpty()) {
                throw new IllegalArgumentException("DataSource namespace is required but not configured");
            }
            if (template.getDataSourceName() == null || template.getDataSourceName().trim().isEmpty()) {
                throw new IllegalArgumentException("DataSource name is required but not configured");
            }

            virt.createVMFromDataSource(
                    nodeName, cloudName, templateName,
                    template.getDataSourceNamespace(), template.getDataSourceName(),
                    template.getDiskSize(), template.getAccessMode(),
                    template.getCpu(), template.getMemory(),
                    template.getCpuLimit(), template.getMemoryLimit(),
                    cloudInitContent != null ? cloudInitContent : "",
                    injectTmpDiskConfig, callback
            );
        } else {
            callback.log("Creating VM from ContainerDisk: " + template.getImage());
            callback.log("Disk size: " + template.getDiskSize() + ", Access mode: " + template.getAccessMode() + resourceLog);
            virt.createVM(
                    nodeName, cloudName, templateName,
                    template.getImage(), template.getDiskSize(), template.getAccessMode(),
                    template.getCpu(), template.getMemory(),
                    template.getCpuLimit(), template.getMemoryLimit(),
                    cloudInitContent != null ? cloudInitContent : "",
                    injectTmpDiskConfig, callback
            );
        }
    }

    private String waitForVMReady(KubeVirt virt, KubeVirtTemplate template, String nodeName,
                                   String dvName, ProvisioningCallback callback)
            throws IOException, InterruptedException {
        final int retryIntervalSeconds = KubeVirtConfiguration.VM_PROVISIONING_RETRY_INTERVAL_SECONDS;
        final int timeoutMinutes = template.getProvisioningTimeoutMinutes();
        final int maxRetries = (timeoutMinutes * 60) / retryIntervalSeconds;
        final boolean needsIp = !template.isVirtctlSsh();

        callback.log("Waiting for VM to become ready (timeout: " + timeoutMinutes + " minutes)...");
        callback.log("Note: Disk provisioning may take several minutes depending on disk size and storage speed.");

        if (!needsIp) {
            callback.log("Using virtctl SSH - IP address detection not required.");
        }

        int attempt = 0;
        String ip = null;
        boolean vmReady = false;
        String lastStatus = "";
        String lastDvStatus = "";
        String lastNetworkDiag = "";

        while (attempt < maxRetries) {
            attempt++;
            String currentStatus = virt.getVMStatus(nodeName);

            if (!currentStatus.equals(lastStatus)) {
                callback.log("VM status: " + currentStatus);
                lastStatus = currentStatus;
            }

            if ("VM not found".equals(currentStatus)) {
                callback.log("ERROR: VM was deleted externally. Aborting provisioning.");
                throw new IOException("VM '" + nodeName + "' was deleted during provisioning. " +
                        "This may be due to manual deletion or resource quota issues.");
            }

            if (dvName != null) {
                KubeVirt.DataVolumeStatus dvStatus = virt.getDataVolumeStatus(dvName);
                String dvStatusStr = dvStatus.toString();

                if (!dvStatusStr.equals(lastDvStatus) || attempt % 6 == 0) {
                    callback.log("DataVolume status: " + dvStatusStr);
                    lastDvStatus = dvStatusStr;
                }

                if (dvStatus.isFailed()) {
                    callback.log("ERROR: DataVolume cloning failed!");
                    callback.log("DV Error: " + dvStatus.getMessage());
                    throw new IOException("DataVolume cloning failed for VM: " + nodeName +
                            ". Error: " + dvStatus.getMessage());
                }
            }

            if (virt.isVMReady(nodeName)) {
                vmReady = true;

                if (!needsIp) {
                    callback.log("VM is ready! (virtctl SSH mode - skipping IP detection)");
                    break;
                }

                ip = virt.getVMIp(nodeName);
                if (ip != null) {
                    callback.log("VM is ready! IP address: " + ip);
                    break;
                } else {
                    if (attempt % 6 == 0 || attempt == 1) {
                        String networkDiag = virt.getNetworkDiagnostics(nodeName);
                        if (!networkDiag.equals(lastNetworkDiag)) {
                            int elapsedSeconds = attempt * retryIntervalSeconds;
                            callback.log(String.format("Waiting for IP address... (%d/%d seconds, timeout: %d min)%n%s",
                                    elapsedSeconds, maxRetries * retryIntervalSeconds, timeoutMinutes, networkDiag));
                            lastNetworkDiag = networkDiag;
                        }
                    }
                }
            }

            try {
                Thread.sleep(retryIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                callback.log("Provisioning interrupted");
                throw e;
            }
        }

        if (!vmReady) {
            handleProvisioningTimeout(virt, template, nodeName, dvName, timeoutMinutes, false, callback);
        } else if (needsIp && ip == null) {
            handleProvisioningTimeout(virt, template, nodeName, dvName, timeoutMinutes, true, callback);
        }

        return ip;
    }

    private void handleProvisioningTimeout(KubeVirt virt, KubeVirtTemplate template, String nodeName,
                                            String dvName, int timeoutMinutes, boolean vmReadyButNoIp,
                                            ProvisioningCallback callback) throws ProvisioningException {
        callback.log("");
        callback.log("╔══════════════════════════════════════════════════════════════╗");
        callback.log("║  PROVISIONING TIMEOUT                                       ║");
        callback.log("╚══════════════════════════════════════════════════════════════╝");

        if (vmReadyButNoIp) {
            callback.log(String.format(
                    "VM is running but failed to obtain an IP address within the configured timeout of %d minute(s).",
                    timeoutMinutes));
            callback.log("Hint: For VMs without qemu-guest-agent, consider using 'virtctl SSH' connection type instead.");
        } else {
            callback.log(String.format(
                    "VM failed to reach Running state within the configured timeout of %d minute(s).",
                    timeoutMinutes));
        }
        callback.log(String.format(
                "Hint: If the VM needs more time to provision, increase 'Provisioning Timeout' in the template " +
                "'%s' configuration (current value: %d min).", template.getName(), timeoutMinutes));

        callback.log("");
        callback.log("=== DETAILED DIAGNOSTICS ===");
        String fullDiag = virt.getFullVMDiagnostics(nodeName);
        callback.log(fullDiag);

        if (dvName != null) {
            KubeVirt.DataVolumeStatus dvStatus = virt.getDataVolumeStatus(dvName);
            callback.log("\nDataVolume Final Status: " + dvStatus);
        }

        callback.log("");
        callback.log("The VM will be deleted. Please check the diagnostics above to resolve the issue.");

        String errorHints;
        if (vmReadyButNoIp) {
            errorHints = "1) qemu-guest-agent not installed in VM (required for IP detection with direct SSH), " +
                         "2) Network not configured properly, " +
                         "3) Consider using 'virtctl SSH' connection type which doesn't require IP detection";
        } else if (template.isDataSource()) {
            errorHints = "1) DataVolume cloning still in progress (check storage speed), " +
                         "2) Source DataSource doesn't exist, " +
                         "3) Storage class issues, " +
                         "4) Insufficient cluster resources";
        } else if (template.isDataSourceImport()) {
            errorHints = "1) DataSource import/creation still in progress, " +
                         "2) Container image not accessible from the cluster, " +
                         "3) DataVolume import timeout, " +
                         "4) Storage class issues, " +
                         "5) Insufficient cluster resources";
        } else {
            errorHints = "1) DataVolume import still in progress (check storage speed), " +
                         "2) Container image not accessible, " +
                         "3) Storage class issues, " +
                         "4) Insufficient cluster resources";
        }

        String errorMsg = vmReadyButNoIp
                ? String.format("Provisioning timeout: VM '%s' is running but failed to obtain IP address " +
                        "within %d minute(s)", nodeName, timeoutMinutes)
                : String.format("Provisioning timeout: VM '%s' failed to become ready " +
                        "within %d minute(s)", nodeName, timeoutMinutes);
        throw new ProvisioningException(nodeName,
                errorMsg + ". Increase 'Provisioning Timeout' in the template configuration if the VM needs more time. " +
                        "Common causes: " + errorHints,
                ErrorCode.PROVISIONING_TIMEOUT);
    }

    private void validateSshCredentials(KubeVirtTemplate template, String nodeName,
                                         ProvisioningCallback callback) throws IOException {
        String credId = template.getSshCredentialsId();
        try {
            var creds = SshCredentialHelper.lookupOrFail(credId);
            callback.log("SSH credentials verified: id=" + credId
                    + ", type=" + creds.getClass().getSimpleName()
                    + ", username=" + creds.getUsername());
        } catch (IOException e) {
            callback.log("ERROR: " + KubeVirtLog.messageOf(e));
            LOGGER.log(Level.SEVERE, "[{0}] {1}", new Object[]{nodeName, KubeVirtLog.messageOf(e)});
            throw e;
        }
    }

    // ==================== Helpers ====================

    private String buildResourceLogMessage(KubeVirtTemplate template) {
        StringBuilder sb = new StringBuilder();
        sb.append(", CPU: ").append(template.getCpu());
        if (template.getCpuLimit() != null && !template.getCpuLimit().isEmpty()) {
            sb.append(" (limit: ").append(template.getCpuLimit()).append(")");
        }
        sb.append(", Memory: ").append(template.getMemory());
        if (template.getMemoryLimit() != null && !template.getMemoryLimit().isEmpty()) {
            sb.append(" (limit: ").append(template.getMemoryLimit()).append(")");
        }
        return sb.toString();
    }

    private void logRetentionPolicy(KubeVirtTemplate template, ProvisioningCallback callback) {
        int idleMinutes = template.getIdleMinutes();
        String retentionDesc = idleMinutes == 0
                ? "single-use (disposed after first build)"
                : idleMinutes + " minute idle timeout";
        callback.log("Agent retention policy: " + retentionDesc);
    }

    private String buildKubernetesErrorMessage(KubernetesClientException e, String nodeName) {
        int statusCode = e.getCode();
        String baseMsg = String.format("Kubernetes API error while provisioning VM '%s'", nodeName);

        if (statusCode == 401) {
            return baseMsg + ": Authentication failed. Check that the credentials are valid and not expired.";
        } else if (statusCode == 403) {
            return baseMsg + ": Access denied. The credentials may not have sufficient permissions.";
        } else if (statusCode == 404) {
            return baseMsg + ": Resource not found. Verify the namespace exists and KubeVirt is installed.";
        } else if (statusCode == 409) {
            if (KubernetesClientExceptions.isResourceQuotaUpdateConflict(statusCode, e.getMessage())) {
                return baseMsg + ": Transient resource quota update conflict persisted after retries. "
                        + "Concurrent provisioning may be contending for ResourceQuota or ClusterResourceQuota.";
            }
            return baseMsg + ": Resource conflict. A VM with the same name may already exist.";
        } else if (statusCode == 422) {
            return baseMsg + ": Invalid resource specification. Check VM configuration.";
        } else if (statusCode >= 500) {
            return baseMsg + ": Server error (" + statusCode + "). The Kubernetes API server may be unavailable.";
        }
        return baseMsg + ": " + KubeVirtLog.messageOf(e);
    }

    /**
     * Looks up the KubeVirt cloud from Jenkins.
     */
    private KubeVirtCloud lookupCloud() {
        hudson.slaves.Cloud cloud = Jenkins.get().getCloud(cloudName);
        if (cloud instanceof KubeVirtCloud) {
            return (KubeVirtCloud) cloud;
        }
        throw new IllegalStateException("Cloud '" + cloudName + "' not found or is not a KubeVirtCloud");
    }

    /**
     * Looks up the template from the cloud by name.
     */
    private KubeVirtTemplate lookupTemplate(KubeVirtCloud cloud) {
        for (KubeVirtTemplate t : cloud.getTemplates()) {
            if (templateName.equals(t.getName())) {
                return t;
            }
        }
        throw new IllegalStateException("Template '" + templateName + "' not found in cloud '" + cloudName + "'");
    }

    /**
     * Logs which consecutive provisioning attempt this is, based on prior
     * failures for the template.
     */
    private void logProvisioningAttempt(ProvisioningCallback callback, KubeVirtTemplate template) {
        int attempt = ProvisioningFailureTracker.getFailureCount(template.getId()) + 1;
        if (template.hasUnlimitedProvisionRetries()) {
            callback.log("Provisioning attempt: " + attempt + " (unlimited retries)");
            return;
        }
        callback.log("Provisioning attempt: " + attempt + "/" + template.getAllowedProvisionFailures());
    }

    /**
     * Persists the agent's configuration to disk (e.g. after swapping the launcher).
     */
    @SuppressWarnings("deprecation") // Slave.save() is the correct API for persisting node config
    private void saveAgent(KubeVirtAgent agent) {
        try {
            agent.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist agent config for " + agent.getNodeName(), e);
        }
    }

    /**
     * Records the failure against the template retry limit, then terminates the
     * agent. Must run before {@link #terminateAgent} removes the node; Jenkins
     * {@code onLaunchFailure} cannot see the agent after that.
     */
    private void abortProvisioning(KubeVirtAgent agent, TaskListener listener,
                                   KubeVirtAgent.TerminationReason reason) {
        try {
            KubeVirtComputerListener.handleProvisioningFailure(agent, listener);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to enforce provisioning retry limit for agent " + agent.getNodeName(), e);
        }
        terminateAgent(agent, reason);
    }

    /**
     * Terminates the agent and its associated VM on failure with LAUNCH_FAILURE reason.
     * Errors are logged but not propagated to avoid masking the original failure.
     */
    private void terminateAgent(KubeVirtAgent agent) {
        terminateAgent(agent, KubeVirtAgent.TerminationReason.LAUNCH_FAILURE);
    }

    /**
     * Terminates the agent and its associated VM on failure with the specified reason.
     * Errors are logged but not propagated to avoid masking the original failure.
     */
    private void terminateAgent(KubeVirtAgent agent, KubeVirtAgent.TerminationReason reason) {
        try {
            LOGGER.log(Level.FINE, "Terminating agent {0} due to {1}", new Object[]{agent.getNodeName(), reason});
            agent.setTerminationReason(reason);
            agent.terminate();
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate agent " + agent.getNodeName(), e);
        }
    }

    // ==================== Disconnect hooks ====================

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.afterDisconnect(computer, listener);
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.beforeDisconnect(computer, listener);
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        // Not instantiated via the UI; provide a minimal descriptor
        return new DescriptorImpl();
    }

    private static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @Override
        public String getDisplayName() {
            return "KubeVirt Provisioning Launcher";
        }
    }
}
