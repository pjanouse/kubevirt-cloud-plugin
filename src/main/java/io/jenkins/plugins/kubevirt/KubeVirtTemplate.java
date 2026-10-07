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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Saveable;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.util.FormApply;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import javax.annotation.CheckForNull;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * KubeVirt VM Template configuration.
 * Defines the settings for provisioning virtual machine agents.
 */
public class KubeVirtTemplate implements Describable<KubeVirtTemplate>, Saveable {

    private String id;
    private final String name;
    private final String labelString;
    private final String diskSourceType;
    private final String image;
    private final String dataSourceNamespace;
    private final String dataSourceName;
    private final String diskSize;
    /** Kubernetes PVC access mode (ReadWriteOnce / ReadWriteMany), not a credential. */
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String accessMode;
    
    // Resource configuration - reserved (requests)
    private final String cpu;
    private final String memory;
    
    // Resource configuration - limits
    private final String cpuLimit;
    private final String memoryLimit;
    
    // SSH configuration
    private final String connectionType;
    @SuppressFBWarnings(value = "PA_PASSWORD_SAVED_IN_PLAIN_TEXT",
            justification = "Jenkins credentials identifier, not a password or secret value")
    private final String sshCredentialsId;
    private final int sshPort;
    private final String remoteFS;
    private final String javaPath;
    
    // Cloud-init configuration
    private final String cloudInitId;
    private final Integer cloudInitWaitSeconds;
    
    // Agent retention configuration
    // Using Integer (nullable) to distinguish between "not set" (null/old config) and "explicitly set to 0"
    private final Integer idleMinutes;

    // Capacity limit for this template
    // Using Integer (nullable) - null or 0 means unlimited
    private final Integer instanceCap;

    // VM provisioning timeout configuration
    private final Integer provisioningTimeoutMinutes;

    // Extra VM provisioning tries after the first failure (-1 = unlimited, 0 = none)
    private final Integer maxProvisionAttempts;

    // Inject /tmp disk configuration into cloud-init
    private final boolean injectTmpDiskConfig;

    // Orphan cleanup grace period (in minutes)
    // How long a VM must exist without a registered agent before being considered orphaned
    private final Integer orphanGracePeriodMinutes;

    private transient Set<LabelAtom> labelSet;

    @DataBoundConstructor
    public KubeVirtTemplate(String id, String name, String labels, String diskSourceType,
                            String image, String dataSourceNamespace,
                            String dataSourceName, String diskSize,
                            String accessMode,
                            String cpu, String memory,
                            String cpuLimit, String memoryLimit,
                            String connectionType, String sshCredentialsId,
                            String sshPort, String remoteFS, String javaPath,
                            String cloudInitId, String idleMinutes,
                            String instanceCap, String provisioningTimeoutMinutes,
                            String maxProvisionAttempts,
                            boolean injectTmpDiskConfig, String orphanGracePeriodMinutes,
                            String cloudInitWaitSeconds) {
        // Use provided ID or generate a new one
        this.id = Util.fixEmpty(id) != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.labelString = labels;
        this.diskSourceType = diskSourceType != null ? diskSourceType : KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK;
        this.image = image;
        this.dataSourceNamespace = (dataSourceNamespace != null && !dataSourceNamespace.isEmpty())
                ? dataSourceNamespace : KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        this.dataSourceName = dataSourceName;
        this.diskSize = diskSize != null && !diskSize.isEmpty() ? diskSize : KubeVirtConfiguration.DEFAULT_DISK_SIZE;
        this.accessMode = accessMode != null && !accessMode.isEmpty() ? accessMode : KubeVirtConfiguration.ACCESS_MODE_RWX;
        this.cpu = (cpu != null && !cpu.isEmpty()) ? cpu : KubeVirtConfiguration.DEFAULT_CPU;
        this.memory = (memory != null && !memory.isEmpty()) ? memory : KubeVirtConfiguration.DEFAULT_MEMORY;
        this.cpuLimit = cpuLimit;
        this.memoryLimit = memoryLimit;

        // SSH configuration with defaults
        this.connectionType = connectionType != null ? connectionType : KubeVirtConfiguration.CONNECTION_DIRECT_SSH;
        this.sshCredentialsId = sshCredentialsId;
        this.sshPort = parsePort(sshPort);
        this.remoteFS = (remoteFS != null && !remoteFS.isEmpty()) ? remoteFS : KubeVirtConfiguration.DEFAULT_REMOTE_FS;
        this.javaPath = (javaPath != null) ? javaPath : KubeVirtConfiguration.DEFAULT_JAVA_PATH;

        // Cloud-init configuration
        this.cloudInitId = Util.fixEmpty(cloudInitId);
        this.cloudInitWaitSeconds = parseCloudInitWaitSeconds(cloudInitWaitSeconds);

        // Agent retention configuration
        this.idleMinutes = parseIdleMinutes(idleMinutes);

        // Capacity limit
        this.instanceCap = parseInstanceCap(instanceCap);

        // VM provisioning timeout configuration
        this.provisioningTimeoutMinutes = parseProvisioningTimeoutMinutes(provisioningTimeoutMinutes);

        this.maxProvisionAttempts = parseMaxProvisionAttempts(maxProvisionAttempts);

        // Inject /tmp disk configuration
        this.injectTmpDiskConfig = injectTmpDiskConfig;

        // Orphan cleanup grace period
        this.orphanGracePeriodMinutes = parseOrphanGracePeriodMinutes(orphanGracePeriodMinutes);

        this.labelSet = Label.parse(labels);
    }
    
