/*
 * Copyright 2026 Red Hat Inc., and individual contributors
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A failure in {@link S3_PING#removeAll(String)} is logged, not thrown: with {@code remove_all_data_on_view_change}
 * the method runs from {@code FILE_PING.handleView()} during a view change.
 */
public class S3_PINGRemoveAllTest {

    @Test
    public void testFailedListingIsLoggedNotThrown() {
        S3_PING ping = new S3_PING();
        ping.bucket_name = "bucket";
        ping.bucket_prefix = "";
        ping.s3Client = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, method, args) -> {
                    throw SdkClientException.create("Unable to load credentials");
                });

        assertDoesNotThrow(() -> ping.removeAll("cluster"));
    }
}
