package com.shoppinglive.live.thumbnail;

import com.shoppinglive.common.core.ApiResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class BroadcastThumbnailController {
    private final BroadcastThumbnailService service;

    public BroadcastThumbnailController(BroadcastThumbnailService service) {
        this.service = service;
    }

    @PostMapping(
            path = "/v1/seller/broadcast-thumbnails",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        var thumbnail = service.upload(file.getBytes(), file.getContentType());
        return ApiResponse.ok(
                Map.of(
                        "thumbnailId",
                        thumbnail.getId(),
                        "thumbnailUrl",
                        "/api/live/v1/broadcast-thumbnails/" + thumbnail.getId()));
    }

    @GetMapping("/v1/broadcast-thumbnails/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        var thumbnail = service.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(thumbnail.getContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic().immutable())
                .body(thumbnail.getContent());
    }
}
