-- 修复：commerce.address 缺 trace_id 列
--
-- 根因：V8 建表时 commerce.address 未包含 trace_id，而 Address 实体继承 BaseEntity
-- （BaseEntity 声明了 private String traceId），MyBatis-Plus 生成的 SELECT 列表因此包含 trace_id，
-- 导致所有按主键/条件查询地址的语句报 `column "trace_id" does not exist`。
--
-- 影响（实测 HTTP 500）：
--   GET    /api/addresses          → 收货地址列表不可用
--   DELETE /api/addresses/{id}     → 收货地址删除不可用
--   POST   /api/orders             → 下单时查收货地址即崩，下单流程整体不可用
--
-- 同类表（cart / cart_item / favorite / browse_history / home_slot*）的实体**未**继承 BaseEntity，
-- 因此不受影响；本次只修 address。

ALTER TABLE commerce.address
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
