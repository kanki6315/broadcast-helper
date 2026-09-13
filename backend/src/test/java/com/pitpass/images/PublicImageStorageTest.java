package com.pitpass.images;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicImageStorageTest {
    PublicImageStorage storage() {
        return new PublicImageStorage(true, "https://account.r2.cloudflarestorage.com", "photos",
                "https://photos.example/", "test-access", "test-secret");
    }

    @Test void signedPutIsBoundToSizeTypeAndShortExpiry() {
        try (var closeable = new StorageCloseable(storage())) {
            String url = URLDecoder.decode(closeable.value.uploadUrl("staging/car-images/id/original", "image/jpeg", 1234), StandardCharsets.UTF_8);
            assertTrue(url.contains("X-Amz-Expires=600"));
            assertTrue(url.contains("content-length"));
            assertTrue(url.contains("content-type"));
            assertTrue(url.startsWith("https://account.r2.cloudflarestorage.com/photos/staging/car-images/id/original?"));
            assertEquals("https://photos.example/car-images/id/sheet", closeable.value.publicUrl("car-images/id/sheet").toString());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void verificationRejectsWrongSizesAndPublicationUsesConditionalServerSideCopy() {
        var storage = storage();
        var client = mock(S3Client.class);
        ReflectionTestUtils.setField(storage, "client", client);
        when(client.headObject(any(Consumer.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/webp").contentLength(20L).eTag("etag").build());
        assertThrows(ResponseStatusException.class, () -> storage.publish("staging/a", "car-images/a", "image/webp", 21));
        verify(client, never()).copyObject(any(Consumer.class));
        when(client.copyObject(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<CopyObjectRequest.Builder> configure = invocation.getArgument(0);
            var builder = CopyObjectRequest.builder();
            configure.accept(builder);
            var request = builder.build();
            assertEquals("photos/staging/a", request.copySource());
            assertEquals("car-images/a", request.key());
            assertEquals("etag", request.copySourceIfMatch());
            assertEquals("public, max-age=31536000, immutable", request.cacheControl());
            assertEquals(MetadataDirective.REPLACE, request.metadataDirective());
            return CopyObjectResponse.builder().build();
        });
        storage.publish("staging/a", "car-images/a", "image/webp", 20);
        verify(client).copyObject(any(Consumer.class));
        verify(client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    record StorageCloseable(PublicImageStorage value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
