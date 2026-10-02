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

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import jenkins.model.Jenkins;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic background task that detects and cleans up orphaned VMs in Kubernetes.
 *
 * <p>A VM is considered orphaned if:</p>
 * <ul>
 *   <li>It exists in Kubernetes with our cloud label</li>
 *   <li>No corresponding Jenkins agent is registered</li>
 *   <li>It has existed for longer than the template's grace period (to avoid deleting VMs still provisioning)</li>
 * </ul>
 *
 * <p>This provides a safety net for scenarios where:</p>
 * <ul>
 *   <li>Agent registration failed after VM creation</li>
 *   <li>Jenkins crashed/restarted during provisioning</li>
 *   <li>Network issues prevented proper cleanup</li>
 *   <li>The _terminate callback failed to delete the VM</li>
 * </ul>
 */
@Extension
public class OrphanedVMCleanupPeriodicWork extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(OrphanedVMCleanupPeriodicWork.class.getName());

    /**
     * How often to run the orphan detection (in milliseconds).
     * Default: 1 hour
     */
    private static final long RECURRENCE_PERIOD_MS = TimeUnit.HOURS.toMillis(1);

    public OrphanedVMCleanupPeriodicWork() {
        super("KubeVirt Orphaned VM Cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        List<KubeVirtCloud> clouds = KubeVirtCloud.getAllClouds();

        if (clouds.isEmpty()) {
            LOGGER.log(Level.FINE, "No KubeVirt clouds configured, skipping orphan detection");
            return;
        }

        KubeVirtLog.log(listener.getLogger(), "Starting orphaned VM detection for " +
                clouds.size() + " cloud(s)");

        int totalOrphansDeleted = 0;
        int cloudsWithCleanupEnabled = 0;

        for (KubeVirtCloud cloud : clouds) {
            // Skip clouds that have orphan cleanup disabled
            if (!cloud.isEnableOrphanCleanup()) {
                LOGGER.log(Level.FINE, "Orphan cleanup is disabled for cloud ''{0}'', skipping", cloud.name);
                continue;
            }
            cloudsWithCleanupEnabled++;

            try {
                int deleted = cleanupOrphanedVMsForCloud(cloud, listener);
                totalOrphansDeleted += deleted;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during orphan cleanup for cloud " + cloud.name, e);
                KubeVirtLog.log(listener.getLogger(), "Error checking cloud '" + cloud.name +
                        "': " + KubeVirtLog.messageOf(e));
            }
        }

        if (cloudsWithCleanupEnabled == 0) {
            LOGGER.log(Level.FINE, "Orphan cleanup is disabled for all clouds, skipping");
            return;
        }

        if (totalOrphansDeleted > 0) {
            KubeVirtLog.log(listener.getLogger(), "Orphan cleanup complete. Deleted " +
                    totalOrphansDeleted + " orphaned VM(s)");
        } else {
            LOGGER.log(Level.FINE, "No orphaned VMs found");
        }
    }

    /**
     * Cleans up orphaned VMs for a specific cloud.
     *
     * @param cloud The cloud to check
     * @param listener Task listener for logging
     * @return The number of orphaned VMs deleted
     */
    private int cleanupOrphanedVMsForCloud(KubeVirtCloud cloud, TaskListener listener) {
        // Get all VMs in Kubernetes for this cloud
        KubeVirtClientFactory clientFactory = new KubeVirtClientFactory();
        List<String> kubernetesVMs;
        Map<String, Instant> vmCreationTimes;
        Map<String, String> vmTemplateNames;

        // Pre-check: verify cloud connectivity before attempting cleanup.
        if (!cloud.isCloudReachable()) {
            LOGGER.log(Level.WARNING,
                    "Cloud ''{0}'' is not reachable, skipping orphan cleanup",
                    cloud.name);
            return 0;
        }

        try (KubeVirt virt = clientFactory.createClient(cloud.getCloudConfig())) {
            kubernetesVMs = virt.listVMsByCloud(cloud.name);
            vmCreationTimes = getVMCreationTimes(virt, kubernetesVMs);
            vmTemplateNames = getVMTemplateNames(virt, kubernetesVMs);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to list VMs for cloud " + cloud.name, e);
            return 0;
        }

        if (kubernetesVMs.isEmpty()) {
            LOGGER.log(Level.FINE, "No VMs found in Kubernetes for cloud ''{0}''", cloud.name);
            return 0;
        }

        // Get all registered agent names for this cloud
        Set<String> registeredAgentNames = getRegisteredAgentNames(cloud.name);

        LOGGER.log(Level.FINE, "Cloud ''{0}'': {1} VMs in K8s, {2} registered agents",
                new Object[]{cloud.name, kubernetesVMs.size(), registeredAgentNames.size()});

        // Build a map of template name to template for quick lookup
        Map<String, KubeVirtTemplate> templateMap = buildTemplateMap(cloud);

        // Find orphaned VMs
        int deletedCount = 0;
        Instant now = Instant.now();

        for (String vmName : kubernetesVMs) {
            if (registeredAgentNames.contains(vmName)) {
                // VM has a registered agent - not orphaned
                continue;
            }

            // Get the grace period for this VM's template
            String templateName = vmTemplateNames.get(vmName);
            int gracePeriodMinutes = getGracePeriodForTemplate(templateName, templateMap);
            Duration gracePeriod = Duration.ofMinutes(gracePeriodMinutes);

            // Check if VM is within grace period
            Instant creationTime = vmCreationTimes.get(vmName);
            if (creationTime != null) {
                Duration age = Duration.between(creationTime, now);
                if (age.compareTo(gracePeriod) < 0) {
                    LOGGER.log(Level.FINE, "VM ''{0}'' is young ({1} minutes old), within grace period ({2} min) - skipping",
                            new Object[]{vmName, age.toMinutes(), gracePeriodMinutes});
                    continue;
                }
            }

            // VM is orphaned - delete it
            KubeVirtLog.log(listener.getLogger(), "Found orphaned VM: " + vmName +
                    " (no registered agent, older than " + gracePeriodMinutes + " minutes)");
            LOGGER.log(Level.FINE, "Deleting orphaned VM ''{0}'' from cloud ''{1}''",
                    new Object[]{vmName, cloud.name});

            try {
                cloud.deleteVM(vmName, templateName);
                deletedCount++;
                KubeVirtLog.log(listener.getLogger(), "Deleted orphaned VM: " + vmName);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete orphaned VM: " + vmName, e);
                KubeVirtLog.log(listener.getLogger(), "Failed to delete orphaned VM " + vmName +
                        ": " + KubeVirtLog.messageOf(e));
            }
        }

        return deletedCount;
    }

    /**
     * Gets the set of registered agent names for a specific cloud.
     *
     * @param cloudName The cloud name to filter by
     * @return Set of agent node names
     */
    private Set<String> getRegisteredAgentNames(String cloudName) {
        Set<String> agentNames = new HashSet<>();
        Jenkins jenkins = Jenkins.get();

        for (Node node : jenkins.getNodes()) {
            if (node instanceof KubeVirtAgent) {
                KubeVirtAgent agent = (KubeVirtAgent) node;
                if (cloudName.equals(agent.getCloudName())) {
                    agentNames.add(agent.getNodeName());
                }
            }
        }

        return agentNames;
    }

    /**
     * Gets the creation times for a list of VMs.
     *
     * @param virt The KubeVirt client
     * @param vmNames The VM names to query
     * @return Map of VM name to creation time
     */
    private Map<String, Instant> getVMCreationTimes(KubeVirt virt, List<String> vmNames) {
        Map<String, Instant> creationTimes = new HashMap<>();

        for (String vmName : vmNames) {
            try {
                String creationTimestamp = virt.getVMCreationTimestamp(vmName);
                if (creationTimestamp != null) {
                    Instant instant = Instant.parse(creationTimestamp);
                    creationTimes.put(vmName, instant);
                }
            } catch (DateTimeParseException e) {
                LOGGER.log(Level.FINE, "Could not parse creation timestamp for VM " + vmName, e);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not get creation timestamp for VM " + vmName, e);
            }
        }

        return creationTimes;
    }

    /**
     * Gets the template names for a list of VMs from their labels.
     *
     * @param virt The KubeVirt client
     * @param vmNames The VM names to query
     * @return Map of VM name to template name
     */
    private Map<String, String> getVMTemplateNames(KubeVirt virt, List<String> vmNames) {
        Map<String, String> templateNames = new HashMap<>();

        for (String vmName : vmNames) {
            try {
                String templateName = virt.getVMTemplateName(vmName);
                if (templateName != null) {
                    templateNames.put(vmName, templateName);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not get template name for VM " + vmName, e);
            }
        }

        return templateNames;
    }

    /**
     * Builds a map of template name to template for quick lookup.
     *
     * @param cloud The cloud containing the templates
     * @return Map of template name to template
     */
    private Map<String, KubeVirtTemplate> buildTemplateMap(KubeVirtCloud cloud) {
        Map<String, KubeVirtTemplate> templateMap = new HashMap<>();
        List<KubeVirtTemplate> templates = cloud.getTemplates();

        if (templates != null) {
            for (KubeVirtTemplate template : templates) {
                templateMap.put(template.getName(), template);
            }
        }

        return templateMap;
    }

    /**
     * Gets the grace period for a template, falling back to default if template not found.
     *
     * @param templateName The template name (may be null)
     * @param templateMap Map of template name to template
     * @return The grace period in minutes
     */
    private int getGracePeriodForTemplate(String templateName, Map<String, KubeVirtTemplate> templateMap) {
        if (templateName != null) {
            KubeVirtTemplate template = templateMap.get(templateName);
            if (template != null) {
                return template.getOrphanGracePeriodMinutes();
            }
        }
        // Fall back to default if template not found
        return KubeVirtConfiguration.DEFAULT_ORPHAN_GRACE_PERIOD_MINUTES;
    }
}
