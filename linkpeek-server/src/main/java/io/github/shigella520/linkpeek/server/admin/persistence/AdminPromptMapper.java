package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminPromptMapper {
    List<AdminPromptRecord> selectAllPrompts();

    List<String> selectStyles();

    AdminPromptRecord selectPrompt(@Param("style") String style);

    void upsertPrompt(AdminPromptRecord prompt);

    int deletePrompt(@Param("style") String style);
}
