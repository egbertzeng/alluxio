---
layout: global
title: Configuring Alluxio with Amazon S3
nickname: Alluxio with S3
group: Under Store
priority: 0
---

* Table of Contents
{:toc}

This guide describes the instructions to configure [Amazon S3](https://aws.amazon.com/s3/) as Alluxio's
under storage system. Alluxio recognizes the s3a:// scheme and uses the aws-sdk to access S3.

## Initial Setup

First, the Alluxio binaries must be on your machine. You can either
[compile Alluxio](Building-Alluxio-Master-Branch.html), or
[download the binaries locally](Running-Alluxio-Locally.html).

Also, in preparation for using S3 with Alluxio, create a bucket (or use an existing bucket). You
should also note the directory you want to use in that bucket, either by creating a new directory in
the bucket, or using an existing one. For the purposes of this guide, the S3 bucket name is called
`S3_BUCKET`, and the directory in that bucket is called `S3_DIRECTORY`.

## Mounting S3

Alluxio unifies access to different storage systems through the [unified namespace](Unified-and-Transparent-Namespace.html)
feature. An S3 location can be either mounted at the root of the Alluxio namespace or at a nested directory.

### Root Mount

You need to configure Alluxio to use under storage systems by modifying
`conf/alluxio-site.properties`. If it does not exist, create the configuration file from the
template.

```bash
$ cp conf/alluxio-site.properties.template conf/alluxio-site.properties
```

You need to configure Alluxio to use S3 as its under storage system by modifying
`conf/alluxio-site.properties`. The first modification is to specify an **existing** S3
bucket and directory as the under storage system. You specify it by modifying
`conf/alluxio-site.properties` to include:

```
alluxio.underfs.address=s3a://S3_BUCKET/S3_DIRECTORY
```

Next, you need to specify the AWS credentials for S3 access.

You can specify credentials in 4 ways, from highest to lowest priority:

* Environment Variables `AWS_ACCESS_KEY_ID` or `AWS_ACCESS_KEY` (either is acceptable) and
`AWS_SECRET_ACCESS_KEY` or `AWS_SECRET_KEY` (either is acceptable)
* System Properties `aws.accessKeyId` and `aws.secretKey`
* Profile file containing credentials at `~/.aws/credentials`
* AWS Instance profile credentials, if you are using an EC2 instance

See [Amazon's documentation](http://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html#id6)
for more details.

After these changes, Alluxio should be configured to work with S3 as its under storage system, and
you can try [Running Alluxio Locally with S3](#running-alluxio-locally-with-s3).

### Nested Mount
An S3 location can be mounted at a nested directory in the Alluxio namespace to have unified access
to multiple under storage systems. Alluxio's [Command Line Interface](Command-Line-Interface.html) can be used for this purpose.

```bash
$ ./bin/alluxio fs mount --option aws.accessKeyId=<AWS_ACCESS_KEY_ID> --option aws.secretKey=<AWS_SECRET_KEY_ID>\
  /mnt/s3 s3a://<S3_BUCKET>/<S3_DIRECTORY>
```

### Enabling Server Side Encryption

You may encrypt your data stored in S3. The encryption is only valid for data at rest in S3 and will
be transferred in decrypted form when read by clients.

Enable this feature by configuring `conf/alluxio-site.properties`:

```
alluxio.underfs.s3a.server.side.encryption.enabled=true
```

### DNS-Buckets

By default, a request directed at the bucket named "mybucket" will be sent to the host name
"mybucket.s3.amazonaws.com". You can enable DNS-Buckets to use path style data access, for example:
"http://s3.amazonaws.com/mybucket" by setting the following configuration:

```
alluxio.underfs.s3.disable.dns.buckets=true
```

### Accessing S3 through a proxy

To communicate with S3 through a proxy, modify `conf/alluxio-site.properties` to include:

```properties
alluxio.underfs.s3.proxy.host=<PROXY_HOST>
alluxio.underfs.s3.proxy.port=<PROXY_PORT>
```

Here, `<PROXY_HOST>` and `<PROXY_PORT>` should be replaced the host and port for your proxy.

## Configuring Application Dependency

When building your application to use Alluxio, your application should include a client module, the
`alluxio-core-client-fs` module to use the [Alluxio file system interface](File-System-API.html) or
the `alluxio-core-client-hdfs` module to use the
[Hadoop file system interface](https://wiki.apache.org/hadoop/HCFS). For example, if you
are using [maven](https://maven.apache.org/), you can add the dependency to your application with:

```xml
<!-- Alluxio file system interface -->
<dependency>
  <groupId>org.alluxio</groupId>
  <artifactId>alluxio-core-client-fs</artifactId>
  <version>{{site.ALLUXIO_RELEASED_VERSION}}</version>
</dependency>
<!-- HDFS file system interface -->
<dependency>
  <groupId>org.alluxio</groupId>
  <artifactId>alluxio-core-client-hdfs</artifactId>
  <version>{{site.ALLUXIO_RELEASED_VERSION}}</version>
</dependency>
```

Alternatively, you may copy `conf/alluxio-site.properties` (having the properties setting
credentials) to the classpath of your application runtime (e.g., `$SPARK_CLASSPATH` for Spark), or
append the path to this site properties file to the classpath.

### Using a non-Amazon service provider

To use an S3 service provider other than "s3.amazonaws.com", modify `conf/alluxio-site.properties`
to include:

```
alluxio.underfs.s3.endpoint=<S3_ENDPOINT>
```

For these parameters, replace `<S3_ENDPOINT>` with the hostname and port of your S3 service, e.g.,
`http://localhost:9000`. Only use this parameter if you are using a provider other than `s3.amazonaws.com`.

### Using v2 S3 Signatures

Some S3 service providers only support v2 signatures. For these S3 providers, you can enforce using
the v2 signatures by setting the `alluxio.underfs.s3a.signer.algorithm` to `S3SignerType`.

## Running Alluxio Locally with S3

After everything is configured, you can start up Alluxio locally to see that everything works.

```bash
$ ./bin/alluxio format
$ ./bin/alluxio-start.sh local
```

This should start an Alluxio master and an Alluxio worker. You can see the master UI at
[http://localhost:19999](http://localhost:19999).

Next, you can run a simple example program:

```bash
$ ./bin/alluxio runTests
```

After this succeeds, you can visit your S3 directory `S3_BUCKET/S3_DIRECTORY` to verify the files
and directories created by Alluxio exist. For this test, you should see files named like:

```
S3_BUCKET/S3_DIRECTORY/alluxio/data/default_tests_files/Basic_CACHE_THROUGH
```

To stop Alluxio, you can run:

```bash
$ ./bin/alluxio-stop.sh local
```

## S3 Access Control

If Alluxio security is enabled, Alluxio enforces the access control inherited from underlying object
storage.

The S3 credentials specified in Alluxio config represents a S3 user. S3 service backend checks the
user permission to the bucket and the object for access control. If the given S3 user does not have
the right access permission to the specified bucket, a permission denied error will be thrown. When
Alluxio security is enabled, Alluxio loads the bucket ACL to Alluxio permission on the first time
when the metadata is loaded to Alluxio namespace.

### Mapping from S3 user to Alluxio file owner

By default, Alluxio tries to extract the S3 user display name from the S3 credential. Optionally,
`alluxio.underfs.s3.owner.id.to.username.mapping` can be used to specify a preset S3 canonical id to
Alluxio username static mapping, in the format "id1=user1;id2=user2".  The AWS S3 canonical ID can
be found at the console [address](https://console.aws.amazon.com/iam/home?#security_credential).
Please expand the "Account Identifiers" tab and refer to "Canonical User ID".

### Mapping from S3 ACL to Alluxio permission

Alluxio checks the S3 bucket READ/WRITE ACL to determine the owner's permission mode to a Alluxio
file. For example, if the S3 user has read-only access to the underlying bucket, the mounted
directory and files would have 0500 mode. If the S3 user has full access to the underlying bucket,
the mounted directory and files would have 0700 mode.

### Mount point sharing

If you want to share the S3 mount point with other users in Alluxio namespace, you can enable
`alluxio.underfs.object.store.mount.shared.publicly`.

### Permission change

In addition, chown/chgrp/chmod to Alluxio directories and files do NOT propagate to the underlying
S3 buckets nor objects.
