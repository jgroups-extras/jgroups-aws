package org.jgroups.s3;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.util.StringUtils;
import org.jgroups.Address;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Created after the original S3_PING from Bela Ban.
 *
 * This implementation uses the AWS SDK in order to be more solid and to benefit from the built-in security features
 * like getting credentials via IAM instance profiles instead of handling this in the application.<br/>
 * Ported to use JGroups 4.x by Bela Ban in 2017.
 *
 * @author Tobias Sarnowski
 * @author Bela Ban
 */
public class NATIVE_S3_PING extends FILE_PING {
    private static final short JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER = 789;

    private static final int SERIALIZATION_BUFFER_SIZE = 4096;
    private static final String SERIALIZED_CONTENT_TYPE = "text/plain";

    private static final String MAGIC_NUMBER_SYSTEM_PROPERTY = "s3ping.magic_number";

    @Property(description = "The S3 endpoint to use (optional).", exposeAsManagedAttribute = false)
    protected String endpoint;

    @Property(description = "The S3 region to use.", exposeAsManagedAttribute = false)
    protected String regionName;

    @Property(description = "The S3 bucket to use.", exposeAsManagedAttribute = false)
    protected String bucketName;

    @Property(description = "The S3 bucket prefix to use (optional e.g. 'jgroups/').", exposeAsManagedAttribute = false)
    protected String bucketPrefix;

    private AmazonS3 s3;

