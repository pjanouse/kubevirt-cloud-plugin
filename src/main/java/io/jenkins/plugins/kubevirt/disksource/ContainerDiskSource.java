/*
 * The MIT License — see source repository for full text.
 */

package io.jenkins.plugins.kubevirt.disksource;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.kubevirt.KubeVirtStaplerSecurity;
import io.jenkins.plugins.kubevirt.ProvisioningCallback;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class ContainerDiskSource extends KubeVirtDiskSource {

    private final String image;

    @DataBoundConstructor
    public ContainerDiskSource(String image) {
        this.image = image;
    }

    @Override
    public String getImage() {
        return image;
    }

    @Override
    public boolean isContainerDisk() {
        return true;
    }

    @Override
    public void createVm(KubeVirt virt, VmProvisioningContext context, ProvisioningCallback callback) {
        callback.log("Creating VM from ContainerDisk: " + image);
        callback.log("Disk size: " + context.getDiskSize() + ", Access mode: " + context.getAccessMode()
                + DiskSourceSupport.resourceLog(context));
        virt.createVM(
                context.getNodeName(),
                context.getCloudName(),
                context.getTemplateName(),
                image,
                context.getDiskSize(),
                context.getAccessMode(),
                context.getCpu(),
                context.getMemory(),
                context.getCpuLimit(),
                context.getMemoryLimit(),
                context.getCloudInitContent(),
                context.isInjectTmpDiskConfig(),
                callback);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<KubeVirtDiskSource> {

        @Override
        public String getDisplayName() {
            return "ContainerDisk";
        }

        @POST
        public FormValidation doCheckImage(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Container disk image is required for ContainerDisk.");
            }
            String image = value.trim();
            if (!image.matches("^[a-zA-Z0-9][a-zA-Z0-9._/-]*(:[a-zA-Z0-9._-]+)?(@sha256:[a-f0-9]+)?$")) {
                return FormValidation.warning(
                        "Image format may be invalid. Expected format: registry/image:tag or registry/image@sha256:digest");
            }
            return FormValidation.ok();
        }
    }
}
