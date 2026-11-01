# Progress Log

## Session: 2026-10-05

### Current Status
- **Phase:** 1 complete — 根因已确认
- **Root cause:** 线上服务器没有部署 MinIO（对象存储），且演示图字节从未上传；数据库只存了 `/admin/files/view?objectKey=...` 代理路径，后端读对象时统一失败为 `40400 文件不存在或无法读取`。

### Actions Taken
- 读前端 `shang-mall/src/utils/media.ts`、`shang-admin-web/src/utils/media.ts`：确认图片一律转成后端代理路径。
- 读后端 `FileController.view()` 与 `MinioObjectStorage`：确认任何对象存储异常都吞成 `40400`。
- 线上探测：`/admin/files/view` 返回应用自身 JSON `40400`（说明 Nginx 反代正常），`/api/home` 返回的 `pic` 就是 `/admin/files/view?objectKey=...`。
- 线上 9000/9001 端口是**别的项目**（证书 `*.yibase.cn`，返回 Next.js 应用与另一套 API），不是 MinIO。
- 找到并阅读 `/Users/imac/Downloads/E_commerce/shang-pivot/docs/服务器部署记录.md`，其「已知限制」直接写明未部署 MinIO。
- 检查 `.deploy/shang-pivot/` 发布包与 `shang-pivot.service`：无任何对象存储组件或依赖。
- 本地复现：`127.0.0.1:8086/admin/files/view` 同样返回 `40400`；本机无 minio 容器、无 minio 镜像、无 59000/9000 监听。

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| 线上 `/admin/files/view` | 返回 JPEG 字节 | `{"code":40400,...}` | FAIL（与诊断一致） |
| 线上 `/api/home` | 返回图片代理路径 | `/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_4.jpg` | PASS |
| 本地 `/admin/files/view` | 返回 JPEG 字节 | `{"code":40400,...}` | FAIL（与诊断一致） |

### Errors
| Error | Resolution |
|-------|------------|
| `init-session.sh` 报 operation not permitted | 改为手动创建 `.planning/2026-10-05-image-not-showing/` 三件套 |
