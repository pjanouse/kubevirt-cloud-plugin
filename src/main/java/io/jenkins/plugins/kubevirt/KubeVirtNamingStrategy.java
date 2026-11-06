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

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strategy for generating RFC 1123 compliant node names for KubeVirt VMs.
 *
 * Generates names in the format: jenkins-{template-name}-{build-number}-{random}
 *
 * RFC 1123 requirements:
 * - Lowercase alphanumeric characters
 * - Dashes (-) and dots (.) allowed
 * - Must start and end with alphanumeric character
 *
 * <p>This class is thread-safe. The SecureRandom instance is stored in a ThreadLocal
 * to avoid contention between threads while maintaining cryptographic security.</p>
 */
public class KubeVirtNamingStrategy {

    private static final AtomicInteger BUILD_COUNTER = new AtomicInteger(1);

    /**
     * Thread-local SecureRandom to ensure thread safety without synchronization overhead.
     * Each thread gets its own SecureRandom instance, avoiding contention.
     */
    private static final ThreadLocal<SecureRandom> RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    /**
     * Generates a valid RFC 1123 compliant node name.
     * Format: jenkins-{sanitized-template-name}-{build-number}-{random-suffix}
     *
     * <p>This method is thread-safe and can be called concurrently.</p>
     *
     * @param templateName The template name to include in the generated name
     * @return A valid Kubernetes-compatible node name
     */
    public String generateNodeName(String templateName) {
        String sanitizedName = sanitizeTemplateName(templateName);
        int buildNumber = BUILD_COUNTER.getAndIncrement();
        int randomSuffix = RANDOM.get().nextInt(10000);
        return String.format("jenkins-%s-%d-%04d", sanitizedName, buildNumber, randomSuffix);
    }

    /**
     * Sanitizes a template name to be RFC 1123 compliant.
     * - Converts to lowercase
     * - Replaces invalid characters with hyphens
     * - Removes leading/trailing hyphens
     *
     * @param templateName The template name to sanitize
     * @return A sanitized name suitable for use in Kubernetes resource names
     */
    String sanitizeTemplateName(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            return "vm";
        }

        String sanitized = templateName.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\-.]", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        return sanitized.isEmpty() ? "vm" : sanitized;
    }

    /**
     * Resets the build counter. Primarily for testing purposes.
     *
     * @param value The value to reset the counter to
     */
    void resetCounter(int value) {
        BUILD_COUNTER.set(value);
    }
}
