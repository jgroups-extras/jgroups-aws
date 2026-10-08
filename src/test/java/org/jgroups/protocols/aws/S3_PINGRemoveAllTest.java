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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jgroups.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A failure in {@link S3_PING#removeAll(String)} or {@link S3_PING#remove(String, org.jgroups.Address)} is logged, not
 * thrown: with {@code remove_all_data_on_view_change} these methods run from {@code FILE_PING.handleView()} during a
 * view change.
 */
public class S3_PINGRemoveAllTest {

    private final Logger logger = Logger.getLogger(S3_PING.class.getName());
    private final List<LogRecord> errors = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue())
                errors.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

    @BeforeEach
    public void addHandler() {
        logger.addHandler(handler);
    }

    @AfterEach
    public void removeHandler() {
        logger.removeHandler(handler);
    }

    @Test
    public void testFailedListingIsLoggedNotThrown() {
        SdkClientException failure = SdkClientException.create("Unable to load credentials");
        S3_PING ping = createPing((method, args) -> {
            throw failure;
        });

        assertDoesNotThrow(() -> ping.removeAll("cluster"));

        assertThat(invocationCount("listObjects")).isEqualTo(1);
        assertThat(errors).singleElement().satisfies(record -> {
            assertThat(record.getMessage()).contains("cluster/");
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }

    @Test
    public void testErrorDuringListingIsLoggedNotThrown() {
        NoClassDefFoundError failure = new NoClassDefFoundError("software/amazon/awssdk/http/SdkHttpClient");
        S3_PING ping = createPing((method, args) -> {
            throw failure;
        });

        assertDoesNotThrow(() -> ping.removeAll("cluster"));

        assertThat(invocationCount("listObjects")).isEqualTo(1);
        assertThat(errors).singleElement().satisfies(record -> assertThat(record.getThrown()).isSameAs(failure));
    }

    @Test
    public void testFailedDeletesAreLoggedAndRemainingObjectsAreDeleted() {
        SdkClientException failure = SdkClientException.create("Access Denied");
        S3_PING ping = createPing((method, args) -> {
            if (method.getName().equals("listObjects")) {
                return ListObjectsResponse.builder()
                        .contents(S3Object.builder().key("cluster/a").build(), S3Object.builder().key("cluster/b").build())
                        .build();
            }
            throw failure;
        });

        assertDoesNotThrow(() -> ping.removeAll("cluster"));

        assertThat(invocationCount("deleteObject")).isEqualTo(2);
        assertThat(errors).hasSize(2).allSatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
        assertThat(errors.get(0).getMessage()).contains("cluster/a");
        assertThat(errors.get(1).getMessage()).contains("cluster/b");
    }

    @Test
    public void testFailedRemoveIsLoggedNotThrown() {
        SdkClientException failure = SdkClientException.create("Access Denied");
        S3_PING ping = createPing((method, args) -> {
            throw failure;
        });

        assertDoesNotThrow(() -> ping.remove("cluster", UUID.randomUUID()));

        assertThat(invocationCount("deleteObject")).isEqualTo(1);
        assertThat(errors).singleElement().satisfies(record -> {
            assertThat(record.getMessage()).contains("cluster/");
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }

    private int invocationCount(String methodName) {
        AtomicInteger count = invocations.get(methodName);
        return count == null ? 0 : count.get();
    }

    /**
     * Creates an {@link S3_PING} whose client delegates S3 operations to the given behavior and counts them;
     * {@link Object} methods are handled by the proxy itself.
     */
    private S3_PING createPing(S3Behavior behavior) {
        S3_PING ping = new S3_PING();
        ping.bucket_name = "bucket";
        ping.bucket_prefix = "";
        ping.s3Client = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals":
                                return proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            default:
                                return "S3Client proxy";
                        }
                    }
                    invocations.computeIfAbsent(method.getName(), name -> new AtomicInteger()).incrementAndGet();
                    return behavior.invoke(method, args);
                });
        return ping;
    }

    @FunctionalInterface
    private interface S3Behavior {
        Object invoke(Method method, Object[] args) throws Throwable;
    }
}
