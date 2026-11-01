# Task Plan: shang-pivot 图片不显示排查

## Goal
定位线上 `http://111.170.33.2:60084/`（及本地）商城图片全部裂图的根因。

## Current Phase
Phase 1 完成（只做诊断，未改动任何代码或服务器）

## Next Step
等待用户确认修复方案；未获授权前不改服务器、不改代码。

## Phases

### Phase 1: 端到端链路排查
- [x] 读前端 `resolveMediaUrl` / 后端 `FileController` / `MinioObjectStorage`
- [x] 线上实测 `/admin/files/view` 与 `/api/home`
- [x] 核对部署包、systemd 单元、服务器部署记录
- [x] 对比本地实例症状
- **Status:** complete

### Phase 2: 修复（待用户授权）
- [ ] 决定方案：服务器自建 MinIO / 复用已有 S3 兼容服务 / 换本地静态图
- [ ] 对齐桶名与 `MINIO_*` 环境变量
- [ ] 上传 `docs/05-assets/product/*.jpg` 至 `PRODUCT/seed/`
- [ ] 复验 `/admin/files/view` 返回图片字节
- **Status:** pending

## Decisions Made
| Decision | Rationale |
|---|---|
| 只诊断不改动 | 用户问的是"为什么不显示"；改服务器属于生产变更，需单独授权 |
| 不改动线上 PostgreSQL 数据 | `pic` 字段存代理路径本身是设计的正确形态，病根在对象存储缺失 |
