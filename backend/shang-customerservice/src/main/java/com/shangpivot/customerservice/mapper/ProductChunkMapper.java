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
            INSERT INTO customerservice.product_chunk (id, spu_id, name, subtitle, category_name, alias_text, price_min, price_max,
                                                       sold_count, embedding, refreshed_at, created_at, updated_at, deleted)
            VALUES (#{id}, #{spuId}, #{name}, #{subtitle}, #{categoryName}, #{aliasText}, #{priceMin}, #{priceMax},
                    #{soldCount}, CAST(#{embedding} AS vector), NOW(), NOW(), NOW(), FALSE)
            """)
    int upsertWithVector(@Param("id") Long id, @Param("spuId") Long spuId, @Param("name") String name,
                         @Param("subtitle") String subtitle, @Param("categoryName") String categoryName, @Param("aliasText") String aliasText,
                         @Param("priceMin") java.math.BigDecimal priceMin, @Param("priceMax") java.math.BigDecimal priceMax,
                         @Param("soldCount") Long soldCount, @Param("embedding") String embedding);

    @Insert("""
            INSERT INTO customerservice.product_chunk (id, spu_id, name, subtitle, category_name, alias_text, price_min, price_max,
                                                       sold_count, refreshed_at, created_at, updated_at, deleted)
            VALUES (#{id}, #{spuId}, #{name}, #{subtitle}, #{categoryName}, #{aliasText}, #{priceMin}, #{priceMax},
                    #{soldCount}, NOW(), NOW(), NOW(), FALSE)
            """)
    int upsertLexical(@Param("id") Long id, @Param("spuId") Long spuId, @Param("name") String name,
                      @Param("subtitle") String subtitle, @Param("categoryName") String categoryName, @Param("aliasText") String aliasText,
                      @Param("priceMin") java.math.BigDecimal priceMin, @Param("priceMax") java.math.BigDecimal priceMax,
                      @Param("soldCount") Long soldCount);

    @Update("""
            UPDATE customerservice.product_chunk
            SET name = #{name}, subtitle = #{subtitle}, category_name = #{categoryName}, alias_text = #{aliasText},
                price_min = #{priceMin}, price_max = #{priceMax}, sold_count = #{soldCount},
                embedding = CAST(#{embedding} AS vector), refreshed_at = NOW(), updated_at = NOW()
            WHERE spu_id = #{spuId} AND deleted = FALSE
            """)
    int updateWithVector(@Param("spuId") Long spuId, @Param("name") String name, @Param("subtitle") String subtitle,
                         @Param("categoryName") String categoryName, @Param("aliasText") String aliasText, @Param("priceMin") java.math.BigDecimal priceMin,
                         @Param("priceMax") java.math.BigDecimal priceMax, @Param("soldCount") Long soldCount,
                         @Param("embedding") String embedding);

    @Update("""
            UPDATE customerservice.product_chunk
            SET name = #{name}, subtitle = #{subtitle}, category_name = #{categoryName}, alias_text = #{aliasText},
                price_min = #{priceMin}, price_max = #{priceMax}, sold_count = #{soldCount},
                refreshed_at = NOW(), updated_at = NOW()
            WHERE spu_id = #{spuId} AND deleted = FALSE
            """)
    int updateLexical(@Param("spuId") Long spuId, @Param("name") String name, @Param("subtitle") String subtitle,
                      @Param("categoryName") String categoryName, @Param("aliasText") String aliasText, @Param("priceMin") java.math.BigDecimal priceMin,
                      @Param("priceMax") java.math.BigDecimal priceMax, @Param("soldCount") Long soldCount);

    @Select("SELECT COALESCE(MAX(spu_id), 0) FROM customerservice.product_chunk WHERE deleted = FALSE")
    long maxIndexedSpuId();

    @Select("SELECT COUNT(*) FROM customerservice.product_chunk WHERE deleted = FALSE")
    long countIndexed();

    /** 向量检索：返回 spu_id + score；可选品类/预算硬过滤。 */
    @Select("""
            <script>
            SELECT spu_id, 1 - (embedding &lt;=&gt; CAST(#{query} AS vector)) AS score
            FROM customerservice.product_chunk
            WHERE deleted = FALSE AND embedding IS NOT NULL
            <if test="categories != null and categories.size() > 0">
              AND category_name IN
              <foreach collection="categories" item="c" open="(" separator="," close=")">#{c}</foreach>
            </if>
            <if test="budgetMax != null">
              AND price_min &lt;= #{budgetMax}
            </if>
            <if test="budgetMin != null">
              AND COALESCE(price_max, price_min) &gt;= #{budgetMin}
            </if>
            ORDER BY embedding &lt;=&gt; CAST(#{query} AS vector)
            LIMIT #{topK}
            </script>
            """)
    List<Map<String, Object>> searchVector(@Param("query") String query, @Param("topK") int topK,
                                           @Param("categories") List<String> categories,
                                           @Param("budgetMin") java.math.BigDecimal budgetMin,
                                           @Param("budgetMax") java.math.BigDecimal budgetMax);

    /** 词法检索：bigram 命中数打分（名称 2 分 / 副标题、类目 1 分），可选品类/预算硬过滤。 */
    @Select("""
            <script>
            SELECT spu_id,
                   <foreach collection="grams" item="g" separator=" + ">
                       (CASE WHEN LOWER(name) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%') THEN 2 ELSE 0 END)
                       + (CASE WHEN LOWER(subtitle) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%') THEN 1 ELSE 0 END)
                       + (CASE WHEN LOWER(category_name) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%') THEN 1 ELSE 0 END)
                       + (CASE WHEN LOWER(alias_text) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%') THEN 1 ELSE 0 END)
                   </foreach>
                   AS score
            FROM customerservice.product_chunk
            WHERE deleted = FALSE
              AND <foreach collection="grams" item="g" separator=" OR " open="(" close=")">
                      (
                          LOWER(name) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%')
                          OR LOWER(subtitle) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%')
                          OR LOWER(category_name) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%')
                          OR LOWER(alias_text) LIKE CONCAT('%', LOWER(CAST(#{g} AS text)), '%')
                      )
                  </foreach>
            <if test="categories != null and categories.size() > 0">
              AND category_name IN
              <foreach collection="categories" item="c" open="(" separator="," close=")">#{c}</foreach>
            </if>
            <if test="budgetMax != null">
              AND price_min &lt;= #{budgetMax}
            </if>
            <if test="budgetMin != null">
              AND COALESCE(price_max, price_min) &gt;= #{budgetMin}
            </if>
            ORDER BY score DESC, spu_id ASC
            LIMIT #{topK}
            </script>
            """)
    List<Map<String, Object>> searchLexical(@Param("grams") List<String> grams, @Param("topK") int topK,
                                            @Param("categories") List<String> categories,
                                            @Param("budgetMin") java.math.BigDecimal budgetMin,
                                            @Param("budgetMax") java.math.BigDecimal budgetMax);
}
