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

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.jgroups.util.Util;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.testcontainers.DockerClientFactory;

import static org.junit.Assert.fail;

/**
 * Tests against a containerized mock S3 service.
 *
 * @author Radoslav Husar
 */
public class MockS3_PINGDiscoveryTestCase extends AbstractS3_PINGDiscoveryTestCase {

    private static S3MockContainer s3Mock;

    @BeforeClass
    public static void setUp() {
        // If credentials are available, we can conditionally skip Mock tests
        if (isGenuineCredentialsAvailable() || (!isDockerAvailable() && !Util.checkForLinux())) {
            Assume.assumeTrue("Podman/Docker environment is not available - skipping tests against S3 mock service.", isDockerAvailable());
        } else if (!isDockerAvailable()) {
            fail("Credentials are not provided, thus Podman/Docker on Linux is required to run tests against a mock service!");
        }

        // TODO Since 3.3.0 the obscure cluster name tests start to fail. Manage the version manually and keep on '3.2.0' for now.
        s3Mock = new S3MockContainer("3.2.0");
        s3Mock.start();

        // TODO workaround using S3MockContainer#getHttpEndpoint() by an IP address so it doesn't rely on spoofing DNS records
        System.setProperty("org.jgroups.aws.endpoint", "http://127.0.0.1:" + s3Mock.getHttpServerPort());

        // Setup fake credentials against the mock service
        System.setProperty("aws.accessKeyId", "foo");
        System.setProperty("aws.secretAccessKey", "bar");
        System.setProperty("S3_PING_BUCKET_NAME", "testing-ping");
    }

    @AfterClass
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
