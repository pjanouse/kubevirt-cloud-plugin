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
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.slaves.Cloud;
import org.kohsuke.stapler.AncestorInPath;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.FormApply;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.jenkins.plugins.kubevirt.exception.VMDeletionException;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Jenkins Cloud implementation for KubeVirt / OpenShift Virtualization.
 * Provides dynamic provisioning of Jenkins agents as virtual machines.
 */
public class KubeVirtCloud extends Cloud implements VMTemplateGroup {
    private static final Logger LOGGER = Logger.getLogger(KubeVirtCloud.class.getName());

    private final String serverUrl;
    private final String credentialsId;
    private final String namespace;
    private final boolean ignoreSsl;
    private final Integer vmCountCap;
    private final boolean enableOrphanCleanup;
    
    @NonNull
    private List<KubeVirtTemplate> templates = new ArrayList<>();

    // Services for provisioning (transient - recreated as needed)
    private transient KubeVirtNamingStrategy namingStrategy;

    // In-flight provisioning counters to prevent race conditions between
    // provision() calls and actual VM creation in Kubernetes
    private transient AtomicInteger inFlightGlobalCount;
    private transient ConcurrentHashMap<String, AtomicInteger> inFlightTemplateCount;

    @DataBoundConstructor
    public KubeVirtCloud(String name, String serverUrl, String credentialsId, String namespace,
                         boolean ignoreSsl, String vmCountCap, boolean enableOrphanCleanup,
                         List<KubeVirtTemplate> templates) {
        super(name);
        this.serverUrl = serverUrl;
        this.credentialsId = credentialsId;
        this.namespace = namespace;
        this.ignoreSsl = ignoreSsl;
        this.vmCountCap = parseVmCountCap(vmCountCap);
        this.enableOrphanCleanup = enableOrphanCleanup;
        setTemplates(templates);
    }

    /**
     * Sets the templates for this cloud.
     * @param templates the templates to set
     */
    public void setTemplates(List<KubeVirtTemplate> templates) {
        this.templates = templates != null ? new ArrayList<>(templates) : new ArrayList<>();
    }

