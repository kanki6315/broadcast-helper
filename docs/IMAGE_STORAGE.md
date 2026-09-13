# Public images and PDFs

Car photos, their 400px WebP sheet variants, manufacturer and series logos, driver headshots, and event PDFs can live in Cloudflare R2.
The browser decodes and resizes each selected photo in a worker, then PUTs the
original and WebP directly to R2. Java handles JSON, signing, HEAD requests, and
server-side object copies; it never downloads or decodes these new uploads.
Sheets, event details, team views, the gallery, and iPad downloads receive public
asset URLs in API data and fetch R2 directly. Driver headshots resize to at most 640px in the browser and upload directly.
PDF uploads use disk-backed multipart files: the backend parses team sheets and pit assignments from temporary paths, then streams each PDF to R2. Storylines use the same disk-backed upload without parsing. New PDFs never become full Java byte arrays when R2 is enabled.
New raster logos resize to at most 1024px in the browser; SVG logos upload unchanged.


## Configure R2

1. Create a dedicated **Standard** R2 bucket for public assets.
2. Attach a public custom domain, for example `https://images.example.com`.
   Use a separate media origin from the application. R2's `r2.dev` domain is
   for development and has rate limits; use the custom domain for production.
3. Create an R2 API token with **Object Read & Write**, restricted to this bucket.
   The backend needs PUT, PUT signing, HEAD, and COPY permissions. Keep the access key
   ID and secret in Railway variables, never in frontend configuration.
4. Set bucket CORS, replacing the origin with the application's exact HTTPS
   origin (add a localhost origin only if testing locally):

   ```json
   [
     {
       "AllowedOrigins": ["https://your-app.example.com"],
       "AllowedMethods": ["PUT", "GET", "HEAD"],
       "AllowedHeaders": ["Content-Type", "Range"],
       "ExposeHeaders": ["ETag", "Content-Length", "Content-Range", "Accept-Ranges"],
       "MaxAgeSeconds": 3600
     }
   ]
   ```

   The PUT URL uses the R2 S3 API endpoint, not the public custom domain.
   CORS permits browser uploads; it does not make uploads anonymous. PUTs still
   require the signed URL issued to an app admin.
5. Add an object lifecycle rule to **delete objects under `staging/` after one
   day**. This cleans up both completed and abandoned browser uploads. Do not
   apply this expiry rule to any permanent asset prefix.
6. Under the parent domain's **Caching → Cache Rules**, create a rule with
   this expression (replace the hostname):

   ```text
   (http.host eq "images.example.com" and (
     starts_with(http.request.uri.path, "/car-images/") or
     starts_with(http.request.uri.path, "/manufacturer-logos/") or
     starts_with(http.request.uri.path, "/series-logos/") or
     starts_with(http.request.uri.path, "/driver-photos/") or
     starts_with(http.request.uri.path, "/documents/")
   ))
   ```

   Select **Eligible for cache**, use the origin Cache-Control header for Edge
   TTL (bypass when absent), and select **Respect origin** for Browser TTL.
   Image object keys have no file extensions, so an explicit eligibility rule
   is needed. Published objects carry `Cache-Control: public,
   max-age=31536000, immutable` and unique keys; replacements don't need purges.
   The rule excludes temporary files under `/staging/`.

