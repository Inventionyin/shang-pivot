package com.shangpivot.customerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shangpivot.customerservice.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /** 向量写入：embedding 以 '[1,2,...]' 字符串传参（数据源 stringtype=unspecified 下 PG 自动转型）。 */
    @Insert("""
            INSERT INTO customerservice.knowledge_chunk (id, doc_id, seq, chunk_text, embedding, created_at, updated_at, deleted)
            VALUES (#{id}, #{docId}, #{seq}, #{chunkText}, CAST(#{embedding} AS vector), NOW(), NOW(), FALSE)
            """)
    int insertWithVector(@Param("id") Long id, @Param("docId") Long docId, @Param("seq") int seq,
                         @Param("chunkText") String chunkText, @Param("embedding") String embedding);

    @Insert("""
            INSERT INTO customerservice.knowledge_chunk (id, doc_id, seq, chunk_text, created_at, updated_at, deleted)
            VALUES (#{id}, #{docId}, #{seq}, #{chunkText}, NOW(), NOW(), FALSE)
            """)
    int insertLexical(@Param("id") Long id, @Param("docId") Long docId, @Param("seq") int seq,
                      @Param("chunkText") String chunkText);

    @Update("UPDATE customerservice.knowledge_chunk SET deleted = TRUE WHERE doc_id = #{docId}")
    int softDeleteByDocId(@Param("docId") Long docId);

    /** 余弦相似检索；返回 chunk_text + score。 */
    @Select("""
            SELECT chunk_text, 1 - (embedding <=> CAST(#{query} AS vector)) AS score
            FROM customerservice.knowledge_chunk
            WHERE deleted = FALSE AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(#{query} AS vector)
            LIMIT #{topK}
            """)
    List<Map<String, Object>> searchVector(@Param("query") String query, @Param("topK") int topK);

    /** 词法兜底：bigram 命中数打分。 */
    @Select("""
            <script>
            SELECT chunk_text, doc_id,
                   <foreach collection="grams" item="g" separator=" + ">
                       (CASE WHEN chunk_text LIKE CONCAT('%', CAST(#{g} AS text), '%') THEN 1 ELSE 0 END)
                   </foreach>
                   AS score
            FROM customerservice.knowledge_chunk
            WHERE deleted = FALSE
              AND <foreach collection="grams" item="g" separator=" OR " open="(" close=")">
                      chunk_text LIKE CONCAT('%', CAST(#{g} AS text), '%')
                  </foreach>
            ORDER BY score DESC, id ASC
            LIMIT #{topK}
            </script>
            """)
    List<Map<String, Object>> searchLexical(@Param("grams") List<String> grams, @Param("topK") int topK);
}
