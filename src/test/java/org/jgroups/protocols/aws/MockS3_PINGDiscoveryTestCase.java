/*
 * Copyright 2023 Red Hat Inc., and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jgroups.protocols.aws;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.jgroups.util.Util;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

/**
 * Tests against a containerized mock S3 service.
 *
 * @author Radoslav Husar
 */
public class MockS3_PINGDiscoveryTestCase extends AbstractS3_PINGDiscoveryTestCase {

    private static S3MockContainer s3Mock;

    @BeforeAll
    public static void setUp() {
        // If credentials are available, we can conditionally skip Mock tests
        if (areGenuineCredentialsAvailable() || (!isDockerAvailable() && !Util.checkForLinux())) {
            assumeTrue(isDockerAvailable(), "Podman/Docker environment is not available - skipping tests against S3 mock service.");
        } else if (!isDockerAvailable()) {
            fail("Credentials are not provided, thus Podman/Docker on Linux is required to run tests against a mock service!");
        }

        // Using 'latest' here often breaks the tests (e.g. like version 3.3.0 did).
        // In that case the version will have to be explicitly managed here for reproducible builds/CI.
        // n.b. for reference https://hub.docker.com/r/adobe/s3mock/tags
        s3Mock = new S3MockContainer("3.12.0");
        s3Mock.start();

        // Configure the protocol - it has no hardcoded values in the stack xml file, so we can set all values using properties
        // TODO workaround using S3MockContainer#getHttpsEndpoint() by an IP address so it doesn't rely on spoofing DNS records
        // TODO switch to TLS
        System.setProperty("jgroups.aws.s3.endpoint", "http://127.0.0.1:" + s3Mock.getHttpServerPort());
        System.setProperty("jgroups.aws.s3.region_name", "ping-testing-region");
        System.setProperty("jgroups.aws.s3.bucket_name", "ping-test-bucket");

        // Setup fake credentials against the mock service
        System.setProperty("aws.accessKeyId", "foo");
        System.setProperty("aws.secretAccessKey", "bar");
    }

    @AfterAll
    public static void cleanup() {
        if (s3Mock != null) {
            s3Mock.stop();
        }
    }

    private static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }
}
