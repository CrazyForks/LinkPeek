package io.github.shigella520.linkpeek.server.admin.persistence;

import io.github.shigella520.linkpeek.server.admin.model.AdminPreviewEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminPreviewEventMapper {
    long countPreviewEvents(@Param("query") String query);

    List<AdminPreviewEventRow> selectPreviewEvents(
            @Param("query") String query,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
