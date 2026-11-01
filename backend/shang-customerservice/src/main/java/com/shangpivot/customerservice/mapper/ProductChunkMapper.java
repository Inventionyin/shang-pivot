package com.shangpivot.customerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shangpivot.customerservice.entity.ProductChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ProductChunkMapper extends BaseMapper<ProductChunk> {

    @Insert("""
            INSERT INTO customerservice.product_chunk (id, spu_id, name, subtitle, category_name, price_min, price_max,
                                                       sold_count, embedding, refreshed_at, created_at, updated_at, deleted)
            VALUES (#{id}, #{spuId}, #{name}, #{subtitle}, #{categoryName}, #{priceMin}, #{priceMax},
                    #{soldCount}, CAST(#{embedding} AS vector), NOW(), NOW(), NOW(), FALSE)
            """)
    int upsertWithVector(@Param("id") Long id, @Param("spuId") Long spuId, @Param("name") String name,
                         @Param("subtitle") String subtitle, @Param("categoryName") String categoryName,
                         @Param("priceMin") java.math.BigDecimal priceMin, @Param("priceMax") java.math.BigDecimal priceMax,
                         @Param("soldCount") Long soldCount, @Param("embedding") String embedding);

    @Insert("""
            INSERT INTO customerservice.product_chunk (id, spu_id, name, subtitle, category_name, price_min, price_max,
                                                       sold_count, refreshed_at, created_at, updated_at, deleted)
            VALUES (#{id}, #{spuId}, #{name}, #{subtitle}, #{categoryName}, #{priceMin}, #{priceMax},
                    #{soldCount}, NOW(), NOW(), NOW(), FALSE)
            """)
    int upsertLexical(@Param("id") Long id, @Param("spuId") Long spuId, @Param("name") String name,
                      @Param("subtitle") String subtitle, @Param("categoryName") String categoryName,
                      @Param("priceMin") java.math.BigDecimal priceMin, @Param("priceMax") java.math.BigDecimal priceMax,
                      @Param("soldCount") Long soldCount);

    @Update("""
            UPDATE customerservice.product_chunk
            SET name = #{name}, subtitle = #{subtitle}, category_name = #{categoryName},
                price_min = #{priceMin}, price_max = #{priceMax}, sold_count = #{soldCount},
                embedding = CAST(#{embedding} AS vector), refreshed_at = NOW(), updated_at = NOW()
            WHERE spu_id = #{spuId} AND deleted = FALSE
            """)
    int updateWithVector(@Param("spuId") Long spuId, @Param("name") String name, @Param("subtitle") String subtitle,
                         @Param("categoryName") String categoryName, @Param("priceMin") java.math.BigDecimal priceMin,
                         @Param("priceMax") java.math.BigDecimal priceMax, @Param("soldCount") Long soldCount,
                         @Param("embedding") String embedding);

    @Update("""
            UPDATE customerservice.product_chunk
            SET name = #{name}, subtitle = #{subtitle}, category_name = #{categoryName},
                price_min = #{priceMin}, price_max = #{priceMax}, sold_count = #{soldCount},
                refreshed_at = NOW(), updated_at = NOW()
            WHERE spu_id = #{spuId} AND deleted = FALSE
            """)
    int updateLexical(@Param("spuId") Long spuId, @Param("name") String name, @Param("subtitle") String subtitle,
                      @Param("categoryName") String categoryName, @Param("priceMin") java.math.BigDecimal priceMin,
                      @Param("priceMax") java.math.BigDecimal priceMax, @Param("soldCount") Long soldCount);

    @Select("SELECT COALESCE(MAX(spu_id), 0) FROM customerservice.product_chunk WHERE deleted = FALSE")
    long maxIndexedSpuId();

    @Select("SELECT COUNT(*) FROM customerservice.product_chunk WHERE deleted = FALSE")
    long countIndexed();

    /** 向量检索：返回 spu_id + score。 */
    @Select("""
            SELECT spu_id, 1 - (embedding <=> CAST(#{query} AS vector)) AS score
            FROM customerservice.product_chunk
            WHERE deleted = FALSE AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(#{query} AS vector)
            LIMIT #{topK}
            """)
    List<Map<String, Object>> searchVector(@Param("query") String query, @Param("topK") int topK);

    /** 词法检索：bigram 命中数打分（名称 2 分 / 副标题、类目 1 分），返回 spu_id + score。 */
    @Select("""
            <script>
            SELECT spu_id,
                   <foreach collection="grams" item="g" separator=" + ">
                       (CASE WHEN name LIKE CONCAT('%', CAST(#{g} AS text), '%') THEN 2 ELSE 0 END)
                       + (CASE WHEN subtitle LIKE CONCAT('%', CAST(#{g} AS text), '%') THEN 1 ELSE 0 END)
                       + (CASE WHEN category_name LIKE CONCAT('%', CAST(#{g} AS text), '%') THEN 1 ELSE 0 END)
                   </foreach>
                   AS score
            FROM customerservice.product_chunk
            WHERE deleted = FALSE
              AND <foreach collection="grams" item="g" separator=" OR " open="(" close=")">
                      (
                          name LIKE CONCAT('%', CAST(#{g} AS text), '%')
                          OR subtitle LIKE CONCAT('%', CAST(#{g} AS text), '%')
                          OR category_name LIKE CONCAT('%', CAST(#{g} AS text), '%')
                      )
                  </foreach>
            ORDER BY score DESC, spu_id ASC
            LIMIT #{topK}
            </script>
            """)
    List<Map<String, Object>> searchLexical(@Param("grams") List<String> grams, @Param("topK") int topK);
}