    private static int parsePort(String port) {
        if (port == null || port.isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_SSH_PORT;
        }
        try {
            int p = Integer.parseInt(port.trim());
            return (p > 0 && p <= 65535) ? p : KubeVirtConfiguration.DEFAULT_SSH_PORT;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_SSH_PORT;
        }
    }

    private static Integer parseIdleMinutes(String idleMinutes) {
        if (idleMinutes == null || idleMinutes.isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
        }
        try {
            int minutes = Integer.parseInt(idleMinutes.trim());
            return minutes >= 0 ? minutes : KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
        }
    }

    /**
     * Parses the instance cap string.
     *
     * @param instanceCap The instance cap as a string
     * @return The parsed value, or default if empty/invalid. 0 means unlimited.
     */
    private static Integer parseInstanceCap(String instanceCap) {
        if (instanceCap == null || instanceCap.trim().isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP;
        }
        try {
            int cap = Integer.parseInt(instanceCap.trim());
            if (cap < 0) {
                return KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP;
            }
            return cap; // 0 means unlimited, positive means that limit
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP;
        }
    }

    private static Integer parseProvisioningTimeoutMinutes(String provisioningTimeoutMinutes) {
        if (provisioningTimeoutMinutes == null || provisioningTimeoutMinutes.isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES;
        }
        try {
            int minutes = Integer.parseInt(provisioningTimeoutMinutes.trim());
            return minutes >= 1 ? minutes : KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES;
        }
    }

    /**
     * Parses the provisioning retry count.
     *
     * @return {@code -1} for unlimited, {@code 0} or greater for that many retries,
     *         or the default if empty/invalid. Values below {@code -1} are rejected.
     */
    private static Integer parseMaxProvisionAttempts(String maxProvisionAttempts) {
        if (maxProvisionAttempts == null || maxProvisionAttempts.trim().isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS;
        }
        try {
            int retries = Integer.parseInt(maxProvisionAttempts.trim());
            return retries >= KubeVirtConfiguration.UNLIMITED_PROVISION_RETRIES
                    ? retries
                    : KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS;
        }
    }

    private static Integer parseCloudInitWaitSeconds(String cloudInitWaitSeconds) {
        if (cloudInitWaitSeconds == null || cloudInitWaitSeconds.isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(cloudInitWaitSeconds.trim());
            return seconds >= 1 ? seconds : KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
        }
    }

    private static Integer parseOrphanGracePeriodMinutes(String orphanGracePeriodMinutes) {
        if (orphanGracePeriodMinutes == null || orphanGracePeriodMinutes.isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
        }
        try {
            int minutes = Integer.parseInt(orphanGracePeriodMinutes.trim());
            return minutes >= 1 ? minutes : KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
        }
    }

    public String getName() {
        return name;
    }

    public String getLabels() {
        return labelString;
    }

    public String getDiskSourceType() {
        return diskSourceType;
    }

    public boolean isContainerDisk() {
        return KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK.equals(diskSourceType);
    }

    public boolean isDataSource() {
        return KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE.equals(diskSourceType);
    }

