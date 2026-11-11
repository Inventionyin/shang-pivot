-- V31: 商品切片增加品牌/系列别名文本，供词法与向量检索使用。
-- 只新增列，不重写历史数据；部署后调用管理端 product-index/rebuild 即可回填。
ALTER TABLE customerservice.product_chunk
    ADD COLUMN IF NOT EXISTS alias_text VARCHAR(512);

COMMENT ON COLUMN customerservice.product_chunk.alias_text IS '品牌/系列中文别名（华为/小米/苹果…），由商品索引服务生成';
