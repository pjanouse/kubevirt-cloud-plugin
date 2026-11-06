/*
 * The MIT License — see source repository for full text.
 */

package io.jenkins.plugins.kubevirt.disksource;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.kubevirt.KubeVirtConfiguration;
import io.jenkins.plugins.kubevirt.ProvisioningCallback;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Disk provisioning strategy for a {@link io.jenkins.plugins.kubevirt.KubeVirtTemplate}.
 */
public abstract class KubeVirtDiskSource extends AbstractDescribableImpl<KubeVirtDiskSource> implements ExtensionPoint {

    /**
     * Creates the VM in Kubernetes using this disk source configuration.
     */
    public abstract void createVm(KubeVirt virt, VmProvisioningContext context, ProvisioningCallback callback);

    @CheckForNull
    public String getImage() {
        return null;
    }

    @CheckForNull
    public String getDataSourceNamespace() {
        return null;
    }

    @CheckForNull
    public String getDataSourceName() {
        return null;
    }

    public boolean isContainerDisk() {
        return false;
    }

    public boolean isDataSourceClone() {
        return false;
    }

    public boolean isDataSourceImport() {
        return false;
    }

    @Restricted(NoExternalUse.class)
    public static KubeVirtDiskSource fromLegacy(
            @CheckForNull String diskSourceType,
            @CheckForNull String image,
            @CheckForNull String dataSourceNamespace,
            @CheckForNull String dataSourceName) {
        String type = diskSourceType != null ? diskSourceType : KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK;
        if (KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE.equals(type)) {
            return new DataSourceCloneDiskSource(dataSourceNamespace, dataSourceName);
        }
        if (KubeVirtConfiguration.DISK_SOURCE_DATA_SOURCE_IMPORT.equals(type)) {
            return new DataSourceImportDiskSource(dataSourceNamespace, dataSourceName, image);
        }
        return new ContainerDiskSource(image);
    }

    @Override
    public Descriptor<KubeVirtDiskSource> getDescriptor() {
        return (Descriptor<KubeVirtDiskSource>) super.getDescriptor();
    }

    /**
     * Context passed to {@link #createVm} so implementations can read shared template settings.
     */
    public static final class VmProvisioningContext {
        private final String nodeName;
        private final String cloudName;
        private final String templateName;
        private final String diskSize;
        /** Kubernetes PVC access mode (ReadWriteOnce / ReadWriteMany), not a credential. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        private final String accessMode;
        private final String cpu;
        private final String memory;
        private final String cpuLimit;
        private final String memoryLimit;
        private final String cloudInitContent;
        private final boolean injectTmpDiskConfig;

        public VmProvisioningContext(
                String nodeName,
                String cloudName,
                String templateName,
                String diskSize,
                String accessMode,
                String cpu,
                String memory,
                String cpuLimit,
                String memoryLimit,
                String cloudInitContent,
                boolean injectTmpDiskConfig) {
            this.nodeName = Objects.requireNonNull(nodeName);
            this.cloudName = Objects.requireNonNull(cloudName);
            this.templateName = Objects.requireNonNull(templateName);
            this.diskSize = diskSize;
            this.accessMode = accessMode;
            this.cpu = cpu;
            this.memory = memory;
            this.cpuLimit = cpuLimit;
            this.memoryLimit = memoryLimit;
            this.cloudInitContent = cloudInitContent != null ? cloudInitContent : "";
            this.injectTmpDiskConfig = injectTmpDiskConfig;
        }

        public String getNodeName() {
            return nodeName;
        }

        public String getCloudName() {
            return cloudName;
        }

        public String getTemplateName() {
            return templateName;
        }

        public String getDiskSize() {
            return diskSize;
        }

        public String getAccessMode() {
            return accessMode;
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

        public String getCloudInitContent() {
            return cloudInitContent;
        }

        public boolean isInjectTmpDiskConfig() {
            return injectTmpDiskConfig;
        }
    }
}
