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
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for KubeVirt agent computer events to:
 * - Provide detailed logging in the build console
 * - Cleanup VMs when agents go offline or fail to launch
 * - Enforce provisioning retry limits and fail queued builds when exceeded
 */
@Extension
public class KubeVirtComputerListener extends ComputerListener {
    
    private static final Logger LOGGER = Logger.getLogger(KubeVirtComputerListener.class.getName());
    
    @Override
    public void preLaunch(Computer c, TaskListener listener) throws IOException, InterruptedException {
        if (c instanceof KubeVirtComputer) {
            KubeVirtComputer kvc = (KubeVirtComputer) c;
            KubeVirtAgent agent = kvc.getNode();
            if (agent != null) {
                KubeVirtLog.log(listener.getLogger(), "Preparing to launch agent on VM: " + agent.getNodeName());
                // Note: provisioning details are written live to this TaskListener
                // by KubeVirtProvisioningLauncher during launch().
            }
        }
    }
    
    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        if (c instanceof KubeVirtComputer) {
            KubeVirtComputer kvc = (KubeVirtComputer) c;
            KubeVirtAgent agent = kvc.getNode();
            if (agent != null) {
                KubeVirtTemplate template = resolveTemplate(agent);
                if (template != null) {
                    ProvisioningFailureTracker.reset(template.getId());
                }
                KubeVirtLog.log(listener.getLogger(), "Agent '" + agent.getNodeName() + "' is now online and ready for builds");
                LOGGER.log(Level.FINE, "KubeVirt agent {0} is online", agent.getNodeName());
            }
        }
    }
    
    @Override
    public void onOffline(Computer c, OfflineCause cause) {
        if (c instanceof KubeVirtComputer) {
            KubeVirtAgent agent = ((KubeVirtComputer) c).getNode();
            if (agent != null) {
                String nodeName = agent.getNodeName();
                String reason = cause != null ? cause.toString() : "unknown";
                LOGGER.log(Level.FINE, "KubeVirt agent {0} went offline: {1}",
                        new Object[]{nodeName, reason});
                
                // DON'T automatically cleanup on offline - this could be a transient network issue.
                // Let CloudRetentionStrategy handle idle termination, or wait for the agent to reconnect.
                // Only cleanup if the cause indicates a permanent failure that won't recover.
                if (cause instanceof OfflineCause.ChannelTermination) {
                    // Log but don't immediately delete - the VM might still be running
                    // and the connection could be re-established
                    LOGGER.log(Level.WARNING, 
                            "Agent {0} channel terminated. VM is still running and may reconnect. " +
                            "CloudRetentionStrategy will cleanup if idle.", nodeName);
                }
                // Note: If the VM truly crashed, the agent will remain offline and 
                // CloudRetentionStrategy will terminate it after the idle timeout.
            }
        }
    }
    
    @Override
    public void onLaunchFailure(Computer c, TaskListener listener) throws IOException, InterruptedException {
        if (c instanceof KubeVirtComputer kvc) {
            KubeVirtAgent agent = kvc.getNode();
            if (agent != null) {
                String nodeName = agent.getNodeName();
                LOGGER.log(Level.WARNING, "KubeVirt agent {0} failed to launch, cleaning up VM", 
                        nodeName);
                KubeVirtLog.error(listener, "Agent failed to launch, VM will be cleaned up");
                
                // Surface SSH authentication errors in the Jenkins system log so
                // admins don't have to dig through individual agent logs
                String sshAuthError = SshAuthHelper.getSshAuthError(c, 0);
                if (sshAuthError != null) {
                    LOGGER.log(Level.SEVERE, "[{0}] SSH AUTHENTICATION ERROR: {1}. "
                                    + "Verify that the SSH credentials are correct and the VM''s "
                                    + "authorized_keys contains the matching public key.",
                            new Object[]{nodeName, sshAuthError});
                }

                // Count only while the VM is still being provisioned. After a
                // successful provision the launcher is swapped to SSH; later
                // reconnect failures must not consume the retry budget.
                // abortProvisioning() records before terminate(); if the node
                // is still registered, tryMarkProvisioningFailureHandled()
                // makes this a no-op instead of double-counting.
                if (kvc.isProvisioning()) {
                    try {
                        handleProvisioningFailure(agent, listener);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING,
                                "Failed to enforce provisioning retry limit for agent " + nodeName, e);
                    }
                }

                // Immediately cleanup on launch failure
                scheduleAgentCleanup(nodeName, "launch failure");
            }
        }
    }

    /**
     * Tracks consecutive launch failures and cancels queued builds when the
     * template's retry limit is reached.
     */
    static void handleProvisioningFailure(KubeVirtAgent agent, TaskListener listener) {
        if (!agent.tryMarkProvisioningFailureHandled()) {
            return;
        }

        KubeVirtTemplate template = resolveTemplate(agent);
        if (template == null) {
            LOGGER.log(Level.FINE,
                    "Skipping retry-limit tracking; template ''{0}'' was not found on cloud ''{1}''",
                    new Object[]{agent.getTemplateName(), agent.getCloudName()});
            return;
        }

        int retries = template.getMaxProvisionAttempts();
        if (template.hasUnlimitedProvisionRetries()) {
            return;
        }

        String templateId = template.getId();
        String labelString = agent.getLabelString();
        int failures = ProvisioningFailureTracker.recordFailure(templateId);
        int allowedFailures = template.getAllowedProvisionFailures();

        if (failures < allowedFailures) {
            KubeVirtLog.log(listener.getLogger(), String.format(
                    "Provisioning attempt %d/%d failed for template '%s' (labels '%s'). "
                            + "Jenkins will provision another VM if demand remains.",
                    failures, allowedFailures, template.getName(), labelString));
            return;
        }

        String reason = String.format(
                "KubeVirt provisioning failed %d consecutive time(s) for template '%s' "
                        + "(labels '%s', retries: %d)",
                failures, template.getName(), labelString, retries);
        int cancelled = QueueUtils.cancelWaitingItemsSatisfiedBy(
                Label.parse(agent.getLabelString()), reason);
        ProvisioningFailureTracker.reset(templateId);

        if (cancelled > 0) {
            KubeVirtLog.error(listener, reason + ". Cancelled " + cancelled + " queued build(s).");
            LOGGER.log(Level.WARNING, "{0}. Cancelled {1} queued build(s).",
                    new Object[]{reason, cancelled});
        } else {
            KubeVirtLog.error(listener, reason + ". No queued builds were found to cancel.");
            LOGGER.log(Level.WARNING, "{0}. No queued builds were found to cancel.", reason);
        }
    }

    static KubeVirtTemplate resolveTemplate(KubeVirtAgent agent) {
        Cloud cloud = Jenkins.get().getCloud(agent.getCloudName());
        if (!(cloud instanceof KubeVirtCloud kubeVirtCloud)) {
            return null;
        }

        String templateName = agent.getTemplateName();
        if (templateName == null) {
            return null;
        }

        for (KubeVirtTemplate template : kubeVirtCloud.getTemplates()) {
            if (templateName.equals(template.getName())) {
                return template;
            }
        }
        return null;
    }

    /**
     * Schedules the termination of an agent and its associated VM.
     * This is done asynchronously to avoid blocking the caller.
     * 
     * Note: We call terminate() not removeNode() because terminate() 
     * triggers _terminate() which actually deletes the VM.
     */
    private void scheduleAgentCleanup(String nodeName, String reason) {
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                // Small delay to ensure any pending operations complete
                Thread.sleep(1000);
                
                Jenkins jenkins = Jenkins.get();
                KubeVirtAgent agent = 
                        (KubeVirtAgent) jenkins.getNode(nodeName);
                
                if (agent != null) {
                    LOGGER.log(Level.FINE, "Terminating agent {0} and VM due to: {1}",
                            new Object[]{nodeName, reason});
                    // Set termination reason before calling terminate()
                    agent.setTerminationReason(KubeVirtAgent.TerminationReason.LAUNCH_FAILURE);
                    // terminate() calls _terminate() which deletes the VM, 
                    // then removes the node from Jenkins
                    agent.terminate();
                    LOGGER.log(Level.FINE, "Agent {0} terminated and VM deleted successfully", nodeName);
                } else {
                    LOGGER.log(Level.FINE, "Agent {0} already removed", nodeName);
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.WARNING, "Error terminating agent " + nodeName, e);
            }
        });
    }
}