Official references:
[public buckets](https://developers.cloudflare.com/r2/buckets/public-buckets/),
[CORS](https://developers.cloudflare.com/r2/buckets/cors/),
[presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/),
[lifecycle rules](https://developers.cloudflare.com/r2/buckets/object-lifecycles/).

## Railway variables

| Variable | Value |
|---|---|
| `R2_IMAGES_ENABLED` | `true` |
| `R2_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` (use the endpoint shown for your bucket) |
| `R2_BUCKET` | Your bucket name |
| `R2_PUBLIC_BASE_URL` | `https://images.example.com` (no bucket name appended) |
| `R2_ACCESS_KEY_ID` | Bucket-scoped access key ID |
| `R2_SECRET_ACCESS_KEY` | Corresponding secret access key |

Redeploy after setting these. R2 must remain enabled for file uploads and public
URLs. Legacy multipart image uploads are rejected with an instruction to reload
the app; they cannot trigger server resizing or database writes.

## Upload and verify

Upload one photo from the season Photos page. The browser processes files one
at a time, with a worker timeout and upload timeout. A failed file appears with
a Retry action; successfully uploaded files remain saved.

In browser Network tools, check:

- Backend requests for preparing/completing uploads have JSON bodies only.
- The original and WebP are PUT directly to `r2.cloudflarestorage.com`.
- After completion, photos load from your public domain without API redirects.
- The photo appears in the event sheet, team view, and iPad; download it on the
  iPad and check it remains visible offline.

Limits: JPEG, PNG, WebP, or GIF originals up to 25 MiB, and WebP variants up to
1 MiB. Animated originals are retained; the sheet variant is a still image.
The backend verifies stored content type and length using HEAD; it deliberately
does not decode pixels. This is an admin-only upload workflow, not an arbitrary
public image ingestion service.

Signed PUT URLs expire after 10 minutes. Completion tickets last 30 minutes.
The backend copies verified uploads from temporary keys to separate permanent
keys inside R2, with an ETag condition to reject a changed source. The signed
PUT URLs cannot change those permanent keys. Both copies must succeed before
any database reference changes. A completed ticket can be retried without
republishing an older photo over a newer one.

## R2-only storage and database cleanup

All new uploads require configured R2 storage. There is no database fallback.
Image resizing runs in the browser; the backend no longer includes Scrimage.
PDFs stream from temporary files to R2 after any required parsing. Downloads
use direct public URLs. Legacy data endpoints redirect to R2 for older links.

Flyway migration V50 checks that every car photo has original and sheet keys,
and every logo, headshot, and PDF has an object key. If any are absent or blank,
it aborts transactionally without deleting bytes. Complete migration on the
previous release before upgrading such a database.

When the check passes, V50 clears and drops the file-data columns, drops the
old thumbnail table, and removes migration-ticket metadata. All file metadata,
R2 keys, page mappings, pit assignments, and driver records remain. Migration
buttons and endpoints are removed. This cleanup runs once during deployment;
there is no manual cleanup button.

The check validates database references, not live R2 objects. R2 and its public
domain must remain available. Database-only rollback to pre-R2 storage is no
longer possible after cleanup without restoring a backup. PostgreSQL vacuum can
reclaim cleared data internally; allocated volume size may not shrink immediately.

## Maintenance and costs

Replaced/deleted permanent objects are retained so previously downloaded page
metadata and cached links continue to work. They continue to count toward R2
storage. Do not add a blanket age-based expiry to permanent images: a current
photo may be several years old. A later garbage-collection job can compare R2
keys against `car_image.original_object_key`, `car_image.sheet_object_key`,
`manufacturer_logo.object_key`, `series_logo.object_key`, `driver_photo.object_key`,
and `event_document.object_key`
and remove unreferenced objects after a retention period; that job is not part
of this change.

Upload tickets contain metadata only. Periodically remove tickets older than
seven days with `DELETE FROM car_image_upload WHERE expires_at < now() - interval
'7 days';` and `DELETE FROM logo_upload WHERE expires_at < now() - interval '7 days';` using database maintenance tooling. No background polling task is
added to the app, so this feature doesn't keep a serverless instance awake.

The lightweight URLConnection HTTP transport avoids adding Apache/Netty pools.
S3 clients are initialized only when signing, completing, or streaming an upload, not when
serving page metadata. Measure both idle memory and concurrent-photo-load peaks
after deployment; the SDK still has some runtime overhead once used. Public R2
behavior and CORS must be verified against the real bucket before rollout.

## Local regression checks

- Backend: `cd backend && ./gradlew test bootJar --no-daemon` (local PostgreSQL required).
- Browser: `cd frontend && npm ci && npx playwright install chromium && npm run test:images`.
  The test starts its own Vite server, generates images, and uses mocked storage
  endpoints; it never contacts R2. To use an existing Chromium installation,
  set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` instead of downloading a browser.
- Frontend: `cd frontend && npm run build && npm run lint`.
- iPad: generate the project with XcodeGen and run its test scheme on an iPad
  simulator. The public-image tests verify absolute URL cache keys and that
  bearer credentials are restricted to the API origin.