    /**
     * Parses the VM count cap string.
     *
     * @param vmCountCap The VM count cap as a string
     * @return The parsed value, or default if empty/invalid. 0 means unlimited.
     */
    private static Integer parseVmCountCap(String vmCountCap) {
        if (vmCountCap == null || vmCountCap.trim().isEmpty()) {
            return KubeVirtConfiguration.DEFAULT_GLOBAL_INSTANCE_CAP;
        }
        try {
            int cap = Integer.parseInt(vmCountCap.trim());
            if (cap < 0) {
                return KubeVirtConfiguration.DEFAULT_GLOBAL_INSTANCE_CAP;
            }
            return cap; // 0 means unlimited, positive means that limit
        } catch (NumberFormatException e) {
            return KubeVirtConfiguration.DEFAULT_GLOBAL_INSTANCE_CAP;
        }
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    /**
     * Gets whether orphaned VM cleanup is enabled for this cloud.
     * When enabled, a periodic background task will detect and delete VMs
     * that exist in Kubernetes but have no registered Jenkins agent.
     *
     * @return true if orphan cleanup is enabled
     */
    public boolean isEnableOrphanCleanup() {
        return enableOrphanCleanup;
    }

    /**
     * Gets the VM count cap as configured (for UI binding).
     * Returns the raw value: 0 means unlimited, positive number means that limit.
     *
     * @return The configured VM count cap value
     */
    public int getVmCountCap() {
        return vmCountCap != null ? vmCountCap : KubeVirtConfiguration.DEFAULT_GLOBAL_INSTANCE_CAP;
    }

    /**
     * Gets the effective VM count cap for provisioning logic.
     * Returns {@link KubeVirtConfiguration#UNLIMITED_CAPACITY} if set to 0 (unlimited).
     *
     * @return The effective VM count cap, or UNLIMITED_CAPACITY if no limit
     */
    public int getEffectiveVmCountCap() {
        int cap = getVmCountCap();
        return cap == 0 ? KubeVirtConfiguration.UNLIMITED_CAPACITY : cap;
    }

    @NonNull
    public List<KubeVirtTemplate> getTemplates() {
        return templates;
    }

    // ==================== VMTemplateGroup Implementation ====================

    @Override
    public void addTemplate(KubeVirtTemplate template) {
        this.checkManagePermission();
        this.templates.add(template);
    }

    @Override
    public void replaceTemplate(KubeVirtTemplate oldTemplate, KubeVirtTemplate newTemplate) {
        this.checkManagePermission();
        this.removeTemplate(oldTemplate);
        this.addTemplate(newTemplate);
    }

    @Override
    public void removeTemplate(KubeVirtTemplate template) {
        this.checkManagePermission();
        this.templates.remove(template);
    }

    @Override
    public String getVMTemplateGroupUrl() {
        return "../../templates";
    }

    @Override
    public Permission getManagePermission() {
        return Jenkins.MANAGE;
    }

    // ==================== Template Routing ====================

    /**
     * Gets a template by ID for URL routing.
     * Used by Stapler to route to /cloud/{cloudName}/template/{templateId}
     * @param id the template ID
     * @return the template, or null if not found
     */
    public KubeVirtTemplate getTemplate(@NonNull String id) {
        return getTemplateById(id);
    }

    /**
     * Gets a template by ID.
     * @param id the template ID
     * @return the template, or null if not found
     */
    public KubeVirtTemplate getTemplateById(@NonNull String id) {
        return templates.stream()
                .filter(t -> id.equals(t.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets the template descriptor for use in Jelly.
     * @return the KubeVirtTemplate descriptor
     */
    @Restricted(NoExternalUse.class) // jelly
    public KubeVirtTemplate.DescriptorImpl getTemplateDescriptor() {
        return (KubeVirtTemplate.DescriptorImpl) Jenkins.get().getDescriptorOrDie(KubeVirtTemplate.class);
    }

    /**
     * Creates a new template via the UI.
     */
    @POST
    public HttpResponse doCreate(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException, Descriptor.FormException {
        Jenkins j = Jenkins.get();
        this.checkManagePermission();
        KubeVirtTemplate newTemplate = getTemplateDescriptor().newInstance(req, req.getSubmittedForm());
        addTemplate(newTemplate);
        j.save();
        // take the user back to templates list
        return FormApply.success("templates");
    }

    @Override
    public Cloud reconfigure(@NonNull StaplerRequest2 req, JSONObject form) throws Descriptor.FormException {
        // Cloud configuration doesn't contain templates anymore, so just keep existing ones.
        LOGGER.log(Level.FINE, "Reconfiguring cloud ''{0}'', preserving {1} templates",
                new Object[]{name, this.templates.size()});
        var newInstance = (KubeVirtCloud) super.reconfigure(req, form);
        // Preserve templates from the current instance since they're managed separately
        if (!this.templates.isEmpty()) {
            newInstance.setTemplates(this.templates);
            LOGGER.log(Level.FINE, "Copied {0} templates to new cloud instance", this.templates.size());
        }
        return newInstance;
    }

    /**
     * Called after deserialization to ensure proper initialization.
     * This handles cases where templates might be null in old configurations.
     */
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            justification = "Field may be null during deserialization of old configurations")
    private Object readResolve() {
        if (templates == null) {
            templates = new ArrayList<>();
            LOGGER.log(Level.WARNING, "Cloud ''{0}'' had null templates list after deserialization, initialized to empty list", name);
        } else {
            LOGGER.log(Level.FINE, "Cloud ''{0}'' loaded with {1} templates", new Object[]{name, templates.size()});
        }
        return this;
    }

    /**
     * Creates a cloud configuration object for this cloud.
     *
     * @return An immutable configuration object
     */
    public KubeVirtCloudConfig getCloudConfig() {
        return new KubeVirtCloudConfig(name, serverUrl, credentialsId, namespace, ignoreSsl);
    }

    /**
     * Returns all KubeVirt clouds configured in Jenkins.
     * Used by CloudInitConfig to show which templates use a given config file.
     *
     * @return List of all KubeVirt clouds
     */
    public static List<KubeVirtCloud> getAllClouds() {
        List<KubeVirtCloud> result = new ArrayList<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof KubeVirtCloud) {
                result.add((KubeVirtCloud) cloud);
            }
        }
        return result;
    }

    // ==================== In-Flight Provisioning Tracking ====================

    /**
     * Gets the in-flight global counter, initializing if necessary.
     * This tracks provisions that have been started but not yet reflected in Kubernetes.
     */
    private AtomicInteger getInFlightGlobalCount() {
        if (inFlightGlobalCount == null) {
            inFlightGlobalCount = new AtomicInteger(0);
        }
        return inFlightGlobalCount;
    }

    /**
     * Gets the in-flight template counter map, initializing if necessary.
     */
    private ConcurrentHashMap<String, AtomicInteger> getInFlightTemplateCountMap() {
        if (inFlightTemplateCount == null) {
            inFlightTemplateCount = new ConcurrentHashMap<>();
        }
        return inFlightTemplateCount;
    }

    /**
     * Increments the in-flight counter for global and template-specific tracking.
     * Called when a provision is started but before the VM exists in Kubernetes.
     *
     * @param templateName The template being provisioned
     */
    private void incrementInFlightCount(String templateName) {
        getInFlightGlobalCount().incrementAndGet();
        getInFlightTemplateCountMap()
                .computeIfAbsent(templateName, k -> new AtomicInteger(0))
                .incrementAndGet();
        LOGGER.log(Level.FINE, "Incremented in-flight count. Global: {0}, Template ''{1}'': {2}",
                new Object[]{getInFlightGlobalCount().get(), templateName,
                        getInFlightTemplateCountMap().get(templateName).get()});
    }

    /**
     * Decrements the in-flight counter for global and template-specific tracking.
     * Called after the VM is created in Kubernetes (or on failure).
     *
     * @param templateName The template that was provisioned
     */
    void decrementInFlightCount(String templateName) {
        int globalCount = getInFlightGlobalCount().decrementAndGet();
        if (globalCount < 0) {
            // Should not happen, but reset to 0 if it does
            getInFlightGlobalCount().set(0);
        }
        AtomicInteger templateCounter = getInFlightTemplateCountMap().get(templateName);
        if (templateCounter != null) {
            int templateCount = templateCounter.decrementAndGet();
            if (templateCount < 0) {
                templateCounter.set(0);
            }
        }
        LOGGER.log(Level.FINE, "Decremented in-flight count. Global: {0}, Template ''{1}''",
                new Object[]{getInFlightGlobalCount().get(), templateName});
    }

    /**
     * Gets the current in-flight count for global provisioning.
     */
    private int getInFlightGlobal() {
        return getInFlightGlobalCount().get();
    }

    /**
     * Gets the current in-flight count for a specific template.
     */
    private int getInFlightForTemplate(String templateName) {
        AtomicInteger counter = getInFlightTemplateCountMap().get(templateName);
        return counter != null ? counter.get() : 0;
    }

    // ==================== Capacity Counting Methods ====================

    /**
     * Counts all VMs for this cloud including in-flight provisions.
     * Combines VMs from Kubernetes API with provisions that have been started
     * but not yet reflected in Kubernetes, preventing race conditions.
     *
     * @return The total number of VMs (existing + in-flight)
     */
    public int countVMsFromKubernetes() {
        int kubernetesCount;
        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        try (KubeVirt virt = clientFactory.createClient(getCloudConfig())) {
            kubernetesCount = virt.countVMsByCloud(name);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count VMs from Kubernetes, falling back to registered agents: " + e.getMessage(), e);
            // Fall back to counting registered agents if Kubernetes API fails
            kubernetesCount = countRegisteredAgents();
        }
        // Add in-flight provisions that aren't yet in Kubernetes
        int inFlight = getInFlightGlobal();
        int total = kubernetesCount + inFlight;
        LOGGER.log(Level.FINE, "VM count: {0} from K8s + {1} in-flight = {2} total",
                new Object[]{kubernetesCount, inFlight, total});
        return total;
    }

    /**
     * Counts all VMs for a specific template including in-flight provisions.
     * Combines VMs from Kubernetes API with provisions that have been started
     * but not yet reflected in Kubernetes.
     *
     * @param templateName The template name to count
     * @return The total number of VMs for the template (existing + in-flight)
     */
    public int countVMsFromKubernetesForTemplate(String templateName) {
        int kubernetesCount;
        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        try (KubeVirt virt = clientFactory.createClient(getCloudConfig())) {
            kubernetesCount = virt.countVMsByCloudAndTemplate(name, templateName);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count VMs from Kubernetes for template " + templateName +
                    ", falling back to registered agents: " + e.getMessage(), e);
            // Fall back to counting registered agents if Kubernetes API fails
            kubernetesCount = countRegisteredAgentsForTemplate(templateName);
        }
        // Add in-flight provisions that aren't yet in Kubernetes
        int inFlight = getInFlightForTemplate(templateName);
        int total = kubernetesCount + inFlight;
        LOGGER.log(Level.FINE, "VM count for template ''{0}'': {1} from K8s + {2} in-flight = {3} total",
                new Object[]{templateName, kubernetesCount, inFlight, total});
        return total;
    }

    /**
     * Counts all registered KubeVirt agents for this cloud in Jenkins.
     * This only counts agents that have completed provisioning and are registered.
     * Used as a fallback if Kubernetes API is unavailable.
     *
     * @return The number of registered agents belonging to this cloud
     */
    public int countRegisteredAgents() {
        int count = 0;
        Jenkins jenkins = Jenkins.get();
        for (Node node : jenkins.getNodes()) {
            if (node instanceof KubeVirtAgent) {
                KubeVirtAgent agent = (KubeVirtAgent) node;
                if (name.equals(agent.getCloudName())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Counts all registered KubeVirt agents for a specific template in Jenkins.
     * This only counts agents that have completed provisioning and are registered.
     * Used as a fallback if Kubernetes API is unavailable.
     *
     * @param templateName The template name to count
     * @return The number of registered agents for the given template
     */
    public int countRegisteredAgentsForTemplate(String templateName) {
        int count = 0;
        Jenkins jenkins = Jenkins.get();
        for (Node node : jenkins.getNodes()) {
            if (node instanceof KubeVirtAgent) {
                KubeVirtAgent agent = (KubeVirtAgent) node;
                if (name.equals(agent.getCloudName()) &&
                    templateName.equals(agent.getTemplateName())) {
                    count++;
                }
            }
        }
        return count;
    }

    // ==================== Cloud Connectivity ====================

    /**
     * Performs a lightweight connectivity check against the Kubernetes API server.
     * Uses a short timeout to avoid blocking the calling thread for too long.
     *
     * @return {@code true} if the cluster responded, {@code false} otherwise
     */
    public boolean isCloudReachable() {
        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        int timeout = KubeVirtConfiguration.CONNECTIVITY_CHECK_TIMEOUT_MS;
        try (KubeVirt virt = clientFactory.createClientWithTimeout(
                getCloudConfig(), timeout, timeout)) {
            virt.verifyConnection();
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Cloud ''{0}'' connectivity check failed: {1}",
                    new Object[]{name, e.getMessage()});
            LOGGER.log(Level.FINE, "Connectivity check exception details", e);
            return false;
        }
    }

    // ==================== Provisioning ====================

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        // Pre-check: Verify cloud connectivity before starting any provisioning.
        // This prevents creating agents and phantom provisions when the cloud is unreachable.
        if (!isCloudReachable()) {
            LOGGER.log(Level.WARNING,
                    "Cloud ''{0}'' is not reachable at {1}. Provisioning aborted for label ''{2}''.",
                    new Object[]{name, serverUrl, state.getLabel()});
            return plannedNodes;
        }

        // Count VMs from Kubernetes API to include those still being provisioned
        // This prevents race conditions where multiple provision() calls could exceed the cap
        int currentGlobalCount = countVMsFromKubernetes();
        LOGGER.log(Level.FINE, "Current VM count from Kubernetes: {0}", currentGlobalCount);

        // Check global capacity
        int globalCap = getEffectiveVmCountCap();
        int globalAvailable;
        if (globalCap == KubeVirtConfiguration.UNLIMITED_CAPACITY) {
            globalAvailable = excessWorkload;
        } else {
            globalAvailable = Math.max(0, globalCap - currentGlobalCount);
        }

        if (globalAvailable <= 0) {
            LOGGER.log(Level.FINE, "Global VM count cap reached ({0}/{1}), not provisioning",
                    new Object[]{currentGlobalCount, globalCap});
            return plannedNodes;
        }

        // Track remaining global capacity (decrements as we provision)
        int remainingGlobalCapacity = globalAvailable;

        // Track per-template counts for this provisioning round
        java.util.Map<String, Integer> templateProvisionedCounts = new java.util.HashMap<>();

        for (KubeVirtTemplate template : templates) {
            if (!template.canProvision(state.getLabel())) {
                continue;
            }

            String templateName = template.getName();

            // Check template-level capacity using Kubernetes API
            int templateCap = template.getEffectiveInstanceCap();
            int currentTemplateCount = countVMsFromKubernetesForTemplate(templateName);
            // Add any we've already planned to provision in this call
            int alreadyPlannedForTemplate = templateProvisionedCounts.getOrDefault(templateName, 0);
            int totalTemplateCount = currentTemplateCount + alreadyPlannedForTemplate;

            int templateAvailable;
            if (templateCap == KubeVirtConfiguration.UNLIMITED_CAPACITY) {
                templateAvailable = excessWorkload;
            } else {
                templateAvailable = Math.max(0, templateCap - totalTemplateCount);
            }

            if (templateAvailable <= 0) {
                LOGGER.log(Level.FINE, "Template ''{0}'' instance cap reached ({1}/{2}), skipping",
                        new Object[]{templateName, totalTemplateCount, templateCap});
                continue;
            }

            // Provision up to the minimum of: excessWorkload, global available, template available
            int toProvision = Math.min(excessWorkload, Math.min(remainingGlobalCapacity, templateAvailable));

            while (toProvision > 0 && excessWorkload > 0) {
                LOGGER.log(Level.FINE, "Provisioning node from template: {0}", templateName);

                // Create a provisioning activity ID for cloud-stats tracking
                ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(
                        name,
                        templateName,
                        getNamingStrategy().generateNodeName(templateName)
                );

                // Increment in-flight counter BEFORE creating the agent.
                // This prevents race conditions between multiple provision() calls.
                incrementInFlightCount(templateName);

                try {
                    // Create the agent immediately with a provisioning launcher.
                    // The agent appears in the UI right away as offline/connecting.
                    // All VM lifecycle work (create, wait, SSH) happens inside
                    // KubeVirtProvisioningLauncher.launch(), which Jenkins core
                    // will invoke after the node is registered.
                    String nodeName = provisioningId.getNodeName();
                    if (nodeName == null) {
                        throw new IllegalStateException("Provisioning ID has no node name");
                    }
                    KubeVirtAgent agent = new KubeVirtAgent(
                            nodeName,
                            name,
                            templateName,
                            template.getRemoteFS(),
                            new KubeVirtProvisioningLauncher(name, templateName),
                            template.getLabels(),
                            null, // provisioningInfo — set later by the launcher
                            template.getIdleMinutes(),
                            provisioningId
                    );

                    // Pre-resolved future: Jenkins core will add the node on the
                    // next NodeProvisioner cycle without any blocking.
                    CompletableFuture<Node> future = CompletableFuture.completedFuture(agent);

                    // Use TrackedPlannedNode for cloud-stats integration
                    plannedNodes.add(new KubeVirtPlannedNode(
                            templateName, future, 1, provisioningId, templateName));

                } catch (IOException | Descriptor.FormException e) {
                    LOGGER.log(Level.SEVERE, "Failed to create agent for template " + templateName, e);
                    decrementInFlightCount(templateName);
                    // Report the failure through a failed future so cloud-stats can track it
                    CompletableFuture<Node> failedFuture = new CompletableFuture<>();
                    failedFuture.completeExceptionally(e);
                    plannedNodes.add(new KubeVirtPlannedNode(
                            templateName, failedFuture, 1, provisioningId, templateName));
                }

                // Track what we've provisioned
                templateProvisionedCounts.merge(templateName, 1, (a, b) -> a + b);

                toProvision--;
                excessWorkload--;
                remainingGlobalCapacity--;
            }

            if (remainingGlobalCapacity <= 0) {
                LOGGER.log(Level.FINE, "Global capacity exhausted, stopping provisioning");
                break;
            }
        }

        return plannedNodes;
    }

    @Override
    public boolean canProvision(CloudState state) {
        for (KubeVirtTemplate template : templates) {
            if (template.canProvision(state.getLabel())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the naming strategy, creating it if necessary.
     */
    private KubeVirtNamingStrategy getNamingStrategy() {
        if (namingStrategy == null) {
            namingStrategy = new KubeVirtNamingStrategy();
        }
        return namingStrategy;
    }

    /**
     * Deletes a VM by name after validating ownership.
     *
     * @param vmName       The name of the VM to delete
     * @param templateName The expected template name for ownership validation
     * @throws VMDeletionException if the VM cannot be deleted
     * @throws IllegalStateException if the VM doesn't belong to this instance
     */
    public void deleteVM(String vmName, String templateName) {
        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        try (KubeVirt virt = clientFactory.createClient(getCloudConfig())) {
            virt.deleteVM(vmName, this.name, templateName);
            LOGGER.log(Level.FINE, "Successfully deleted VM: {0}", vmName);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                LOGGER.log(Level.FINE, "VM {0} not found (may already be deleted)", vmName);
                return;
            }
            String errorMsg = String.format("Failed to delete VM '%s': %s", vmName, e.getMessage());
            LOGGER.log(Level.WARNING, errorMsg, e);
            throw new VMDeletionException(vmName, errorMsg, e);
        } catch (IllegalStateException e) {
            String errorMsg = String.format("Cannot delete VM '%s': %s", vmName, e.getMessage());
            LOGGER.log(Level.WARNING, errorMsg, e);
            throw new VMDeletionException(vmName, errorMsg, e);
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "KubeVirt / OpenShift Virtualization";
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup<?> context) {
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
                            StandardUsernamePasswordCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StringCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always()
                    );
        }

        /**
         * Validates the VM count cap field.
         */
        @POST
        public FormValidation doCheckVmCountCap(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok("Will use default: " + KubeVirtConfiguration.DEFAULT_GLOBAL_INSTANCE_CAP);
            }
            try {
                int cap = Integer.parseInt(value.trim());
                if (cap < 0) {
                    return FormValidation.error("VM count cap cannot be negative.");
                }
                if (cap == 0) {
                    return FormValidation.ok("Unlimited (no cap)");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number.");
            }
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String serverUrl,
                @QueryParameter String credentialsId,
                @QueryParameter String namespace,
                @QueryParameter boolean ignoreSsl) {

            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }

            // Validate required fields
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return FormValidation.error("Server URL is required");
            }
            if (namespace == null || namespace.trim().isEmpty()) {
                return FormValidation.error("Namespace is required");
            }
            if (credentialsId == null || credentialsId.trim().isEmpty()) {
                return FormValidation.error("Credentials are required");
            }

            // Lookup credentials - try StringCredentials first, then fall back to Username/Password
            String token = null;

            // First, try StringCredentials (Secret text)
            StringCredentials stringCreds = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StringCredentials.class,
                            Jenkins.get(),
                            ACL.SYSTEM2,
                            Collections.<DomainRequirement>emptyList()
                    ),
                    CredentialsMatchers.withId(credentialsId)
            );

            if (stringCreds != null) {
                token = stringCreds.getSecret().getPlainText();
            } else {
                // Fall back to StandardUsernamePasswordCredentials
                StandardUsernamePasswordCredentials userPassCreds = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                StandardUsernamePasswordCredentials.class,
                                Jenkins.get(),
                                ACL.SYSTEM2,
                                Collections.<DomainRequirement>emptyList()
                        ),
                        CredentialsMatchers.withId(credentialsId)
                );

                if (userPassCreds != null) {
                    token = userPassCreds.getPassword().getPlainText();
                }
            }

