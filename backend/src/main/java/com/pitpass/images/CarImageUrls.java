package com.pitpass.images;

import org.springframework.stereotype.Component;

/** DTO URLs point straight to R2; only unmigrated photos retain API URLs. */
@Component
public class CarImageUrls {
    private final PublicImageStorage storage;
    public CarImageUrls(PublicImageStorage storage) { this.storage = storage; }

    public String sheet(long imageId, Long version, String key) {
        if (key != null) return storage.publicUrl(key).toString();
        return version == null ? null : "/api/car-images/" + imageId + "/data?variant=sheet&v=" + version;
    }
    public String entry(long entryId, Long version, String key) {
        if (key != null) return storage.publicUrl(key).toString();
        return version == null ? null : "/api/entries/" + entryId + "/image?variant=sheet&v=" + version;
    }
    public String original(long imageId, long version, String key) {
        return key != null ? storage.publicUrl(key).toString() : "/api/car-images/" + imageId + "/data?v=" + version;
    }
}
