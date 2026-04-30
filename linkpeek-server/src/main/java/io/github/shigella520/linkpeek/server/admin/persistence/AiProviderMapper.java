package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiProviderMapper {
    List<AiProviderRecord> selectAllProviders();

    List<AiProviderRecord> selectEnabledProviders();

    AiProviderRecord selectProvider(@Param("id") long id);

    void insertProvider(AiProviderRecord provider);

    int updateProvider(AiProviderRecord provider);

    int deleteProvider(@Param("id") long id);
}
