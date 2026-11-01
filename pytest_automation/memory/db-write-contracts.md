# 写接口 DB 契约

契约源：`data/write_contracts.yaml` → `artifacts/write-contracts.json`。
定位：响应 ID / 路径 ID / 自然键（order_id）；Long 按字符串、Decimal 按数值、JSONB 按 JSON、软删过滤。
已覆盖 23 个写端点（22 个有测试），剩余写端点需补流程与契约。
