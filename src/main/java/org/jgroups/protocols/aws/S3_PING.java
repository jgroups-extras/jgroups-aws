package org.jgroups.protocols.aws;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;

import org.jgroups.Address;
import org.jgroups.annotations.Property;
import org.jgroups.aws.s3.NATIVE_S3_PING;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.Util;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * This implementation uses the AWS SDK to be more solid and to benefit from the built-in security features
 * like getting credentials via IAM instance profiles instead of handling this in the application.
 *
 * @author Tobias Sarnowski
 * @author Bela Ban
 * @author Radoslav Husar
 */
public class S3_PING extends FILE_PING {
    protected static final short  JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER=789;
    protected static final int    SERIALIZATION_BUFFER_SIZE=4096;
    protected static final String SERIALIZED_CONTENT_TYPE="text/plain";
    protected static final String MAGIC_NUMBER_SYSTEM_PROPERTY="s3ping.magic_number";

    @Property(description = "Forces the AWS S3 client to use path-style addressing for buckets (default: false).",
            systemProperty = {"jgroups.aws.s3.path_style_access_enabled", "JGROUPS_AWS_S3_PATH_STYLE_ACCESS_ENABLED"},
            writable = false)
    protected boolean path_style_access_enabled;

    @Property(description = "The AWS endpoint with which to communicate (optional).",
            systemProperty = {"jgroups.aws.s3.endpoint", "JGROUPS_AWS_S3_ENDPOINT"},
            writable = false)
    protected String endpoint;

    @Property(description = "The AWS region with which to communicate (required).",
            systemProperty = {"jgroups.aws.s3.region_name", "JGROUPS_AWS_S3_REGION_NAME"},
            writable = false)
    protected String region_name;

    @Property(description = "The AWS S3 bucket name to use (required).",
            systemProperty = {"jgroups.aws.s3.bucket_name", "JGROUPS_AWS_S3_BUCKET_NAME"},
            writable = false)
    protected String bucket_name;

    @Property(description = "The prefix to prefix all AWS S3 paths with, e.g. 'jgroups/' (optional).",
            systemProperty = {"jgroups.aws.s3.bucket_prefix", "JGROUPS_AWS_S3_BUCKET_PREFIX"},
            writable = false)
    protected String bucket_prefix;

    @Property(description = "Whether to check if the bucket exists in AWS S3 and create a new one if it does not exist yet (default: true).",
            systemProperty = {"jgroups.aws.s3.check_if_bucket_exists", "JGROUPS_AWS_S3_CHECK_IF_BUCKET_EXISTS"},
            writable = false)
    protected boolean check_if_bucket_exists = true;

    @Property(description = "Whether to grant the bucket owner full control over the bucket on each update. This is useful in multi-region deployments where each region exists in its own AWS account (default: false).",
            systemProperty = {"jgroups.aws.s3.acl_grant_bucket_owner_full_control", "JGROUPS_AWS_S3_ACL_GRANT_BUCKET_OWNER_FULL_CONTROL"},
            writable = false)
    protected boolean acl_grant_bucket_owner_full_control;

    @Property(description = "KMS key to use for enabling KMS server-side encryption (SSE-KMS) for AWS S3 (optional).",
            systemProperty = {"jgroups.aws.s3.kms_key_id", "JGROUPS_AWS_S3_KMS_KEY_ID"},
            exposeAsManagedAttribute = false)
    protected String kms_key_id;

    protected S3Client s3Client;

