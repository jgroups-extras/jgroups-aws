/*
 * Copyright 2022 Red Hat Inc., and individual contributors
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jgroups.JChannel;
import org.jgroups.util.Util;
import org.junit.jupiter.api.Test;

/**
 * Functional tests for S3_PING discovery.
 *
 * @author Radoslav Husar
 */
public abstract class AbstractS3_PINGDiscoveryTestCase {

    public static final int CHANNEL_COUNT = 5;

    // The cluster names need to randomized so that multiple test runs can be run in parallel with the same
    // credentials (e.g. running JDK8 and JDK9 on the CI).
    public static final String RANDOM_CLUSTER_NAME = UUID.randomUUID().toString();

    static boolean areGenuineCredentialsAvailable() {
        return System.getenv("AWS_ACCESS_KEY_ID") != null && System.getenv("AWS_SECRET_ACCESS_KEY") != null;
    }

    @Test
    public void testDiscovery() throws Exception {
        discover(RANDOM_CLUSTER_NAME, S3_PING.class.getSimpleName());
    }

    @Test
    public void testDiscoveryWithBucketPrefix() throws Exception {
        String bucketPrefixProperty = "jgroups.aws.s3.bucket_prefix";

        System.setProperty(bucketPrefixProperty, "my-other-test-prefix");
        discover(RANDOM_CLUSTER_NAME, S3_PING.class.getSimpleName());
        System.clearProperty(bucketPrefixProperty);
    }

    @Test
    public void testDiscoveryObscureClusterName() throws Exception {
        String obscureClusterName = "``\\//--+ěščřžýáíé==''!@#$%^&*()_{}<>?";
        discover(obscureClusterName + RANDOM_CLUSTER_NAME, S3_PING.class.getSimpleName());
    }

    private void discover(String clusterName, String stackName) throws Exception {
        List<JChannel> channels = create(clusterName, stackName);

        Thread.sleep(TimeUnit.SECONDS.toMillis(2));

        printViews(channels);

        // Asserts the views are there
        for (JChannel channel : channels) {
            assertEquals(CHANNEL_COUNT, channel.getView().getMembers().size(), "member count");
        }

        // Stop all channels
        // n.b. all channels must be closed, only disconnecting all concurrently can leave stale data
        for (JChannel channel : channels) {
            channel.close();
        }
    }

    private List<JChannel> create(String clusterName, String stackName) throws Exception {
        List<JChannel> result = new LinkedList<>();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            JChannel channel = new JChannel("org/jgroups/protocols/aws/tcp-" + stackName + ".xml");

            channel.connect(clusterName);
            if (i == 0) {
                // Let's be clear about the coordinator
                Util.sleep(1000);
            }
            result.add(channel);
        }
        return result;
    }

    protected static void printViews(List<JChannel> channels) {
        for (JChannel ch : channels) {
            System.out.println("Channel " + ch.getName() + " has view " + ch.getView());
        }
    }
}
