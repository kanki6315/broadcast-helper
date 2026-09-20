package com.pitpass.images;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/** Public asset storage. PDF uploads stream from disk; public downloads bypass Java. */
@Component
public class PublicImageStorage {
    private final boolean enabled;
    private final String endpoint, bucket, publicBase, accessKey, secretKey;
    private S3Client client;
    private S3Presigner presigner;

    public PublicImageStorage(@Value("${images.r2.enabled:false}") boolean enabled,
            @Value("${images.r2.endpoint:}") String endpoint,
            @Value("${images.r2.bucket:}") String bucket,
            @Value("${images.r2.public-base-url:}") String publicBase,
            @Value("${images.r2.access-key-id:}") String accessKey,
            @Value("${images.r2.secret-access-key:}") String secretKey) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.publicBase = publicBase.replaceAll("/+$", "");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        if (enabled) {
            if (bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank())
                throw new IllegalArgumentException("R2 bucket and credentials are required when enabled");
            for (String url : new String[] {endpoint, this.publicBase}) {
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("R2 endpoints must be HTTPS URLs without credentials or query strings");
            }
        }
    }

    public boolean enabled() { return enabled; }

    // Lazy SDK initialization: serving redirects doesn't start HTTP pools or load S3 clients.
    private synchronized void initialize() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        if (client != null) return;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var config = S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build();
        client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of("auto"))
                .credentialsProvider(credentials).serviceConfiguration(config)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45)))
                .build();
        presigner = S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.of("auto"))
                .credentialsProvider(credentials).serviceConfiguration(config).build();
    }

    public String uploadUrl(String key, String contentType, long length) {
        initialize();
        var put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentLength(length).build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)).putObjectRequest(put).build()).url().toString();
    }

    /** Copy to a key that has never had a signed PUT URL, so completed images are immutable. */
    public void publish(String stagingKey, String finalKey, String contentType, long length) {
        initialize();
        try {
            var head = client.headObject(b -> b.bucket(bucket).key(stagingKey));
            if (head.contentLength() != length || !contentType.equals(head.contentType()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Uploaded image size or type does not match");
            client.copyObject(b -> b.bucket(bucket).key(finalKey).copySource(bucket + "/" + stagingKey)
                    .copySourceIfMatch(head.eTag()).metadataDirective(MetadataDirective.REPLACE)
                    .contentType(contentType).cacheControl("public, max-age=31536000, immutable"));
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 412)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is missing or changed; upload the file again");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image storage could not finalize the upload");
        }
    }

    public void uploadPdf(String key, java.nio.file.Path file) {
        initialize();
        try {
            client.putObject(b -> b.bucket(bucket).key(key).contentType("application/pdf")
                    .cacheControl("public, max-age=31536000, immutable"), file);
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Document storage upload failed", e);
        }
    }

    /**
     * An object in a DIFFERENT, non-public bucket on the same R2 account —
     * the live timing recordings, which are licensed data and must never be
     * reachable through {@link #publicUrl}. Refuses the public bucket outright
     * so a misconfigured env var cannot publish them.
     */
    public void uploadPrivate(String privateBucket, String key, java.nio.file.Path file, String contentType) {
        if (privateBucket.equals(bucket))
            throw new IllegalArgumentException("Refusing to store private data in the public bucket");
        initialize();
        client.putObject(b -> b.bucket(privateBucket).key(key).contentType(contentType), file);
    }

    public URI publicUrl(String key) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is not configured");
        return URI.create(publicBase + "/" + key);
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) client.close();
        if (presigner != null) presigner.close();
    }
}
