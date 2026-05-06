package io.github.shigella520.linkpeek.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.server.ai.AiTitleService;
import io.github.shigella520.linkpeek.server.cache.DiskCacheManager;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void concurrentBasePreviewLoadsResolveProviderOnceOnCacheMiss() throws Exception {
        SlowPreviewProvider provider = new SlowPreviewProvider();
        DiskCacheManager cacheManager = new DiskCacheManager(new ObjectMapper(), properties(tempDir));
        cacheManager.init();
        PreviewService previewService = new PreviewService(
                new PreviewProviderRegistry(List.of(provider)),
                cacheManager,
                new AiTitleService(null, null, null, null, null)
        );
        PreviewService.ResolvedPreview resolvedPreview = previewService.prepare("https://video.example.com/watch/abc");

        int concurrency = 8;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<PreviewService.PreviewLoadResult>> futures = IntStream.range(0, concurrency)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        await(start);
                        return previewService.loadPreview(resolvedPreview);
                    }, executor))
                    .toList();

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<PreviewService.PreviewLoadResult> results = futures.stream()
                    .map(future -> join(future, 5, TimeUnit.SECONDS))
                    .toList();

            assertEquals(1, provider.resolutions.get());
            assertEquals(1, results.stream().filter(result -> !result.cacheHit()).count());
            assertEquals(concurrency - 1L, results.stream().filter(PreviewService.PreviewLoadResult::cacheHit).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private static PreviewService.PreviewLoadResult join(
            CompletableFuture<PreviewService.PreviewLoadResult> future,
            long timeout,
            TimeUnit unit
    ) {
        try {
            return future.get(timeout, unit);
        } catch (Exception exception) {
            throw new AssertionError("Preview load did not complete in time.", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static LinkPeekProperties properties(Path cacheDir) {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setCacheDir(cacheDir);
        properties.setCacheTtlSeconds(3600);
        properties.setCacheMaxSizeGb(1);
        return properties;
    }

    private static final class SlowPreviewProvider implements PreviewProvider {
        private final AtomicInteger resolutions = new AtomicInteger();

        @Override
        public String getId() {
            return "slow";
        }

        @Override
        public boolean supports(URI sourceUrl) {
            return "video.example.com".equals(sourceUrl.getHost());
        }

        @Override
        public URI canonicalize(URI sourceUrl) {
            return URI.create("https://video.example.com/watch/abc");
        }

        @Override
        public PreviewMetadata resolve(URI sourceUrl) {
            resolutions.incrementAndGet();
            sleep(Duration.ofMillis(250));
            return new PreviewMetadata(
                    sourceUrl.toString(),
                    canonicalize(sourceUrl).toString(),
                    getId(),
                    "Concurrent title",
                    "Concurrent description",
                    "Concurrent site",
                    "https://img.example/thumb.jpg",
                    1200,
                    630,
                    ContentType.VIDEO
            );
        }

        private void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
