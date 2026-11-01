# trace_id 未落库（D-23）

`BaseEntity.traceId` 缺 `@TableField(fill = FieldFill.INSERT)`，`strictInsertFill` 不生效；
address/order/stock/review 等表 trace_id 全为 NULL，导致无法按 trace 反查数据行。
契约中暂不把 trace_id 列入生成列断言，修复后应恢复并补断言。
