/*
 * The MIT License — see source repository for full text.
 */

package io.jenkins.plugins.kubevirt.disksource;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.kubevirt.KubeVirtConfiguration;
import io.jenkins.plugins.kubevirt.KubeVirtStaplerSecurity;
import io.jenkins.plugins.kubevirt.ProvisioningCallback;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class DataSourceImportDiskSource extends KubeVirtDiskSource {

    private final String dataSourceNamespace;
    private final String dataSourceName;
    private final String image;

    @DataBoundConstructor
    public DataSourceImportDiskSource(String dataSourceNamespace, String dataSourceName, String image) {
        this.dataSourceNamespace = (dataSourceNamespace != null && !dataSourceNamespace.isEmpty())
                ? dataSourceNamespace
                : KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        this.dataSourceName = dataSourceName;
        this.image = image;
    }

    @Override
    public String getImage() {
        return image;
    }

    @Override
    public String getDataSourceNamespace() {
        return dataSourceNamespace;
    }

    @Override
    public String getDataSourceName() {
        return dataSourceName;
    }

    @Override
    public boolean isDataSourceImport() {
        return true;
    }

    @Override
    public void createVm(KubeVirt virt, VmProvisioningContext context, ProvisioningCallback callback) {
        callback.log("Creating VM from DataSource Import: " + image);
        callback.log("DataSource: " + dataSourceNamespace + "/" + dataSourceName);
        callback.log("Disk size: " + context.getDiskSize() + ", Access mode: " + context.getAccessMode()
                + DiskSourceSupport.resourceLog(context));
        DiskSourceSupport.requireDataSourceFields(dataSourceNamespace, dataSourceName);
        virt.createVMFromDataSourceImport(
                context.getNodeName(),
                context.getCloudName(),
                context.getTemplateName(),
                dataSourceNamespace,
                dataSourceName,
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
            return "DataSource (Import)";
        }

        public String getDefaultDataSourceNamespace() {
            return KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        }

        @POST
        public FormValidation doCheckImage(@QueryParameter String value) {
            if (!KubeVirtStaplerSecurity.canManage()) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Container disk image is required for DataSource (Import).");
            }
            String image = value.trim();
            if (!image.matches("^[a-zA-Z0-9][a-zA-Z0-9._/-]*(:[a-zA-Z0-9._-]+)?(@sha256:[a-f0-9]+)?$")) {
                return FormValidation.warning(
                        "Image format may be invalid. Expected format: registry/image:tag or registry/image@sha256:digest");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckDataSourceName(@QueryParameter String value) {
            return DiskSourceSupport.checkDataSourceName(value, "DataSource (Import)");
        }

        @POST
        public FormValidation doCheckDataSourceNamespace(@QueryParameter String value) {
            return DiskSourceSupport.checkDataSourceNamespace(value, "DataSource (Import)");
        }
    }
}
