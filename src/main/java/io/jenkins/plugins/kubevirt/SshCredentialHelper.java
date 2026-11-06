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
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.security.ACL;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Collections;

/**
 * Utility class for looking up and validating SSH credentials from the Jenkins
 * credential store.
 *
 * <p>Centralises the credential lookup logic used during VM provisioning
 * ({@link KubeVirtProvisioningLauncher}) so it is not duplicated across
 * multiple classes.</p>
 */
public final class SshCredentialHelper {

    private SshCredentialHelper() {
        // utility class
    }

    /**
     * Looks up an SSH credential by ID from the Jenkins global credential store.
     *
     * @param credentialsId The credential ID to look up
     * @return The matching credential, or {@code null} if not found
     */
    public static StandardUsernameCredentials lookup(String credentialsId) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.emptyList()
                ),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    /**
     * Validates that SSH credentials are configured and resolvable.
     *
     * @param credentialsId The credential ID to validate
     * @return The resolved credential (never {@code null})
     * @throws IOException If the credential ID is blank or cannot be found
     */
    public static StandardUsernameCredentials lookupOrFail(String credentialsId) throws IOException {
        if (credentialsId == null || credentialsId.isEmpty()) {
            throw new IOException("SSH credentials not configured. "
                    + "Please configure SSH credentials in the template settings.");
        }

        StandardUsernameCredentials creds = lookup(credentialsId);
        if (creds == null) {
            throw new IOException("SSH credentials '" + credentialsId
                    + "' not found on this Jenkins controller. "
                    + "Ensure the credential exists with ID '" + credentialsId + "' and is of type "
                    + "'SSH Username with private key' or 'Username with password'. "
                    + "Go to Manage Jenkins > Credentials to verify.");
        }
        return creds;
    }
}
