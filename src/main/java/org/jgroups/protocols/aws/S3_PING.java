package org.jgroups.protocols.aws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.util.StringUtils;
import org.jgroups.Address;
import org.jgroups.annotations.Property;
import org.jgroups.aws.s3.NATIVE_S3_PING;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.Util;

/**
 * This implementation uses the AWS SDK in order to be more solid and to benefit from the built-in security features
 * like getting credentials via IAM instance profiles instead of handling this in the application.
 *
 * @author Tobias Sarnowski
 * @author Bela Ban
 */
public class S3_PING extends FILE_PING {
    protected static final short  JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER=789;
    protected static final int    SERIALIZATION_BUFFER_SIZE=4096;
    protected static final String SERIALIZED_CONTENT_TYPE="text/plain";
    protected static final String MAGIC_NUMBER_SYSTEM_PROPERTY="s3ping.magic_number";

    protected static final AccessControlList BUCKET_OWNER_FULL_CONTROL_ACL = new AccessControlList();

    @Property(description="The S3 path-style enable (optional).", exposeAsManagedAttribute=false)
    protected boolean  path_style_access_enabled=false;

    @Property(description="The S3 endpoint to use (optional).", exposeAsManagedAttribute=false)
    protected String   endpoint;

    @Property(description="The S3 region to use.", exposeAsManagedAttribute=false)
    protected String   region_name;

    @Property(description="The S3 bucket to use.", exposeAsManagedAttribute=false)
    protected String   bucket_name;

    @Property(description="The S3 bucket prefix to use (optional e.g. 'jgroups/').", exposeAsManagedAttribute=false)
    protected String   bucket_prefix;

    @Property(description="Checks if the bucket exists in S3 and creates a new one if missing")
    protected boolean  check_if_bucket_exists=true;

    @Property(description = "Flag indicating whether or not to grant the bucket owner full control over the bucket  " +
        "on each update. This is useful in multi-region deployments where each region exists in its own AWS account.")
    protected boolean acl_grant_bucket_owner_full_control = false;

    @Property(description="Use kms encryption with s3 with the given kms key (optionally - enables KMS Server side encryption (SSE-KMS) using the given kms key)", exposeAsManagedAttribute=false)
    protected String  kms_key_id;

    protected AmazonS3 s3;

    protected SSEAwsKeyManagementParams encryptionParams;

