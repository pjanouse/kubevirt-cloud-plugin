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
import hudson.ExtensionList;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Cloud-init configuration file for KubeVirt VMs.
 * <p>
 * Managed via Jenkins → Manage Jenkins → Managed Files.
 * Users can create cloud-init YAML configurations that include SSH keys,
 * user setup, and other first-boot customizations.
 * </p>
 */
public class CloudInitConfig extends Config {
    private static final long serialVersionUID = 1L;

    @DataBoundConstructor
    public CloudInitConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Override
    public ConfigProvider getDescriptor() {
        return ExtensionList.lookupSingleton(CloudInitConfigProvider.class);
    }

    /**
     * Resolves cloud-init content from config ID.
     *
     * @param cloudInitId The ID of the cloud-init config
     * @return The cloud-init content, or null if ID is null/empty or content is empty
     * @throws IllegalArgumentException if the config ID doesn't exist or is wrong type
     */
    public static @CheckForNull String resolve(@CheckForNull String cloudInitId) {
        if (cloudInitId == null || cloudInitId.isEmpty()) {
            return null;
        }

        Config config = ConfigFiles.getByIdOrNull(Jenkins.get(), cloudInitId);
        if (config == null) {
            throw new IllegalArgumentException(
                "Unable to locate KubeVirt cloud-init config: '" + cloudInitId + "'");
        }
        if (!(config instanceof CloudInitConfig cloudInitConfig)) {
            throw new IllegalArgumentException(
                "Config file is not of correct type (expected CloudInitConfig): " + config.getClass());
        }

        return cloudInitConfig.content.isEmpty() ? null : cloudInitConfig.content;
    }

    /**
     * Config provider extension that registers this config type with Jenkins.
     * This makes "KubeVirt Cloud-Init" appear as an option
     * when creating new managed files.
     */
    @Extension(ordinal = 70)
    @Symbol("kubevirtCloudInit")
    public static class CloudInitConfigProvider extends AbstractConfigProviderImpl {

        public CloudInitConfigProvider() {
            load();
        }

        @Override
        public ContentType getContentType() {
            // Using HTML as it provides a plain text editor (same as OpenStack plugin)
            return ContentType.DefinedType.HTML;
        }

        @Override
        public String getDisplayName() {
            return "KubeVirt Cloud-Init";
        }

        @Override
        public @Nonnull Config newConfig(@SuppressWarnings("null") @Nonnull String id) {
            return new CloudInitConfig(id, "New Cloud-Init", "", getDefaultTemplate());
        }

        // @Override
        // @SuppressWarnings("unchecked", "deprecation")
        // public Config convert(Config config) {
        //     return new CloudInitConfig(config.id, config.name, config.comment, config.content);
        // }

        /**
         * Provides a default cloud-init template to help users get started.
         */
        private String getDefaultTemplate() {
            return "#cloud-config\n" +
                   "# KubeVirt Cloud-Init Configuration\n" +
                   "#\n" +
                   "# This file is processed by cloud-init on first boot.\n" +
                   "# Ensure your VM image has cloud-init installed.\n" +
                   "#\n" +
                   "# For SSH key injection, replace the placeholder below with your actual public key.\n" +
                   "# The public key should match the private key configured in Jenkins SSH credentials.\n" +
                   "\n" +
                   "# Stop sshd early to prevent premature agent connection.\n" +
                   "# bootcmd runs BEFORE user creation, package installation, and runcmd.\n" +
                   "bootcmd:\n" +
                   "  - systemctl stop sshd || true\n" +
                   "  - systemctl disable sshd || true\n" +
                   "\n" +
                   "users:\n" +
                   "  - name: jenkins\n" +
                   "    sudo: ALL=(ALL) NOPASSWD:ALL\n" +
                   "    shell: /bin/bash\n" +
                   "    ssh_authorized_keys:\n" +
                   "      - ssh-rsa AAAA... your-public-key-here\n" +
                   "\n" +
                   "# Ensure SSH daemon is running (keep as the LAST runcmd entry)\n" +
                   "runcmd:\n" +
                   "  - systemctl enable sshd\n" +
                   "  - systemctl start sshd\n";
        }

        /**
         * Returns list of templates that use a given cloud-init config.
         * Used by the Jelly UI to show usages when editing the config file.
         *
         * @param id The config file ID
         * @return Collection of "cloud-name / template-name" strings
         */
        @Restricted(DoNotUse.class) // Jelly
        public Collection<String> usages(@Nonnull String id) {
            ArrayList<String> usages = new ArrayList<>();
            for (KubeVirtCloud cloud : KubeVirtCloud.getAllClouds()) {
                for (KubeVirtTemplate template : cloud.getTemplates()) {
                    if (id.equals(template.getCloudInitId())) {
                        usages.add(cloud.name + " / " + template.getName());
                    }
                }
            }
            return usages;
        }
    }
}
