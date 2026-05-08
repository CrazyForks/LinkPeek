package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.core.error.MetadataNotFoundException;
import io.github.shigella520.linkpeek.core.error.PreviewException;
import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsRecorder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/media")
@Tag(name = "Media", description = "缩略图与视频代理接口")
public class MediaController {
    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final PreviewService previewService;
    private final StatisticsRecorder statisticsRecorder;

    public MediaController(PreviewService previewService, StatisticsRecorder statisticsRecorder) {
        this.previewService = previewService;
        this.statisticsRecorder = statisticsRecorder;
    }

    @GetMapping("/thumb/{previewKey}.jpg")
    @Operation(
            summary = "获取缩略图代理",
            description = "按 PreviewKey 下载并缓存缩略图，返回 JPEG 资源。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "成功返回缩略图"),
                    @ApiResponse(responseCode = "404", description = "元数据不存在或已过期", content = @Content(schema = @Schema(hidden = true)))
            }
    )
    public ResponseEntity<Resource> thumbnail(
            @Parameter(description = "预览资源的 opaque 标识，不暴露平台内部 ID")
            @PathVariable String previewKey,
            @Parameter(description = "可选缩略图内容版本，用于生成式标题卡片刷新外部缓存。")
            @RequestParam(name = "v", required = false) String version
    ) {
        long startedAt = System.nanoTime();
        try {
            PreviewService.ThumbnailResult result = previewService.ensureThumbnailResult(previewKey, version);
            long durationMs = elapsedMillis(startedAt);
            statisticsRecorder.recordThumbnailServed(
                    previewKey,
                    result.metadata(),
                    result.cacheHit(),
                    durationMs
            );
            Path path = result.path();
            log.info(
                    "thumbnail_served previewKey={} provider={} cacheHit={} durationMs={} status={}",
                    previewKey,
                    result.metadata() == null ? "n/a" : result.metadata().providerId(),
                    result.cacheHit(),
                    durationMs,
                    200
            );
            FileSystemResource resource = new FileSystemResource(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body(resource);
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startedAt);
            HttpStatus failureStatus = thumbnailFailureStatus(exception);
            if (failureStatus.is5xxServerError()) {
                log.warn(
                        "thumbnail_failed previewKey={} durationMs={} status={} message={}",
                        previewKey,
                        durationMs,
                        failureStatus.value(),
                        exception.getMessage(),
                        exception
                );
            } else {
                log.info(
                        "thumbnail_failed previewKey={} durationMs={} status={} message={}",
                        previewKey,
                        durationMs,
                        failureStatus.value(),
                        exception.getMessage()
                );
            }
            throw exception;
        }
    }

    @GetMapping("/video/{previewKey}.mp4")
    @Operation(
            summary = "获取视频代理",
            description = "当前版本仅保留路由占位，固定返回 501 Not Implemented。",
            responses = {
                    @ApiResponse(responseCode = "501", description = "当前版本未实现视频代理")
            }
    )
    public ResponseEntity<String> video(
            @Parameter(description = "预览资源的 opaque 标识，不暴露平台内部 ID")
            @PathVariable String previewKey
    ) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Video proxy is not implemented in this release.");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private HttpStatus thumbnailFailureStatus(RuntimeException exception) {
        if (exception instanceof MetadataNotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (exception instanceof IllegalArgumentException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (exception instanceof PreviewException) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (exception instanceof IllegalStateException) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
