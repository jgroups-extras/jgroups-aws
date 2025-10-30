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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;

/**
 * Tests against genuine S3 endpoint requiring credentials.
 *
 * @author Radoslav Husar
 */
public class GenuineS3_PINGDiscoveryTestCase extends AbstractS3_PINGDiscoveryTestCase {

    @BeforeAll
    public static void assumeCredentials() {
        assumeTrue(areGenuineCredentialsAvailable(), "Credentials are not available, test will be ignored!");
    }
}