    static {
        short magicNumber = JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER;
        if (!StringUtils.isNullOrEmpty(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY))) {
            try {
                magicNumber = Short.parseShort(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY));
            } catch (NumberFormatException e) {
                LogFactory.getLog(NATIVE_S3_PING.class).warn("Could not convert " + System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY)
                    + " to short. Using default magic number " + JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER);
            }
        }
        registerProtocolWithJGroups(magicNumber);
    }

    @Override
    public void init() throws Exception {
        super.init();

        if (bucketPrefix == null || bucketPrefix.equals("/")) {
            bucketPrefix = "";
        } else if (!bucketPrefix.endsWith("/") && !bucketPrefix.isEmpty()) {
            bucketPrefix = bucketPrefix + "/";
        }

        s3 = new AmazonS3Client();

        if (endpoint != null) {
            s3.setEndpoint(endpoint);
            log.info("set Amazon S3 endpoint to %s", endpoint);
        }

        final Region region = Region.getRegion(Regions.fromName(regionName));
        s3.setRegion(region);

        log.info("using Amazon S3 ping in region %s with bucket '%s' and prefix '%s'", region, bucketName, bucketPrefix);
    }

    @Override
    protected void createRootDir() {
        // ignore, bucket has to exist
    }

    private String getClusterPrefix(final String clusterName) {
        return bucketPrefix + clusterName + "/";
    }

    @Override
    protected void readAll(final List<Address> members, final String clustername, final Responses responses) {
        if (clustername == null) {
            return;
        }

        final String clusterPrefix = getClusterPrefix(clustername);

        if (log.isTraceEnabled()) {
            log.trace("getting entries for %s ...", clusterPrefix);
        }

        try {
            final ObjectListing objectListing = s3.listObjects(
                    new ListObjectsRequest()
                            .withBucketName(bucketName)
                            .withPrefix(clusterPrefix));

            if (log.isTraceEnabled()) {
                log.trace("got object listing, %d entries [%s]", objectListing.getObjectSummaries().size(), clusterPrefix);
            }

            // TODO batching not supported; can result in wrong lists if bucket has too many entries

            for (final S3ObjectSummary summary : objectListing.getObjectSummaries()) {
                if (log.isTraceEnabled()) {
                    log.trace("fetching data for object %s ...", summary.getKey());
                }

                if (summary.getSize() > 0) {
                    final S3Object object = s3.getObject(new GetObjectRequest(summary.getBucketName(), summary.getKey()));
                    if (log.isTraceEnabled()) {
                        log.trace("parsing data for object %s (%s, %d bytes)...", summary.getKey(),
                                object.getObjectMetadata().getContentType(), object.getObjectMetadata().getContentLength());
                    }

                    final List<PingData> data = read(object.getObjectContent());
                    if (data == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("fetched update for member list in Amazon S3 is empty [%s]", clusterPrefix);
                        }
                        break;
                    }
                    for (final PingData pingData : data) {
                        if (members == null || members.contains(pingData.getAddress())) {
                            responses.addResponse(pingData, pingData.isCoord());
                            if (log.isTraceEnabled()) {
                                log.trace("added member %s [members: %s]", pingData, members != null);
                            }
                        }
                        if (local_addr != null && !local_addr.equals(pingData.getAddress())) {
                            addDiscoveryResponseToCaches(pingData.getAddress(), pingData.getLogicalName(),
                                    pingData.getPhysicalAddr());
                            if (log.isTraceEnabled()) {
                                log.trace("added possible member %s [local address: %s]", pingData, local_addr);
                            }
                        }

                        if (log.isTraceEnabled()) {
                            log.trace("processed entry in Amazon S3 [%s -> %s]", summary.getKey(), pingData);
                        }
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("skipping object %s as it is empty", summary.getKey());
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("fetched update for member list in Amazon S3 [%s]", clusterPrefix);
            }

        } catch (final Exception e) {
            log.error(String.format("failed getting member list from Amazon S3 [%s]", clusterPrefix), e);
        }
    }

    @Override
    protected void write(final List<PingData> list, final String clustername) {
        final String filename = addressToFilename(local_addr);
        final String key = getClusterPrefix(clustername) + filename;

        try {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream(SERIALIZATION_BUFFER_SIZE);
            write(list, outStream);

            final byte[] data = outStream.toByteArray();

            final ByteArrayInputStream inStream = new ByteArrayInputStream(data);
            final ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType(SERIALIZED_CONTENT_TYPE);
            objectMetadata.setContentLength(data.length);

            if (log.isTraceEnabled()) {
                log.trace("new S3 file content (%d bytes): %s", data.length, new String(data));
            }

            s3.putObject(new PutObjectRequest(bucketName, key, inStream, objectMetadata));

            if (log.isDebugEnabled()) {
                log.debug("wrote member list to Amazon S3 [%s -> %s]", key, list);
            }

        } catch (final Exception e) {
            log.error(String.format("failed to update member list in Amazon S3 [%s]", key), e);
        }
    }

    @Override
    protected void remove(final String clustername, final Address addr) {
        if(clustername == null || addr == null) {
            return;
        }

        String filename = addressToFilename(addr);
        String key = getClusterPrefix(clustername) + filename;

        try {
            s3.deleteObject(new DeleteObjectRequest(bucketName, key));

            if(log.isTraceEnabled()) {
                log.trace("removing " + key);
            }
        } catch(Exception e) {
            log.error(Util.getMessage("FailureRemovingData"), e);
        }
    }

    @Override
    protected void removeAll(String clustername) {
        if(clustername == null) {
            return;
        }

        final String clusterPrefix = getClusterPrefix(clustername);

        try {
            final ObjectListing objectListing = s3.listObjects(
                new ListObjectsRequest()
                    .withBucketName(bucketName)
                    .withPrefix(clusterPrefix));

            if (log.isTraceEnabled()) {
                log.trace("got object listing, %d entries [%s]", objectListing.getObjectSummaries().size(), clusterPrefix);
            }

            for (final S3ObjectSummary summary : objectListing.getObjectSummaries()) {
                if (log.isTraceEnabled()) {
                    log.trace("fetching data for object %s ...", summary.getKey());
                }

                try {
                    s3.deleteObject(new DeleteObjectRequest(bucketName, summary.getKey()));
                    if (log.isTraceEnabled()) {
                        log.trace("removing %s/%s", summary.getKey());
                    }
                } catch(Throwable t) {
                    log.error("failed deleting object %s/%s: %s", summary.getKey(), t);
                }
            }
        } catch(Exception ex) {
            log.error(Util.getMessage("FailedDeletingAllObjects"), ex);
        }
    }

    public static void registerProtocolWithJGroups(short magicNumber) {
        ClassConfigurator.addProtocol(magicNumber, NATIVE_S3_PING.class);
    }
}
