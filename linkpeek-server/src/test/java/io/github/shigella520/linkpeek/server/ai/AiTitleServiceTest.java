package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTitleServiceTest {
    @Test
    void buildPromptReplacesRawContentPlaceholderAndAppendsOutputConstraint() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeAiTitleClient());

        String prompt = service.buildPrompt("请总结：{raw_content}", " 原文内容 ");

        assertTrue(prompt.contains("请总结：原文内容"));
        assertFalse(prompt.contains("原文内容：\n原文内容"));
        assertTrue(prompt.endsWith(AiTitleService.OUTPUT_CONSTRAINT));
    }

    @Test
    void buildPromptAppendsRawContentWhenPlaceholderIsMissing() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeAiTitleClient());

        String prompt = service.buildPrompt("请写一个标题", "帖子正文");

        assertTrue(prompt.contains("请写一个标题\n\n原文内容：\n帖子正文"));
        assertTrue(prompt.endsWith(AiTitleService.OUTPUT_CONSTRAINT));
    }

    @Test
    void cleanTitleKeepsOnlyOnePlainTitleLine() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeAiTitleClient());

        assertEquals("一个更有点击欲的标题", service.cleanTitle("""
                ```markdown
                标题：“一个更有点击欲的标题”
                解释：这里不应该保留
                ```
                """));
    }

    @Test
    void generateStyledMetadataFallsBackAcrossEnabledProviders() {
        AiProviderRecord first = provider(1L, 1);
        AiProviderRecord second = provider(2L, 2);
        FakeAiTitleClient client = new FakeAiTitleClient();
        client.failProviderIds.add(1L);
        client.title = "\"最终标题\"";
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of(first, second)), client);

        Optional<PreviewMetadata> result = service.generateStyledMetadata(
                generatedTextMetadata(),
                new AiTitleService.StylePrompt("fun", "请总结 {raw_content}", "hash")
        );

        assertTrue(result.isPresent());
        assertEquals("最终标题", result.get().title());
        assertEquals("原始标题", generatedTextMetadata().title());
        assertIterableEquals(List.of(1L, 2L), client.requestedProviderIds);
    }

    @Test
    void generateStyledMetadataSkipsRealImageCards() {
        FakeAiTitleClient client = new FakeAiTitleClient();
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of(provider(1L, 1))), client);

        Optional<PreviewMetadata> result = service.generateStyledMetadata(
                new PreviewMetadata(
                        "https://example.com/source",
                        "https://example.com/canonical",
                        "bilibili",
                        "真实图片标题",
                        "描述",
                        "Bilibili",
                        "https://i.example.com/thumb.jpg",
                        1200,
                        630,
                        ContentType.VIDEO,
                        "正文"
                ),
                new AiTitleService.StylePrompt("fun", "请总结 {raw_content}", "hash")
        );

        assertTrue(result.isEmpty());
        assertTrue(client.requestedProviderIds.isEmpty());
    }

    private PreviewMetadata generatedTextMetadata() {
        return new PreviewMetadata(
                "https://example.com/source",
                "https://example.com/canonical",
                "v2ex",
                "原始标题",
                "描述",
                "V2EX",
                "generated://v2ex/title-card/1",
                1200,
                630,
                ContentType.ARTICLE,
                "原文正文"
        );
    }

    private static AiProviderRecord provider(long id, int sortOrder) {
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(id);
        provider.setName("provider-" + id);
        provider.setEnabled(true);
        provider.setSortOrder(sortOrder);
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setApiKind("CHAT_COMPLETIONS");
        provider.setModel("gpt-test");
        provider.setEffort("");
        provider.setApiKey("");
        provider.setUpdatedAt(1L);
        return provider;
    }

    private static final class FakeAiProviderMapper implements AiProviderMapper {
        private final List<AiProviderRecord> providers;

        private FakeAiProviderMapper(List<AiProviderRecord> providers) {
            this.providers = providers;
        }

        @Override
        public List<AiProviderRecord> selectAllProviders() {
            return providers;
        }

        @Override
        public List<AiProviderRecord> selectEnabledProviders() {
            return providers;
        }

        @Override
        public AiProviderRecord selectProvider(long id) {
            return providers.stream()
                    .filter(provider -> provider.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void insertProvider(AiProviderRecord provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateProvider(AiProviderRecord provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteProvider(long id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAiTitleClient extends AiTitleClient {
        private final List<Long> failProviderIds = new ArrayList<>();
        private final List<Long> requestedProviderIds = new ArrayList<>();
        private String title = "AI 标题";

        private FakeAiTitleClient() {
            super(null, null);
        }

        @Override
        public Optional<String> generateTitle(AiProviderRecord provider, String prompt) throws IOException {
            requestedProviderIds.add(provider.getId());
            if (failProviderIds.contains(provider.getId())) {
                throw new IOException("provider failed");
            }
            return Optional.ofNullable(title);
        }
    }
}