    public boolean isDataSourceImport() {
        return KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType);
    }

    public String getImage() {
        return image;
    }

    public String getDataSourceNamespace() {
        return dataSourceNamespace;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public String getDiskSize() {
        return diskSize;
    }

    public String getAccessMode() {
        return accessMode != null ? accessMode : KubeVirtConfiguration.ACCESS_MODE_RWX;
    }

    public String getCpu() {
        return cpu;
    }

    public String getMemory() {
        return memory;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }
    
    public String getConnectionType() {
        return connectionType;
    }
    
    public boolean isDirectSsh() {
        return KubeVirtConfiguration.CONNECTION_DIRECT_SSH.equals(connectionType) || connectionType == null;
    }

    public boolean isVirtctlSsh() {
        return KubeVirtConfiguration.CONNECTION_VIRTCTL_SSH.equals(connectionType);
    }
    
    public String getSshCredentialsId() {
        return sshCredentialsId;
    }
    
    public int getSshPort() {
        return sshPort;
    }
    
    public String getRemoteFS() {
        return remoteFS;
    }
    
    public String getJavaPath() {
        return javaPath;
    }
    
    public @CheckForNull String getCloudInitId() {
        return cloudInitId;
    }
    
    /**
     * Resolves and returns the cloud-init content from the configured config file.
     *
     * @return The cloud-init content, or null if not configured or empty
     */
    public @CheckForNull String getCloudInitContent() {
        return CloudInitConfig.resolve(cloudInitId);
    }
    
    /**
     * Gets the idle timeout in minutes before the agent is terminated.
     * A value of 0 means the VM will be disposed immediately after the first build completes.
     * Returns the default value (10 minutes) for old configurations that don't have this field.
     *
     * @return The idle timeout in minutes (0 or greater)
     */
    public int getIdleMinutes() {
        return idleMinutes != null ? idleMinutes : KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
    }

    /**
     * Gets the instance cap as configured (for UI binding).
     * Returns the raw value: 0 means unlimited, positive number means that limit.
     *
     * @return The configured instance cap value
     */
    public int getInstanceCap() {
        return instanceCap != null ? instanceCap : KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP;
    }

    /**
     * Gets the effective instance cap for provisioning logic.
     * Returns {@link KubeVirtConfiguration#UNLIMITED_CAPACITY} if set to 0 (unlimited).
     *
     * @return The effective instance cap, or UNLIMITED_CAPACITY if no limit
     */
    public int getEffectiveInstanceCap() {
        int cap = getInstanceCap();
        return cap == 0 ? KubeVirtConfiguration.UNLIMITED_CAPACITY : cap;
    }

    /**
     * Gets the VM provisioning timeout in minutes.
     * This is the maximum time to wait for the VM to be ready and get an IP address.
     * Returns the default value (15 minutes) for old configurations that don't have this field.
     *
     * @return The provisioning timeout in minutes (1 or greater)
     */
    public int getProvisioningTimeoutMinutes() {
        return provisioningTimeoutMinutes != null ? provisioningTimeoutMinutes : KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES;
    }

    /**
     * Gets the number of extra VM provisioning tries after the first failure.
     * Queued builds waiting for this template's label are cancelled once the
     * retry budget is exhausted.
     *
     * @return {@code -1} for unlimited retries, {@code 0} for no retries,
     *         or a positive retry count
     */
    public int getMaxProvisionAttempts() {
        return maxProvisionAttempts != null ? maxProvisionAttempts : KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS;
    }

    /**
     * @return {@code true} if provisioning may be retried indefinitely
     */
    public boolean hasUnlimitedProvisionRetries() {
        return getMaxProvisionAttempts() < 0;
    }

    /**
     * Consecutive failures allowed before cancelling queued builds (retries + 1).
     * Only meaningful when retries are limited.
     *
     * @return the failure budget, at least 1 when retries are limited
     */
    public int getAllowedProvisionFailures() {
        return getMaxProvisionAttempts() + 1;
    }

    /**
     * Gets the maximum time to wait for cloud-init to complete SSH setup.
     * Used by virtctl SSH to poll for sshd before attempting agent connection.
     *
     * @return The cloud-init wait timeout in seconds (1 or greater)
     */
    public int getCloudInitWaitSeconds() {
        return cloudInitWaitSeconds != null ? cloudInitWaitSeconds : KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
    }

    /**
     * Gets whether to inject /tmp disk configuration into cloud-init.
     * When enabled, a 10Gi ephemeral disk is attached and mounted at /tmp via cloud-init runcmd.
     * This provides faster /tmp storage for build operations.
     *
     * @return true if /tmp disk configuration should be injected
     */
    public boolean isInjectTmpDiskConfig() {
        return injectTmpDiskConfig;
    }

    /**
     * Gets the orphan grace period in minutes.
     * A VM without a registered Jenkins agent will not be considered orphaned
     * until it has existed for at least this many minutes.
     * This allows time for VM boot, cloud-init, and agent registration.
     *
     * @return The orphan grace period in minutes (1 or greater)
     */
    public int getOrphanGracePeriodMinutes() {
        return orphanGracePeriodMinutes != null ? orphanGracePeriodMinutes : KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
    }

    public boolean canProvision(Label label) {
        return label == null || label.matches(labelSet);
    }

    // ==================== ID and Template Management ====================

    /**
     * Gets the unique ID of this template.
     * @return the template ID
     */
    @NonNull
    public String getId() {
        return id;
    }

    /**
     * Checks if the current user has permission to manage templates.
     * @return true if the user has manage permission
     */
    @Restricted(DoNotUse.class) // Used by jelly
    public boolean hasManagePermission() {
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request != null) {
            VMTemplateGroup groupFromRequest = request.findAncestorObject(VMTemplateGroup.class);
            if (groupFromRequest != null) {
                return groupFromRequest.hasManagePermission();
            }
        }
        return Jenkins.get().hasPermission(Jenkins.MANAGE);
    }

    /**
     * Gets the manage permission for this template.
     * @return the permission required to manage templates
     */
    public Permission getManagePermission() {
        return Jenkins.MANAGE;
    }

    /**
     * Deletes the template via the UI.
     */
    @POST
    public HttpResponse doDoDelete(@AncestorInPath VMTemplateGroup owner) throws IOException {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        Jenkins j = Jenkins.get();
        owner.checkManagePermission();
        owner.removeTemplate(this);
        j.save();
        // take the user back to templates list
        return new HttpRedirect(owner.getVMTemplateGroupUrl());
    }

    /**
     * Saves the template configuration via the UI.
     */
    @POST
    public HttpResponse doConfigSubmit(StaplerRequest2 req, @AncestorInPath VMTemplateGroup owner)
            throws IOException, ServletException, Descriptor.FormException {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        Jenkins j = Jenkins.get();
        owner.checkManagePermission();
        KubeVirtTemplate newTemplate = reconfigure(req, req.getSubmittedForm());
        owner.replaceTemplate(this, newTemplate);
        j.save();
        // take the user back to templates list
        return FormApply.success(owner.getVMTemplateGroupUrl());
    }

    private KubeVirtTemplate reconfigure(@NonNull final StaplerRequest2 req, net.sf.json.JSONObject form)
            throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        return getDescriptor().newInstance(req, form);
    }

    /**
     * Empty implementation of Saveable interface.
     */
    @Override
    public void save() {
        // No-op - saving is handled by Jenkins.save() in the controller methods
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<KubeVirtTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<KubeVirtTemplate> {
        @Override
        public String getDisplayName() {
            return "KubeVirt Template";
        }
        // ---- Default value getters for Jelly (used via ${descriptor.defaultXxx}) ----

        public String getDefaultDataSourceNamespace() {
            return KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        }

        public String getDefaultDiskSize() {
            return KubeVirtConfiguration.DEFAULT_DISK_SIZE;
        }

        public String getDefaultCpu() {
            return KubeVirtConfiguration.DEFAULT_CPU;
        }

        public String getDefaultMemory() {
            return KubeVirtConfiguration.DEFAULT_MEMORY;
        }

        public boolean getDefaultInjectTmpDisk() {
            return KubeVirtConfiguration.DEFAULT_INJECT_TMP_DISK;
        }

        public int getDefaultSshPort() {
            return KubeVirtConfiguration.DEFAULT_SSH_PORT;
        }

        public String getDefaultRemoteFS() {
            return KubeVirtConfiguration.DEFAULT_REMOTE_FS;
        }

        public int getDefaultIdleMinutes() {
            return KubeVirtConfiguration.DEFAULT_IDLE_MINUTES;
        }

        public int getDefaultInstanceCap() {
            return KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP;
        }

        public int getDefaultOrphanGracePeriodMinutes() {
            return KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
        }

        public int getDefaultProvisioningTimeoutMinutes() {
            return KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES;
        }

        public int getDefaultMaxProvisionAttempts() {
            return KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS;
        }

        public int getDefaultCloudInitWaitSeconds() {
            return KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
        }

        public ListBoxModel doFillDiskSourceTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("ContainerDisk", KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK);
            items.add("DataSource (Clone)", KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE);
            items.add("DataSource (Import)", KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT);
            return items;
        }

        public ListBoxModel doFillAccessModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("ReadWriteMany (RWX) - Supports live migration", KubeVirtConfiguration.ACCESS_MODE_RWX);
            items.add("ReadWriteOnce (RWO) - Single node only", KubeVirtConfiguration.ACCESS_MODE_RWO);
            return items;
        }

        @POST
        public FormValidation doCheckName(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Template Name is required. This will be used in the VM name.");
            }
            // Check for valid Kubernetes name characters
            String sanitized = value.trim().toLowerCase().replaceAll("[^a-z0-9\\-.]", "-");
            if (!sanitized.equals(value.trim().toLowerCase())) {
                return FormValidation.warning("Name contains invalid characters. Will be sanitized to: " + sanitized);
            }
            return FormValidation.ok();
        }
        
        public ListBoxModel doFillConnectionTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Direct SSH (requires network access to VM)", KubeVirtConfiguration.CONNECTION_DIRECT_SSH);
            items.add("virtctl SSH (tunneled via Kubernetes API)", KubeVirtConfiguration.CONNECTION_VIRTCTL_SSH);
            return items;
        }
        
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath ItemGroup<?> context) {
            if (context == null) {
                context = Jenkins.get();
            }
            if (context instanceof AccessControlled) {
                if (!((AccessControlled) context).hasPermission(Jenkins.ADMINISTER)) {
                    return new StandardListBoxModel().includeCurrentValue("");
                }
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StandardUsernameCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always()
                    );
        }
        
        @POST
        public FormValidation doCheckSshCredentialsId(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("SSH Credentials are required to connect to the VM.");
            }
            return FormValidation.ok();
        }

        /**
         * Validates the CPU field (reserved/requests).
         * Accepts Kubernetes CPU format: integer cores (e.g., "2") or millicores (e.g., "500m").
         */
        @POST
        public FormValidation doCheckCpu(@QueryParameter String value,
                                          @QueryParameter String cpuLimit) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("CPU is required.");
            }

            String cpu = value.trim();

            // Validate Kubernetes CPU format: number or number with 'm' suffix
            if (!cpu.matches("^[0-9]+m?$")) {
                return FormValidation.error(
                        "Invalid CPU format. Use number of cores (e.g., '2') or millicores (e.g., '500m').");
            }

            // Parse and validate the value
            try {
                int cpuMillicores = parseCpuToMillicores(cpu);
                
                if (cpuMillicores < 100) {
                    return FormValidation.warning(
                            "CPU allocation is very low (" + cpuMillicores + "m). Consider at least 500m for Jenkins agents.");
                }

                // Cross-validate against limit if provided
                if (cpuLimit != null && !cpuLimit.trim().isEmpty()) {
                    try {
                        int limitMillicores = parseCpuToMillicores(cpuLimit.trim());
                        if (cpuMillicores > limitMillicores) {
                            return FormValidation.error(
                                    "Reserved CPU (" + cpu + " = " + cpuMillicores + "m) cannot exceed CPU limit (" 
                                    + cpuLimit.trim() + " = " + limitMillicores + "m).");
                        }
                    } catch (NumberFormatException e) {
                        // Limit has invalid format, will be caught by its own validator
                    }
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid CPU value: " + cpu);
            }

            return FormValidation.ok();
        }

        /**
         * Validates the Memory field (reserved/requests).
         * Accepts Kubernetes memory format: number with suffix (Ki, Mi, Gi, Ti, K, M, G, T).
         */
        @POST
        public FormValidation doCheckMemory(@QueryParameter String value,
                                             @QueryParameter String memoryLimit) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Memory is required.");
            }

            String memory = value.trim();

            // Validate Kubernetes memory format
            if (!memory.matches("^[0-9]+([KMGT]i?)?$")) {
                return FormValidation.error(
                        "Invalid memory format. Use Kubernetes format (e.g., '2Gi', '512Mi', '4G').");
            }

            // Parse and validate the value
            try {
                long memoryBytes = parseMemoryToBytes(memory);
                long minMemory = 512L * 1024 * 1024; // 512Mi

                if (memoryBytes < minMemory) {
                    return FormValidation.warning(
                            "Memory allocation is low. Consider at least 512Mi for Jenkins agents.");
                }

                // Cross-validate against limit if provided
                if (memoryLimit != null && !memoryLimit.trim().isEmpty()) {
                    try {
                        long limitBytes = parseMemoryToBytes(memoryLimit.trim());
                        if (memoryBytes > limitBytes) {
                            return FormValidation.error(
                                    "Reserved memory (" + memory + ") cannot exceed memory limit (" + memoryLimit.trim() + ").");
                        }
                    } catch (NumberFormatException e) {
                        // Limit has invalid format, will be caught by its own validator
                    }
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid memory value: " + memory);
            }

            return FormValidation.ok();
        }

        /**
         * Validates the CPU Limit field.
         * Accepts Kubernetes CPU format: integer cores (e.g., "2") or millicores (e.g., "500m").
         */
        @POST
        public FormValidation doCheckCpuLimit(@QueryParameter String value,
                                              @QueryParameter String cpu) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("No limit set (VM can use all available CPU)");
            }

            String cpuLimit = value.trim();

            // Validate Kubernetes CPU format: number or number with 'm' suffix
            if (!cpuLimit.matches("^[0-9]+m?$")) {
                return FormValidation.error(
                        "Invalid CPU format. Use number of cores (e.g., '2') or millicores (e.g., '500m').");
            }

            // Parse and validate the value
            try {
                int limitMillicores = parseCpuToMillicores(cpuLimit);

                // Cross-validate against reserved CPU if provided
                if (cpu != null && !cpu.trim().isEmpty()) {
                    try {
                        int reservedMillicores = parseCpuToMillicores(cpu.trim());
                        if (limitMillicores < reservedMillicores) {
                            return FormValidation.error(
                                    "CPU limit (" + cpuLimit + " = " + limitMillicores + "m) must be >= reserved CPU (" 
                                    + cpu.trim() + " = " + reservedMillicores + "m).");
                        }
                    } catch (NumberFormatException e) {
                        // Reserved has invalid format, will be caught by its own validator
                    }
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid CPU value: " + cpuLimit);
            }

            return FormValidation.ok();
        }

        /**
         * Validates the Memory Limit field.
         * Accepts Kubernetes memory format: number with suffix (Ki, Mi, Gi, Ti, K, M, G, T).
         */
        @POST
        public FormValidation doCheckMemoryLimit(@QueryParameter String value,
                                                  @QueryParameter String memory) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("No limit set (VM can use all available memory)");
            }

            String memoryLimit = value.trim();

            // Validate Kubernetes memory format
            if (!memoryLimit.matches("^[0-9]+([KMGT]i?)?$")) {
                return FormValidation.error(
                        "Invalid memory format. Use Kubernetes format (e.g., '2Gi', '512Mi', '4G').");
            }

            // Parse and validate the value
            try {
                long limitBytes = parseMemoryToBytes(memoryLimit);

                // Cross-validate against reserved memory if provided
                if (memory != null && !memory.trim().isEmpty()) {
                    try {
                        long reservedBytes = parseMemoryToBytes(memory.trim());
                        if (limitBytes < reservedBytes) {
                            return FormValidation.error(
                                    "Memory limit (" + memoryLimit + ") must be >= reserved memory (" + memory.trim() + ").");
                        }
                    } catch (NumberFormatException e) {
                        // Reserved has invalid format, will be caught by its own validator
                    }
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid memory value: " + memoryLimit);
            }

            return FormValidation.ok();
        }

        /**
         * Parses a Kubernetes CPU string to millicores.
         * @param cpu CPU value in Kubernetes format (e.g., "1", "500m", "2")
         * @return The CPU value in millicores
         */
        private int parseCpuToMillicores(String cpu) {
            if (cpu.endsWith("m")) {
                return Integer.parseInt(cpu.substring(0, cpu.length() - 1));
            } else {
                return Integer.parseInt(cpu) * 1000;
            }
        }

        /**
         * Parses a Kubernetes memory string to bytes.
         */
        private long parseMemoryToBytes(String memory) {
            if (memory.endsWith("Ti")) {
                return Long.parseLong(memory.replace("Ti", "")) * 1024L * 1024 * 1024 * 1024;
            } else if (memory.endsWith("Gi")) {
                return Long.parseLong(memory.replace("Gi", "")) * 1024L * 1024 * 1024;
            } else if (memory.endsWith("Mi")) {
                return Long.parseLong(memory.replace("Mi", "")) * 1024L * 1024;
            } else if (memory.endsWith("Ki")) {
                return Long.parseLong(memory.replace("Ki", "")) * 1024L;
            } else if (memory.endsWith("T")) {
                return Long.parseLong(memory.replace("T", "")) * 1000L * 1000 * 1000 * 1000;
            } else if (memory.endsWith("G")) {
                return Long.parseLong(memory.replace("G", "")) * 1000L * 1000 * 1000;
            } else if (memory.endsWith("M")) {
                return Long.parseLong(memory.replace("M", "")) * 1000L * 1000;
            } else if (memory.endsWith("K")) {
                return Long.parseLong(memory.replace("K", "")) * 1000L;
            } else {
                return Long.parseLong(memory);
            }
        }

        /**
         * Validates the Disk Size field.
         * Accepts Kubernetes storage format: number with suffix (Ki, Mi, Gi, Ti, K, M, G, T).
         */
        @POST
        public FormValidation doCheckDiskSize(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                // Will use default, so this is OK
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_DISK_SIZE);
            }

            String diskSize = value.trim();

            // Validate Kubernetes storage format
            if (!diskSize.matches("^[0-9]+([KMGT]i?)?$")) {
                return FormValidation.error(
                        "Invalid disk size format. Use Kubernetes format (e.g., '30Gi', '100G').");
            }

            // Parse and validate the value
            try {
                long sizeBytes = parseMemoryToBytes(diskSize);
                long minSize = 10L * 1024 * 1024 * 1024; // 10Gi

                if (sizeBytes < minSize) {
                    return FormValidation.warning(
                            "Disk size is small. Consider at least 10Gi for OS and Jenkins workspace.");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid disk size value: " + diskSize);
            }

            return FormValidation.ok();
        }

        /**
         * Validates the SSH Port field.
         */
        @POST
        public FormValidation doCheckSshPort(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("Will use default port: " + KubeVirtConfiguration.DEFAULT_SSH_PORT);
            }

            try {
                int port = Integer.parseInt(value.trim());
                if (port < 1 || port > 65535) {
                    return FormValidation.error("Port must be between 1 and 65535.");
                }
                if (port < 1024) {
                    return FormValidation.warning("Using a privileged port (<1024). Ensure the VM allows this.");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid port number.");
            }

            return FormValidation.ok();
        }

        /**
         * Validates the Remote FS field.
         */
        @POST
        public FormValidation doCheckRemoteFS(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_REMOTE_FS);
            }

            String remoteFS = value.trim();
            if (!remoteFS.startsWith("/")) {
                return FormValidation.error("Remote filesystem path must be an absolute path starting with '/'.");
            }

            return FormValidation.ok();
        }

        /**
         * Validates the Container Disk Image field.
         */
        @POST
        public FormValidation doCheckImage(@QueryParameter String value,
                                           @QueryParameter String diskSourceType) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }            // Only required for ContainerDisk and DataSource Import
            if (!KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK.equals(diskSourceType)
                    && !KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)) {
                return FormValidation.ok();
            }

            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Container disk image is required for " +
                        (KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)
                                ? "DataSource (Import)" : "ContainerDisk") + " source type.");
            }

            String image = value.trim();
            // Basic image format validation
            if (!image.matches("^[a-zA-Z0-9][a-zA-Z0-9._/-]*(:[a-zA-Z0-9._-]+)?(@sha256:[a-f0-9]+)?$")) {
                return FormValidation.warning(
                        "Image format may be invalid. Expected format: registry/image:tag or registry/image@sha256:digest");
            }

            return FormValidation.ok();
        }

        /**
         * Validates the DataSource Name field.
         */
        @POST
        public FormValidation doCheckDataSourceName(@QueryParameter String value,
                                                     @QueryParameter String diskSourceType) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }            // Required for DataSource (Clone) and DataSource (Import)
            if (!KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE.equals(diskSourceType)
                    && !KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)) {
                return FormValidation.ok();
            }

            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("DataSource name is required for " +
                        (KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)
                                ? "DataSource (Import)" : "DataSource (Clone)") + " source type.");
            }

            // Validate RFC 1123 DNS subdomain name
            String name = value.trim();
            if (!name.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
                return FormValidation.error(
                        "DataSource name must be a valid Kubernetes name (lowercase, alphanumeric, hyphens).");
            }

            if (name.length() > 253) {
                return FormValidation.error("DataSource name must be 253 characters or less.");
            }

            return FormValidation.ok();
        }

        /**
         * Validates the DataSource Namespace field.
         */
        @POST
        public FormValidation doCheckDataSourceNamespace(@QueryParameter String value,
                                                          @QueryParameter String diskSourceType) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }            // Required for DataSource (Clone) and DataSource (Import)
            if (!KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE.equals(diskSourceType)
                    && !KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)) {
                return FormValidation.ok();
            }

            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("DataSource namespace is required for " +
                        (KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(diskSourceType)
                                ? "DataSource (Import)" : "DataSource (Clone)") + " source type.");
            }

            // Validate RFC 1123 DNS label
            String namespace = value.trim();
            if (!namespace.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
                return FormValidation.error(
                        "Namespace must be a valid Kubernetes namespace (lowercase, alphanumeric, hyphens).");
            }

            if (namespace.length() > 63) {
                return FormValidation.error("Namespace must be 63 characters or less.");
            }

            return FormValidation.ok();
        }
        
        /**
         * Populates the cloud-init config dropdown with available configs.
         */
        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillCloudInitIdItems() {
            ListBoxModel items = new ListBoxModel();
            if (!KubeVirtStaplerSecurity.canManage()) {
                return items;
            }
            items.add("-- None --", "");
            for (Config config : ConfigFiles.getConfigsInContext(
                    Jenkins.get(), CloudInitConfig.CloudInitConfigProvider.class)) {
                items.add(config.name, config.id);
            }
            return items;
        }
        
        /**
         * Validates the selected cloud-init config and provides a link to edit it.
         */
        @POST
        public FormValidation doCheckCloudInitId(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.warning(
                    "No cloud-init config selected. VM will boot without cloud-init customization. " +
                    "SSH key injection requires a cloud-init config.");
            }
            
            Config config = ConfigFiles.getByIdOrNull(Jenkins.get(), value);
            if (config == null) {
                return FormValidation.error("Selected config no longer exists: " + value);
            }
            
            // Provide a link to edit the config
            return FormValidation.okWithMarkup(
                "<a target='_blank' href='" + Jenkins.get().getRootUrl() 
                + "configfiles/editConfig?id=" + Util.escape(value) + "'>Edit: " 
                + Util.escape(config.name) + "</a>");
        }
        
        /**
         * Validates the idle minutes field.
         */
        @POST
        public FormValidation doCheckIdleMinutes(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.ok(); // Will use default
            }
            try {
                int minutes = Integer.parseInt(value.trim());
                if (minutes < 0) {
                    return FormValidation.error("Idle time must be 0 or greater.");
                }
                if (minutes == 0) {
                    return FormValidation.ok("VM will be disposed immediately after the first build completes.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        /**
         * Validates the instance cap field.
         */
        @POST
        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_TEMPLATE_INSTANCE_CAP);
            }
            try {
                int cap = Integer.parseInt(value.trim());
                if (cap < 0) {
                    return FormValidation.error("Instance cap cannot be negative.");
                }
                if (cap == 0) {
                    return FormValidation.ok("Unlimited (no cap)");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        /**
         * Validates the provisioning timeout minutes field.
         */
        @POST
        public FormValidation doCheckProvisioningTimeoutMinutes(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_PROVISIONING_TIMEOUT_MINUTES + " minutes");
            }
            try {
                int minutes = Integer.parseInt(value.trim());
                if (minutes < 1) {
                    return FormValidation.error("Provisioning timeout must be at least 1 minute.");
                }
                if (minutes < 5) {
                    return FormValidation.warning("Short timeout may cause failures if disk cloning takes longer than expected.");
                }
                if (minutes > 60) {
                    return FormValidation.warning("Very long timeout. Consider investigating if provisioning typically takes this long.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        /**
         * Validates the max provision attempts field (retry count).
         */
        @POST
        public FormValidation doCheckMaxProvisionAttempts(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.ok("Will use default: "
                        + KubeVirtConfiguration.DEFAULT_MAX_PROVISION_ATTEMPTS
                        + " retry, then fail the build.");
            }
            try {
                int retries = Integer.parseInt(value.trim());
                if (retries < KubeVirtConfiguration.UNLIMITED_PROVISION_RETRIES) {
                    return FormValidation.error("Use -1 for unlimited retries, or 0 or greater for a retry limit.");
                }
                if (retries == KubeVirtConfiguration.UNLIMITED_PROVISION_RETRIES) {
                    return FormValidation.warning("Unlimited provisioning retries. Failed builds may spawn VMs indefinitely.");
                }
                if (retries == 0) {
                    return FormValidation.ok("No retries. Fail the build after the first provisioning failure.");
                }
                if (retries == 1) {
                    return FormValidation.ok("One retry. Fail the build after 2 consecutive provisioning failures.");
                }
                return FormValidation.ok(retries + " retries. Fail the build after "
                        + (retries + 1) + " consecutive provisioning failures.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        /**
         * Validates the cloud-init wait seconds field.
         */
        @POST
        public FormValidation doCheckCloudInitWaitSeconds(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS + " seconds");
            }
            try {
                int seconds = Integer.parseInt(value.trim());
                if (seconds < 1) {
                    return FormValidation.error("Cloud-init wait timeout must be at least 1 second.");
                }
                if (seconds < 60) {
                    return FormValidation.warning("Short timeout may cause failures if cloud-init installs packages or runs long runcmd scripts.");
                }
                if (seconds > 3600) {
                    return FormValidation.warning("Very long timeout. Consider optimizing cloud-init instead.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        /**
         * Validates the orphan grace period minutes field.
         */
        @POST
        public FormValidation doCheckOrphanGracePeriodMinutes(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES + " minutes");
            }
            try {
                int minutes = Integer.parseInt(value.trim());
                if (minutes < 1) {
                    return FormValidation.error("Orphan grace period must be at least 1 minute.");
                }
                if (minutes < 15) {
                    return FormValidation.warning("Short grace period may delete VMs that are still starting up.");
                }
                if (minutes > 120) {
                    return FormValidation.warning("Long grace period means orphaned VMs will consume resources for longer before cleanup.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }
    }

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(KubeVirtTemplate.class.getName());

    protected Object readResolve() {
        labelSet = Label.parse(labelString);
        // Initialize ID for templates that were created before ID was added
        if (id == null) {
            id = UUID.randomUUID().toString();
            LOGGER.log(java.util.logging.Level.FINE, "Template ''{0}'' had no ID, generated new ID: {1}", 
                    new Object[]{name, id});
        }
        LOGGER.log(java.util.logging.Level.FINE, "Template ''{0}'' loaded with ID: {1}", 
                new Object[]{name, id});
        return this;
    }
}