    static {
        short magicNumber=JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER;
        if(isDefined(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY))) {
            try {
                magicNumber=Short.parseShort(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY));
            } catch (NumberFormatException e) {
                LogFactory.getLog(S3_PING.class).warn("Could not convert provided property '%s' to short. Using default magic number: %d.",
                        System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY), JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER);
            }
        }
        //noinspection deprecation
        ClassConfigurator.addProtocol(magicNumber, NATIVE_S3_PING.class);
        ClassConfigurator.addProtocol(++magicNumber, S3_PING.class);
    }

    @Override
    public void init() throws Exception {
        super.init();

        if (bucket_prefix == null || bucket_prefix.equals("/")) {
            bucket_prefix = "";
        } else if (!bucket_prefix.endsWith("/") && !bucket_prefix.isEmpty()) {
            bucket_prefix = bucket_prefix + "/";
        }

        S3ClientBuilder builder = S3Client.builder();
        builder.credentialsProvider(DefaultCredentialsProvider.builder().build());

        // TODO Is this meant to replace #withPathStyleAccessEnabled ?
        builder.forcePathStyle(path_style_access_enabled);

        Region region = Region.of(region_name);
        builder.region(region);

        if (isDefined(endpoint)) {
            builder.endpointOverride(new URI(endpoint));
            log.info("Overriding AWS endpoint to '%s'.", endpoint);
        }
        s3Client = builder.build();
        log.info("Using AWS S3 ping in region '%s' with bucket '%s' and prefix '%s'.", region, bucket_name, bucket_prefix);

        if(!check_if_bucket_exists) return;

        boolean bucket_exists;
        HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(bucket_name).build();
        try {
            s3Client.headBucket(headBucketRequest);
            bucket_exists = true;
        } catch (NoSuchBucketException ignore) {
            bucket_exists = false;
        }

        if (!bucket_exists) {
            log.info("Bucket '%s' does not exist, creating it.", bucket_name);
            CreateBucketRequest createBucketRequest = CreateBucketRequest.builder().bucket(bucket_name).build();
            try {
                s3Client.createBucket(createBucketRequest);
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException exception) {
                log.info("Attempted to create bucket '%s' but it already exists.", bucket_name);
                return;
            }
            log.info("Created bucket '%s'.", bucket_name);
        } else {
            log.info("Found bucket '%s'.", bucket_name);
        }
    }

    @Override
    protected void createRootDir() {
        // ignore, bucket has to exist
    }

    protected String getClusterPrefix(final String clusterName) {
        return bucket_prefix + clusterName + "/";
    }

    @Override
    protected void readAll(final List<Address> members, final String clustername, final Responses responses) {
        if(clustername == null)
            return;

        final String clusterPrefix=getClusterPrefix(clustername);

        if(log.isTraceEnabled())
            log.trace("Getting entries for cluster '%s'.", clusterPrefix);

        try {
            ListObjectsRequest listObjectsRequest = ListObjectsRequest.builder().bucket(bucket_name).prefix(clusterPrefix).build();
            final ListObjectsResponse objects = s3Client.listObjects(listObjectsRequest);

            if(log.isTraceEnabled())
                log.trace("Got object listing, %d entries for cluster '%s'.", objects.contents().size(), clusterPrefix);

            // TODO batching not supported; can result in wrong lists if bucket has too many entries

            for (final S3Object s3Object : objects.contents()) {
                if (log.isTraceEnabled())
                    log.trace("Fetching data for object '%s'.", s3Object.key());

                if (s3Object.size() > 0) {
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket_name).key(s3Object.key()).build();
                    ResponseBytes<GetObjectResponse> objectAsBytes = s3Client.getObjectAsBytes(getObjectRequest);

                    if (log.isTraceEnabled()) {
                        log.trace("Parsing data for object '%s': '%s'.", s3Object.key(), objectAsBytes.toString());
                    }

                    final List<PingData> data = this.read(objectAsBytes.asInputStream());
                    if (data == null) {
                        log.debug("Fetched update for cluster '%s' member list in AWS S3 is empty.", clusterPrefix);
                        break;
                    }
                    for (final PingData pingData : data) {
                        if (members == null || members.contains(pingData.getAddress())) {
                            responses.addResponse(pingData, pingData.isCoord());
                            if (log.isTraceEnabled())
                                log.trace("Added member '%s', members '%s'.", pingData, members != null);
                        }
                        if (local_addr != null && !local_addr.equals(pingData.getAddress())) {
                            addDiscoveryResponseToCaches(pingData.getAddress(), pingData.getLogicalName(), pingData.getPhysicalAddr());
                            if (log.isTraceEnabled()) {
                                log.trace("Added possible member '%s' with local address '%s'.", pingData, local_addr);
                            }
                        }
                        if (log.isTraceEnabled())
                            log.trace("Processed entry in AWS S3: '%s' -> '%s'.", s3Object.key(), pingData);
                    }
                } else {
                    if (log.isTraceEnabled())
                        log.trace("Skipping empty object '%s'.", s3Object.key());
                }
            }
            log.debug("Fetched update for member list in AWS S3 for cluster '%s'.", clusterPrefix);
        } catch (final Exception e) {
            log.error(String.format("Failed getting member list from AWS S3 for cluster '%s'.", clusterPrefix), e);
        }
    }

    @Override
    protected void write(final List<PingData> list, final String clustername) {
        final String filename=addressToFilename(local_addr);
        final String key = getClusterPrefix(clustername) + filename;

        try {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream(SERIALIZATION_BUFFER_SIZE);
            this.write(list, outStream);
            final byte[] data = outStream.toByteArray();

            if (log.isTraceEnabled()) {
                log.trace("New AWS S3 file content (%d bytes): %s", data.length, new String(data));
            }

            PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                    .bucket(bucket_name)
                    .key(key)
                    .contentType(SERIALIZED_CONTENT_TYPE);

            if (acl_grant_bucket_owner_full_control) {
                putRequestBuilder.acl(ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL);
            }

            if (isDefined(kms_key_id)) {
                putRequestBuilder.serverSideEncryption(ServerSideEncryption.AWS_KMS);
                putRequestBuilder.ssekmsKeyId(kms_key_id);
            }

            RequestBody requestBody = RequestBody.fromBytes(data);
            s3Client.putObject(putRequestBuilder.build(), requestBody);

            log.debug("Wrote member list to AWS S3: '%s' -> '%s'.", key, list);
        } catch (final Exception e) {
            log.error(String.format("Failed to update member list in AWS S3 in '%s'.", key), e);
        }
    }

    @Override
    protected void remove(final String clustername, final Address addr) {
        if(clustername == null || addr == null)
            return;
        String filename=addressToFilename(addr);
        String key=getClusterPrefix(clustername) + filename;
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(bucket_name).key(key).build();
            s3Client.deleteObject(deleteObjectRequest);
            if(log.isTraceEnabled())
                log.trace("Removing key '%s'.", key);
        }
        catch(Exception e) {
            log.error(Util.getMessage("FailureRemovingData"), e);
        }
    }

    @Override
    protected void removeAll(String clustername) {
        if(clustername == null)
            return;

        final String clusterPrefix=getClusterPrefix(clustername);

        try {
            final ListObjectsRequest listRequest = ListObjectsRequest.builder().bucket(bucket_name).prefix(clusterPrefix).build();
            final ListObjectsResponse objects = s3Client.listObjects(listRequest);

            if(log.isTraceEnabled())
                log.trace("Got object listing, '%d' entries for cluster '%s'.", objects.contents().size(), clusterPrefix);

            for(final S3Object object : objects.contents()) {
                if(log.isTraceEnabled())
                    log.trace("Fetching data for object '%s'.", object.key());
                try {
                    DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(bucket_name).key(object.key()).build();
                    s3Client.deleteObject(deleteObjectRequest);
                    if(log.isTraceEnabled())
                        log.trace("Removing '%s'.", object.key());
                }
                catch(Throwable t) {
                    log.error("Failed deleting object '%s': %s", object.key(), t);
                }
            }
        }
        catch(Exception ex) {
            log.error(Util.getMessage("FailedDeletingAllObjects"), ex);
        }
    }

    private static boolean isDefined(String s) {
        return (s != null && !s.trim().isEmpty());
    }
}