    static {
        short magicNumber=JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER;
        if(!StringUtils.isNullOrEmpty(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY))) {
            try {
                magicNumber=Short.parseShort(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY));
            }
            catch(NumberFormatException e) {
                LogFactory.getLog(S3_PING.class).warn("Could not convert " + System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY)
                                                               + " to short. Using default magic number " + JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER);
            }
        }
        ClassConfigurator.addProtocol(magicNumber, NATIVE_S3_PING.class);
        ClassConfigurator.addProtocol(++magicNumber, S3_PING.class);
    }

    @Override
    public void init() throws Exception {
        super.init();

        if(bucket_prefix == null || bucket_prefix.equals("/"))
            bucket_prefix="";
        else if(!bucket_prefix.endsWith("/") && !bucket_prefix.isEmpty())
            bucket_prefix=bucket_prefix + "/";

        DefaultAWSCredentialsProviderChain creds=DefaultAWSCredentialsProviderChain.getInstance();
        AmazonS3ClientBuilder builder=AmazonS3ClientBuilder.standard().withCredentials(creds).withPathStyleAccessEnabled(path_style_access_enabled);
        if(!StringUtils.isNullOrEmpty(endpoint)) {
            builder=builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region_name));
            log.info("set Amazon S3 endpoint to %s", endpoint);
        } else {
            builder.withRegion(region_name);
        }
        s3=builder.build();
        log.info("using Amazon S3 ping in region %s with bucket '%s' and prefix '%s'", region_name, bucket_name, bucket_prefix);

        if (!StringUtils.isNullOrEmpty(kms_key_id)) {
            encryptionParams = new SSEAwsKeyManagementParams().withAwsKmsKeyId(kms_key_id);
            log.info("using S3 client with KMS");
        }

        if(!check_if_bucket_exists)
            return;
        boolean bucket_exists=s3.doesBucketExistV2(bucket_name);
        if(!bucket_exists) {
            log.info("bucket %s does not exist, creating it\n", bucket_name);
            s3.createBucket(bucket_name);
            log.info("created bucket %s\n", bucket_name);
        }
        else
            log.info("found bucket %s\n", bucket_name);

        // Initialize the bucket owner full control grant.
        if (acl_grant_bucket_owner_full_control) {
            BUCKET_OWNER_FULL_CONTROL_ACL.grantAllPermissions(new Grant(
                    new CanonicalGrantee(s3.getS3AccountOwner().getId()),
                    Permission.FullControl
            ));
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
            log.trace("getting entries for %s ...", clusterPrefix);

        try {
            final ObjectListing objectListing=s3.listObjects(
              new ListObjectsRequest()
                .withBucketName(bucket_name)
                .withPrefix(clusterPrefix));

            if(log.isTraceEnabled())
                log.trace("got object listing, %d entries [%s]", objectListing.getObjectSummaries().size(), clusterPrefix);

            // TODO batching not supported; can result in wrong lists if bucket has too many entries

            for(final S3ObjectSummary summary : objectListing.getObjectSummaries()) {
                if(log.isTraceEnabled())
                    log.trace("fetching data for object %s ...", summary.getKey());

                if(summary.getSize() > 0) {
                    final S3Object object=s3.getObject(new GetObjectRequest(summary.getBucketName(), summary.getKey()));
                    if(log.isTraceEnabled())
                        log.trace("parsing data for object %s (%s, %d bytes)...", summary.getKey(),
                                  object.getObjectMetadata().getContentType(), object.getObjectMetadata().getContentLength());

                    final List<PingData> data=read(object.getObjectContent());
                    if(data == null) {
                        log.debug("fetched update for member list in Amazon S3 is empty [%s]", clusterPrefix);
                        break;
                    }
                    for(final PingData pingData : data) {
                        if(members == null || members.contains(pingData.getAddress())) {
                            responses.addResponse(pingData, pingData.isCoord());
                            if(log.isTraceEnabled())
                                log.trace("added member %s [members: %s]", pingData, members != null);
                        }
                        if(local_addr != null && !local_addr.equals(pingData.getAddress())) {
                            addDiscoveryResponseToCaches(pingData.getAddress(), pingData.getLogicalName(),
                                                         pingData.getPhysicalAddr());
                            if(log.isTraceEnabled())
                                log.trace("added possible member %s [local address: %s]", pingData, local_addr);
                        }
                        if(log.isTraceEnabled())
                            log.trace("processed entry in Amazon S3 [%s -> %s]", summary.getKey(), pingData);
                    }
                }
                else {
                    if(log.isTraceEnabled())
                        log.trace("skipping object %s as it is empty", summary.getKey());
                }
            }
            log.debug("fetched update for member list in Amazon S3 [%s]", clusterPrefix);
        }
        catch(final Exception e) {
            log.error(String.format("failed getting member list from Amazon S3 [%s]", clusterPrefix), e);
        }
    }

    @Override
    protected void write(final List<PingData> list, final String clustername) {
        final String filename=addressToFilename(local_addr);
        final String key=getClusterPrefix(clustername) + filename;

        try {
            final ByteArrayOutputStream outStream=new ByteArrayOutputStream(SERIALIZATION_BUFFER_SIZE);
            write(list, outStream);

            final byte[] data=outStream.toByteArray();
            final ByteArrayInputStream inStream=new ByteArrayInputStream(data);
            final ObjectMetadata objectMetadata=new ObjectMetadata();
            objectMetadata.setContentType(SERIALIZED_CONTENT_TYPE);
            objectMetadata.setContentLength(data.length);

            if(log.isTraceEnabled())
                log.trace("new S3 file content (%d bytes): %s", data.length, new String(data));

            final PutObjectRequest putRequest =
                acl_grant_bucket_owner_full_control
                    ? new PutObjectRequest(bucket_name, key, inStream, objectMetadata)
                        .withAccessControlList(BUCKET_OWNER_FULL_CONTROL_ACL)
                    : new PutObjectRequest(bucket_name, key, inStream, objectMetadata);
            s3.putObject(encryptionParams != null ? putRequest.withSSEAwsKeyManagementParams(encryptionParams) : putRequest);
            log.debug("wrote member list to Amazon S3 [%s -> %s]", key, list);
        }
        catch(final Exception e) {
            log.error(String.format("failed to update member list in Amazon S3 [%s]", key), e);
        }
    }

    @Override
    protected void remove(final String clustername, final Address addr) {
        if(clustername == null || addr == null)
            return;
        String filename=addressToFilename(addr);
        String key=getClusterPrefix(clustername) + filename;
        try {
            s3.deleteObject(new DeleteObjectRequest(bucket_name, key));
            if(log.isTraceEnabled())
                log.trace("removing " + key);
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
            final ObjectListing objectListing=s3.listObjects(
              new ListObjectsRequest()
                .withBucketName(bucket_name)
                .withPrefix(clusterPrefix));

            if(log.isTraceEnabled())
                log.trace("got object listing, %d entries [%s]", objectListing.getObjectSummaries().size(), clusterPrefix);

            for(final S3ObjectSummary summary : objectListing.getObjectSummaries()) {
                if(log.isTraceEnabled())
                    log.trace("fetching data for object %s ...", summary.getKey());
                try {
                    s3.deleteObject(new DeleteObjectRequest(bucket_name, summary.getKey()));
                    if(log.isTraceEnabled())
                        log.trace("removing %s/%s", summary.getKey());
                }
                catch(Throwable t) {
                    log.error("failed deleting object %s/%s: %s", summary.getKey(), t);
                }
            }
        }
        catch(Exception ex) {
            log.error(Util.getMessage("FailedDeletingAllObjects"), ex);
        }
    }

}
