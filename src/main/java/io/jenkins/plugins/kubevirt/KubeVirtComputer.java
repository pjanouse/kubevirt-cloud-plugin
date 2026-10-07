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
import hudson.model.Computer;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.ComputerLauncher;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.security.core.Authentication;
import jenkins.model.Jenkins;

import java.io.IOException;

/**
 * Computer implementation for KubeVirt agents.
 * Implements TrackedItem for cloud-stats plugin integration.
 */
public class KubeVirtComputer extends AbstractCloudComputer<KubeVirtAgent> implements TrackedItem {

    public KubeVirtComputer(KubeVirtAgent agent) {
        super(agent);
    }

    // ==================== Status Icon ====================

    /**
     * Returns {@code true} while the VM is still being provisioned, i.e. the
     * agent's launcher is still the {@link KubeVirtProvisioningLauncher}.
     * Once provisioning completes the launcher is swapped to the real SSH
     * launcher and this method returns {@code false}.
     */
    public boolean isProvisioning() {
        KubeVirtAgent agent = getNode();
        if (agent == null) {
            return false;
        }
        ComputerLauncher launcher = agent.getLauncher();
        return launcher instanceof KubeVirtProvisioningLauncher;
    }

    /**
     * Overrides the default computer icon so that nodes still being
     * provisioned are visually distinct from regular online/offline nodes.
     *
     * <ul>
     *   <li><b>Provisioning</b> — {@code symbol-computer-paused}
     *       (same icon Jenkins uses for "will connect when needed")</li>
     *   <li><b>Otherwise</b> — delegates to the default Jenkins implementation</li>
     * </ul>
     */
    @Override
    public String getIcon() {
        if (isProvisioning()) {
            return "symbol-computer-paused";
        }
        return super.getIcon();
    }

    /**
     * Overrides {@link hudson.slaves.SlaveComputer#getIconClassName()} which
     * returns the <em>online</em> icon ({@code symbol-computer}) while a
     * connection attempt is in progress. For KubeVirt agents that are still
     * provisioning a VM this is misleading, so we return a distinct
     * "paused / waiting" icon instead.
     */
    @Override
    public String getIconClassName() {
        if (isProvisioning()) {
            return "symbol-computer-paused";
        }
        return super.getIconClassName();
    }

    /**
     * Provides alt text that matches the provisioning icon.
     */
    @Override
    public String getIconAltText() {
        if (isProvisioning()) {
            return "[provisioning]";
        }
        return super.getIconAltText();
    }

    // ==================== Lifecycle ====================

    /**
     * Override delete action to track that this is a user-initiated deletion.
     * This ensures the correct OfflineCause is used when the agent goes offline.
     */
    @RequirePOST
    @Override
    public HttpResponse doDoDelete() throws IOException {
        checkPermission(DELETE);
        KubeVirtAgent agent = getNode();
        if (agent != null) {
            agent.setTerminationReason(KubeVirtAgent.TerminationReason.USER_DELETED);
        }
        return super.doDoDelete();
    }

    /**
     * Gets the provisioning activity ID for cloud-stats tracking.
     * Delegates to the associated KubeVirtAgent node.
     *
     * @return The provisioning activity ID, or null if the node is not available
     */
    @Override
    public ProvisioningActivity.Id getId() {
        KubeVirtAgent node = getNode();
        return node != null ? node.getId() : null;
    }

    /**
     * Override ACL to deny CONFIGURE permission.
     * KubeVirt agents are dynamically provisioned and should not be manually configured.
     * This hides the Configure link from the sidebar.
     */
    @Override
    @NonNull
    public ACL getACL() {
        final KubeVirtAgent node = getNode();
        final ACL base = node != null ? node.getACL() : Jenkins.get().getACL();
        return new KubeVirtComputerACL(base);
    }

    /**
     * Custom ACL that denies CONFIGURE permission for KubeVirt computers.
     */
    private static final class KubeVirtComputerACL extends ACL {

        private final ACL base;

        public KubeVirtComputerACL(final ACL base) {
            this.base = base;
        }

        @Override
        public boolean hasPermission2(Authentication a, Permission permission) {
            // Deny CONFIGURE permission - these agents are not manually configurable
            return permission == Computer.CONFIGURE ? false : base.hasPermission2(a, permission);
        }
    }
}
