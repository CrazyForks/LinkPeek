package io.github.shigella520.linkpeek.server.service;

import io.github.shigella520.linkpeek.core.error.MetadataNotFoundException;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import io.github.shigella520.linkpeek.server.ai.AiTitleService;
import io.github.shigella520.linkpeek.server.cache.DiskCacheManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PreviewService {
    private final PreviewProviderRegistry providerRegistry;
    private final DiskCacheManager cacheManager;
    private final AiTitleService aiTitleService;
    private final ConcurrentHashMap<String, ReentrantLock> metadataLocks = new ConcurrentHashMap<>();

    public PreviewService(
            PreviewProviderRegistry providerRegistry,
            DiskCacheManager cacheManager,
            AiTitleService aiTitleService
    ) {
        this.providerRegistry = providerRegistry;
        this.cacheManager = cacheManager;
        this.aiTitleService = aiTitleService;
    }

    public ResolvedPreview prepare(String rawUrl) {
        URI sourceUrl = UrlNormalizer.parseHttpUrl(rawUrl);
        PreviewProvider provider = providerRegistry.findSupporting(sourceUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("No preview provider is available for this URL."));
        URI canonicalUrl = provider.canonicalize(sourceUrl);
        return new ResolvedPreview(sourceUrl, canonicalUrl, PreviewKey.fromCanonicalUrl(canonicalUrl), provider);
    }

    public boolean supports(String rawUrl) {
        URI sourceUrl = UrlNormalizer.parseHttpUrl(rawUrl);
        return providerRegistry.findSupporting(sourceUrl).isPresent();
    }

    public PreviewLoadResult loadPreview(ResolvedPreview resolvedPreview) {
        return loadPreview(resolvedPreview, null);
    }

    public PreviewLoadResult loadPreview(ResolvedPreview resolvedPreview, String style) {
        Optional<AiTitleService.StylePrompt> stylePrompt = aiTitleService.resolveStylePrompt(style);
        if (stylePrompt.isPresent()) {
            return loadStyledPreview(resolvedPreview, stylePrompt.get());
        }

        return loadBasePreview(resolvedPreview);
    }

    private PreviewLoadResult loadStyledPreview(
            ResolvedPreview resolvedPreview,
            AiTitleService.StylePrompt stylePrompt
    ) {
        PreviewKey styledPreviewKey = aiTitleService.styledPreviewKey(resolvedPreview.canonicalUrl(), stylePrompt);
        Optional<PreviewMetadata> cachedStyled = cacheManager.getMetadata(styledPreviewKey);
        if (cachedStyled.isPresent()) {
            return new PreviewLoadResult(resolvedPreview, cachedStyled.get(), styledPreviewKey, true, true, true);
        }

        ReentrantLock lock = metadataLockFor(styledPreviewKey);
        lock.lock();
        try {
            cachedStyled = cacheManager.getMetadata(styledPreviewKey);
            if (cachedStyled.isPresent()) {
                return new PreviewLoadResult(resolvedPreview, cachedStyled.get(), styledPreviewKey, true, true, true);
            }

            PreviewLoadResult baseResult = loadBasePreview(resolvedPreview);
            PreviewMetadata aiMetadata = resolvedPreview.provider().enrichForAiTitle(baseResult.metadata(), resolvedPreview.sourceUrl());
            if (!aiTitleService.supportsAiTitle(aiMetadata)) {
                return baseResult;
            }
            Optional<PreviewMetadata> styledMetadata = aiTitleService.generateStyledMetadata(aiMetadata, stylePrompt);
            if (styledMetadata.isPresent()) {
                cacheManager.storeMetadata(styledPreviewKey, styledMetadata.get());
                return new PreviewLoadResult(resolvedPreview, styledMetadata.get(), styledPreviewKey, false, true, true);
            }
            return baseResult.withAiStats(true, false);
        } finally {
            lock.unlock();
        }
    }

    private PreviewLoadResult loadBasePreview(ResolvedPreview resolvedPreview) {
        Optional<PreviewMetadata> cached = cacheManager.getMetadata(resolvedPreview.previewKey());
        if (cached.isPresent()) {
            return new PreviewLoadResult(resolvedPreview, cached.get(), true);
        }

        ReentrantLock lock = metadataLockFor(resolvedPreview.previewKey());
        lock.lock();
        try {
            cached = cacheManager.getMetadata(resolvedPreview.previewKey());
            if (cached.isPresent()) {
                return new PreviewLoadResult(resolvedPreview, cached.get(), true);
            }

            PreviewMetadata metadata = resolvedPreview.provider().resolve(resolvedPreview.sourceUrl());
            cacheManager.storeMetadata(resolvedPreview.previewKey(), metadata);
            return new PreviewLoadResult(resolvedPreview, metadata, false);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock metadataLockFor(PreviewKey previewKey) {
        return metadataLocks.computeIfAbsent(previewKey.value(), ignored -> new ReentrantLock());
    }

    public Optional<PreviewLoadResult> getCachedPreview(ResolvedPreview resolvedPreview) {
        return cacheManager.getMetadata(resolvedPreview.previewKey())
                .map(metadata -> new PreviewLoadResult(resolvedPreview, metadata, true));
    }

    public Path ensureThumbnail(String previewKeyValue) {
        return ensureThumbnailResult(previewKeyValue).path();
    }

    public ThumbnailResult ensureThumbnailResult(String previewKeyValue) {
        PreviewKey previewKey = new PreviewKey(previewKeyValue);
        Optional<Path> cached = cacheManager.getThumbnailPath(previewKey);
        if (cached.isPresent()) {
            return new ThumbnailResult(cached.get(), true, cacheManager.getMetadata(previewKey).orElse(null));
        }

        ReentrantLock lock = cacheManager.lockFor(previewKey);
        lock.lock();
        try {
            Optional<Path> lockedCached = cacheManager.getThumbnailPath(previewKey);
            if (lockedCached.isPresent()) {
                return new ThumbnailResult(lockedCached.get(), true, cacheManager.getMetadata(previewKey).orElse(null));
            }
            return downloadThumbnail(previewKey);
        } finally {
            lock.unlock();
        }
    }

    private ThumbnailResult downloadThumbnail(PreviewKey previewKey) {
        PreviewMetadata metadata = cacheManager.getMetadata(previewKey)
                .orElseThrow(() -> new MetadataNotFoundException("Preview metadata is missing or expired."));
        PreviewProvider provider = providerRegistry.getById(metadata.providerId())
                .orElseThrow(() -> new UnsupportedPreviewUrlException("The provider for this preview is not available."));

        Path targetPath = cacheManager.thumbnailPath(previewKey);
        try {
            Path downloaded = provider.downloadThumbnail(metadata, targetPath);
            cacheManager.evictIfNeeded();
            return new ThumbnailResult(downloaded, false, metadata);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store thumbnail in cache", exception);
        }
    }

    public record ResolvedPreview(
            URI sourceUrl,
            URI canonicalUrl,
            PreviewKey previewKey,
            PreviewProvider provider
    ) {
    }

    public record PreviewLoadResult(
            ResolvedPreview resolvedPreview,
            PreviewMetadata metadata,
            PreviewKey previewKey,
            boolean cacheHit,
            boolean aiRequested,
            boolean aiSucceeded
    ) {
        public PreviewLoadResult(ResolvedPreview resolvedPreview, PreviewMetadata metadata, boolean cacheHit) {
            this(resolvedPreview, metadata, resolvedPreview.previewKey(), cacheHit, false, false);
        }

        public PreviewLoadResult withAiStats(boolean aiRequested, boolean aiSucceeded) {
            return new PreviewLoadResult(resolvedPreview, metadata, previewKey, cacheHit, aiRequested, aiSucceeded);
        }
    }

    public record ThumbnailResult(
            Path path,
            boolean cacheHit,
            PreviewMetadata metadata
    ) {
    }
}
