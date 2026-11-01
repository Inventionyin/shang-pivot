# 测试工程记忆索引
- [xdist 并发口径](xdist-grouping.md)：API 按主体接口分组、UI 3 worker、结果必须与串行一致
- [写接口 DB 契约](db-write-contracts.md)：请求字段→表列、定位策略与已知 trace_id 缺陷
- [trace_id 未落库](trace-id-defect.md)：BaseEntity.traceId 缺 fill 注解，行级 trace 全为空
- [中间件现状](middleware-gap.md)：RabbitMQ 订单事件（V21/幂等/DLQ）、Redis 四类键与用例
- [覆盖率两段式](coverage-two-tier.md)：静态矩阵 + JaCoCo tcpserver 脚本与限制
- [知识库与历史](knowledge-history.md)：运行归档、跨轮 diff、SQLite FTS 检索
- [后端日志路径](backend-log-paths.md)：跨目录搜索、排除 access 日志、按 mtime 取应用日志
