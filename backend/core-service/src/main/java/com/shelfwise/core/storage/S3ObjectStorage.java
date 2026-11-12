package com.shelfwise.core.storage;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** S3 API through the AWS SDK's non-blocking client. The bucket is created on first use if it is missing. */
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class S3ObjectStorage implements ObjectStorage {

    private final S3AsyncClient s3;
    private final String bucket;
    private final AtomicReference<Mono<Void>> bucketReady = new AtomicReference<>();

    public S3ObjectStorage(StorageProperties props) {
        var builder = S3AsyncClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        this.s3 = builder.build();
        this.bucket = props.bucket();
    }

    @Override
    public Mono<Void> put(String key, byte[] content, String contentType) {
        return ensureBucket().then(Mono.fromFuture(() -> s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                AsyncRequestBody.fromBytes(content)))).then();
    }

    @Override
    public Mono<byte[]> get(String key) {
        return Mono.fromFuture(() -> s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                AsyncResponseTransformer.toBytes())).map(r -> r.asByteArray());
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromFuture(() -> s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()))
                .then().onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> ensureBucket() {
        Mono<Void> ready = bucketReady.get();
        if (ready == null) {
            ready = Mono.fromFuture(() -> s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
                    .then()
                    .onErrorResume(e -> isMissingBucket(e)
                            ? Mono.fromFuture(() -> s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())).then()
                            : Mono.error(e))
                    .cache();
            if (!bucketReady.compareAndSet(null, ready)) {
                ready = bucketReady.get();
            }
        }
        return ready.doOnError(e -> bucketReady.set(null));
    }

    private static boolean isMissingBucket(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof S3Exception s && s.statusCode() == 404) {
                return true;
            }
        }
        return false;
    }
}
