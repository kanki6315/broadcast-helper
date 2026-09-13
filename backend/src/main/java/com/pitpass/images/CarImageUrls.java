package com.pitpass.images;

import org.springframework.stereotype.Component;

/** DTO URLs point straight to R2; absent images have no URL. */
@Component
public class CarImageUrls {
    private final PublicImageStorage storage;
    public CarImageUrls(PublicImageStorage storage) { this.storage = storage; }

    public String sheet(long imageId, Long version, String key) {
        return key == null ? null : storage.publicUrl(key).toString();
    }
    public String entry(long entryId, Long version, String key) {
        return key == null ? null : storage.publicUrl(key).toString();
    }
    public String original(long imageId, long version, String key) {
        return key == null ? null : storage.publicUrl(key).toString();
    }
}
