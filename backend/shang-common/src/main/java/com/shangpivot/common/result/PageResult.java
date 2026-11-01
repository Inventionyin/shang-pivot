package com.shangpivot.common.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页结果")
public class PageResult<T> {
    @Schema(description = "总条数", example = "100")
    private long total;
    @Schema(description = "当前页数据列表")
    private List<T> records;

    public static <T> PageResult<T> of(long total, List<T> records) {
        return new PageResult<>(total, records == null ? Collections.emptyList() : records);
    }

    public static <T> PageResult<T> empty() {
        return of(0, Collections.emptyList());
    }
}
