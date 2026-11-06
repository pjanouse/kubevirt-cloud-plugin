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

public class DataSourceCloneDiskSource extends KubeVirtDiskSource {

    private final String dataSourceNamespace;
    private final String dataSourceName;

    @DataBoundConstructor
    public DataSourceCloneDiskSource(String dataSourceNamespace, String dataSourceName) {
        this.dataSourceNamespace = (dataSourceNamespace != null && !dataSourceNamespace.isEmpty())
                ? dataSourceNamespace
                : KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        this.dataSourceName = dataSourceName;
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
    public boolean isDataSourceClone() {
        return true;
    }

    @Override
    public void createVm(KubeVirt virt, VmProvisioningContext context, ProvisioningCallback callback) {
        callback.log("Creating VM from DataSource: " + dataSourceNamespace + "/" + dataSourceName);
        callback.log("Disk size: " + context.getDiskSize() + ", Access mode: " + context.getAccessMode()
                + DiskSourceSupport.resourceLog(context));
        DiskSourceSupport.requireDataSourceFields(dataSourceNamespace, dataSourceName);
        virt.createVMFromDataSource(
                context.getNodeName(),
                context.getCloudName(),
                context.getTemplateName(),
                dataSourceNamespace,
                dataSourceName,
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
            return "DataSource (Clone)";
        }

        public String getDefaultDataSourceNamespace() {
            return KubeVirtConfiguration.DEFAULT_DATASOURCE_NAMESPACE;
        }

        @POST
        public FormValidation doCheckDataSourceName(@QueryParameter String value) {
            return DiskSourceSupport.checkDataSourceName(value, "DataSource (Clone)");
        }

        @POST
        public FormValidation doCheckDataSourceNamespace(@QueryParameter String value) {
            return DiskSourceSupport.checkDataSourceNamespace(value, "DataSource (Clone)");
        }
    }
}
