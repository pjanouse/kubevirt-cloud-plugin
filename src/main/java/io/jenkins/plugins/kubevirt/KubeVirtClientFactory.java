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
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import hudson.security.ACL;
import io.jenkins.plugins.kubevirt.internal.KubeVirt;
import jenkins.model.Jenkins;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating {@link KubeVirt} client instances.
 * Handles credentials lookup from Jenkins and client configuration.
 */
public class KubeVirtClientFactory {

    private static final Logger LOGGER = Logger.getLogger(KubeVirtClientFactory.class.getName());

    /**
     * Creates a new KubeVirt client using the provided cloud configuration.
     *
     * @param config The cloud configuration containing connection details
     * @return A configured KubeVirt client instance (caller is responsible for closing)
     * @throws IllegalStateException if credentials cannot be found
     */
    public KubeVirt createClient(KubeVirtCloudConfig config) {
        return createClient(config.getServerUrl(), config.getCredentialsId(),
                config.getNamespace(), config.isIgnoreSsl());
    }

    /**
     * Creates a new KubeVirt client with the specified parameters.
     *
     * @param serverUrl     The Kubernetes API server URL
     * @param credentialsId The ID of the credentials for authentication
     * @param namespace     The Kubernetes namespace
     * @param ignoreSsl     Whether to ignore SSL certificate errors
     * @return A configured KubeVirt client instance (caller is responsible for closing)
     * @throws IllegalStateException if credentials cannot be found
     */
    public KubeVirt createClient(String serverUrl, String credentialsId,
                                  String namespace, boolean ignoreSsl) {
        String token = lookupToken(credentialsId);
        return new KubeVirt(serverUrl, token, namespace, ignoreSsl);
    }

    /**
     * Creates a new KubeVirt client with custom timeouts.
     * Useful for connectivity checks where a shorter timeout is desired.
     *
     * @param config              The cloud configuration containing connection details
     * @param connectionTimeoutMs Connection timeout in milliseconds
     * @param requestTimeoutMs    Request timeout in milliseconds
     * @return A configured KubeVirt client instance (caller is responsible for closing)
     * @throws IllegalStateException if credentials cannot be found
     */
    public KubeVirt createClientWithTimeout(KubeVirtCloudConfig config,
                                             int connectionTimeoutMs, int requestTimeoutMs) {
        String token = lookupToken(config.getCredentialsId());
        return new KubeVirt(config.getServerUrl(), token, config.getNamespace(),
                config.isIgnoreSsl(), connectionTimeoutMs, requestTimeoutMs);
    }

    /**
     * Looks up the authentication token from Jenkins credentials.
     * Supports both StringCredentials (Secret text) and StandardUsernamePasswordCredentials.
     *
     * @param credentialsId The credentials ID to look up
     * @return The authentication token
     * @throws IllegalStateException if credentials are not found or token is empty
     */
    String lookupToken(String credentialsId) {
        // First, try StringCredentials (Secret text)
        StringCredentials stringCreds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.emptyList()
                ),
                CredentialsMatchers.withId(credentialsId)
        );

        if (stringCreds != null) {
            String token = stringCreds.getSecret().getPlainText();
            if (token.isBlank()) {
                LOGGER.log(Level.WARNING, "Token is empty for credentials: {0}", credentialsId);
                return "";
            }
            return token;
        }

        // Fall back to StandardUsernamePasswordCredentials
        StandardUsernamePasswordCredentials userPassCreds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.emptyList()
                ),
                CredentialsMatchers.withId(credentialsId)
        );

        if (userPassCreds == null) {
            LOGGER.log(Level.SEVERE, "Credentials not found with ID: {0}", credentialsId);
            throw new IllegalStateException("Credentials not found with ID: " + credentialsId);
        }

        String token = userPassCreds.getPassword().getPlainText();
        if (token.isBlank()) {
            LOGGER.log(Level.WARNING, "Token is empty for credentials: {0}", credentialsId);
            return "";
        }

        return token;
    }
}
