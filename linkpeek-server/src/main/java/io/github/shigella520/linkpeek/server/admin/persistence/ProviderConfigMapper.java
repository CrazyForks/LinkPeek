package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProviderConfigMapper {
    List<ProviderConfigRecord> selectAllConfigs();

    List<ProviderConfigRecord> selectProviderConfigs(@Param("providerId") String providerId);

    ProviderConfigRecord selectConfig(
            @Param("providerId") String providerId,
            @Param("configKey") String configKey
    );

    void upsertConfig(ProviderConfigRecord config);
}