            if (token == null || token.trim().isEmpty()) {
                LOGGER.log(Level.SEVERE, "Test Connection failed: Credentials not found or token is empty for ID: {0}", credentialsId);
                return FormValidation.error("Credentials not found or token is empty for ID: " + credentialsId);
            }

            KubeVirt virt = null;
            try {
                LOGGER.log(Level.INFO, "Testing connection to KubeVirt cluster - Server: {0}, Namespace: {1}, IgnoreSSL: {2}",
                        new Object[]{serverUrl, namespace, ignoreSsl});

                virt = new KubeVirt(serverUrl, token, namespace, ignoreSsl);
                String result = virt.testConnection();

                LOGGER.log(Level.INFO, "Test Connection successful:\n{0}", result);
                return FormValidation.ok("Connection successful!\n" + result);

            } catch (Exception e) {
                // Build detailed error message
                StringBuilder errorDetails = new StringBuilder();
                errorDetails.append("Connection failed: ").append(e.getMessage());

                // Add root cause if available
                Throwable cause = e.getCause();
                if (cause != null) {
                    errorDetails.append("\n\nRoot cause: ").append(cause.getClass().getSimpleName())
                            .append(": ").append(cause.getMessage());

                    // Check for common issues and provide helpful hints
                    if (cause instanceof java.net.UnknownHostException) {
                        errorDetails.append("\n\nHint: The server hostname could not be resolved. Check the Server URL.");
                    } else if (cause instanceof java.net.ConnectException) {
                        errorDetails.append("\n\nHint: Could not connect to the server. Verify the server is reachable and the URL is correct.");
                    } else if (cause instanceof javax.net.ssl.SSLException) {
                        errorDetails.append("\n\nHint: SSL/TLS error. Try enabling 'Ignore SSL errors' if using self-signed certificates.");
                    }

                    // Add nested cause if present
                    Throwable nestedCause = cause.getCause();
                    if (nestedCause != null) {
                        errorDetails.append("\n\nNested cause: ").append(nestedCause.getClass().getSimpleName())
                                .append(": ").append(nestedCause.getMessage());
                    }
                }

                // Check for authentication issues in the message
                String errorMsg = e.getMessage();
                if (errorMsg != null) {
                    if (errorMsg.contains("401") || errorMsg.contains("Unauthorized")) {
                        errorDetails.append("\n\nHint: Authentication failed. Verify the token/password in your credentials is correct and not expired.");
                    } else if (errorMsg.contains("403") || errorMsg.contains("Forbidden")) {
                        errorDetails.append("\n\nHint: Access denied. The credentials may not have sufficient permissions for the specified namespace.");
                    } else if (errorMsg.contains("404") || errorMsg.contains("not found")) {
                        errorDetails.append("\n\nHint: Resource not found. Verify the namespace exists and KubeVirt is installed.");
                    }
                }

                LOGGER.log(Level.SEVERE, "Test Connection failed: " + errorDetails, e);
                return FormValidation.error(errorDetails.toString());

            } finally {
                if (virt != null) {
                    virt.close();
                }
            }
        }
    }
}
