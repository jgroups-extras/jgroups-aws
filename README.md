# JGroups AWS - S3_PING

Discovery protocol using AWS S3 buckets as cluster information store. Based on the original code written by
Tobias Sarnowski at Zalando [1,2], and ported to JGroups 4.x by Bela Ban in 2017.

To use it, include the following dependencies:
* `module: org.jgroups.aws` / `artifactId: jgroups-aws` / `version: 2.0.1.Final` (or higher)

Native means, it uses the AWS SDK [3] and does not implement the HTTP protocol on its own. The benefit is a more stable
connection as well as usage of IAM server profiles and AWS standardized credential distribution.

# Artifact
```xml
<dependency>
    <groupId>org.jgroups.aws</groupId>
    <artifactId>jgroups-aws</artifactId>
    <version>2.0.1.Final</version>
</dependency>
```

# Configuration

Like the original `S3_PING`, this library implement a JGroups discovery protocol which replaces protocols like
`MPING` or `TCPPING`.

```xml
<aws.S3_PING region_name="us-east-1a"
             bucket_name="jgroups-s3-test"/>
```

`aws.S3_PING` automatically registers itself to JGroups with the magic number 789. You can overwrite this by
setting the system property `s3ping.magic_number` to different number:

`-Ds3ping.magic_number=123`

## Possible Configurations

* **region_name**: like "eu-west-1", "us-east-1", etc.
* **bucket_name**: the S3 bucket to store the files in
* **bucket_prefix** (optional): if you don't want the plugin to pollute your S3 bucket, you can configure a prefix like
  "jgroups/"
* **endpoint** (optional): you can override the S3 endpoint if you know what you are doing
* **kms_key_id** (optional): you can set this to a kms key id to enable KMS-SSE encryption when writing data to S3 (see https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingKMSEncryption.html)

## Example Configuration

```xml
<!--
Based on tcp.xml but with new aws.S3_PING.
-->
<config xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns="urn:org:jgroups"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    <TCP bind_port="7800"
         recv_buf_size="${tcp.recv_buf_size:5M}"
         send_buf_size="${tcp.send_buf_size:5M}"
         max_bundle_size="64K"
         thread_pool.enabled="true"
         thread_pool.min_threads="2"
         thread_pool.max_threads="8"
         thread_pool.keep_alive_time="5000"/>

    <aws.S3_PING region_name="eu-west-1"
                 bucket_name="jgroups-s3-test"
                 bucket_prefix="jgroups"/>

    <MERGE3 min_interval="10000"
            max_interval="30000"/>

    <FD_SOCK/>
    <FD_ALL timeout="30000" interval="5000"/>
    <VERIFY_SUSPECT timeout="1500"/>
    <pbcast.NAKACK2 use_mcast_xmit="false"
                    discard_delivered_msgs="true"/>

    <UNICAST3/>

    <pbcast.STABLE stability_delay="1000" desired_avg_gossip="50000"
                   max_bytes="4M"/>
    <pbcast.GMS print_local_addr="true" join_timeout="2000"
                view_bundling="true"/>
    <MFC max_credits="2M"
         min_threshold="0.4"/>
    <FRAG2 frag_size="60K"/>
</config>
```

# Testing

Running the automated tests requires having AWS credentials setup with appropriate permissions
along with setting the region name and a bucket name.

```shell
declare -x AWS_ACCESS_KEY_ID="qF7ujVAaYUp3Tx7m"
declare -x AWS_SECRET_KEY="WzbG3R2KGtx5rsHQUx2PKQPS2f6WzMtf"
declare -x S3_PING_REGION_NAME="eu-central-1"
declare -x S3_PING_BUCKET_NAME="jgroups"
mvn verify
```

If any of the required properties are not specified tests will be skipped (uses `org.junit.Assume`).

# Reporting Issues

Project JGroups AWS uses GitHub issues:

[https://github.com/jgroups-extras/jgroups-aws/issues](https://github.com/jgroups-extras/jgroups-aws/issues)

# References

[1] https://libraries.io/github/zalando/jgroups-native-s3-ping

[2] https://github.com/jwegne/jgroups-native-s3-ping

[3] http://docs.aws.amazon.com/AmazonS3/latest/dev/UsingTheMPDotJavaAPI.html
