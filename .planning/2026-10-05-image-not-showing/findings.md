# Findings: 尚品汇图片不显示

## 问题
线上商城 `http://111.170.33.2:60084/` 商品图、品牌 Logo 全部裂图（后端返回 40400）。

## 图片链路（代码事实）
1. `spu.pic` / `spu.pics` / 品牌 Logo 等字段在数据库里存的是**后端代理路径**：
   `/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg`
   （见 `backend/shang-server/src/main/resources/db/migration/V4__seed_catalog_orders_from_projectku.sql`、`V16`、`V17`）
2. C 端 `shang-mall/src/utils/media.ts` 的 `resolveMediaUrl()` 对 `/admin/files/view` 开头的路径**原样透传**；
   对 `http(s)://{host}/{bucket}/{objectKey}` 会剥离 bucket 段改写成 `/api/files/view?...`。
3. 管理端 `shang-admin-web/src/utils/media.ts` 逻辑相同，固定走 `/admin/files/view`。
4. 后端 `FileController.view()` → `objectStorage.statObject(objectKey)`；
   任何失败（连不上 MinIO、桶不存在、对象不存在）统一映射为 `40400 文件不存在或无法读取`。
   （`backend/shang-identity/src/main/java/com/shangpivot/identity/controller/FileController.java`）
5. `MinioObjectStorage` 只构造客户端，**不检查也不创建桶**。

## 线上实测（111.170.33.2）
| 请求 | 结果 |
|---|---|
| `GET http://111.170.33.2:60084/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg` | 200 + `{"code":40400,"msg":"文件不存在或无法读取"}` |
| `GET http://111.170.33.2:60085/admin/files/view?...` | 同上 |
| `GET http://111.170.33.2:60084/api/home` | 200，`pic` 字段为 `/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_4.jpg` |

结论：Nginx `/admin/files/` 反代到 `127.0.0.1:8086` 是通的（返回的是应用自己的 JSON 错误码，不是 Nginx 404），
因此**前端路径与 Nginx 都没问题**，失败发生在后端读对象存储这一步。

## 根因
**服务器上根本没有部署 MinIO。** 部署记录明确写着：

> `shang-pivot/docs/服务器部署记录.md` → 已知限制：
> "当前未部署 MinIO。应用可以启动并浏览演示数据，但图片上传/对象存储相关操作需要后续提供 MinIO 或兼容 S3 服务后再启用。"

发布包 `.deploy/shang-pivot/shang-pivot-release.tar.gz` 只含 `app.jar` + `admin/` + `mall/` 静态产物，
`shang-pivot.service` 的 `After=` 只依赖 postgresql / redis，没有任何对象存储服务。

另外，即使补上 MinIO，**演示图字节也不会自动出现**：
`docs/05-assets/README.md` 写明仓库不含自动上传步骤，新环境必须手动灌一次：
```bash
mc alias set local http://<minio>:9000 <ak> <sk>
mc mb -p local/shang-pivot
mc cp docs/05-assets/product/*.jpg local/shang-pivot/PRODUCT/seed/
```
V4 迁移只往 `identity.file_object` 写了元数据（id 201–246），**没有写对象字节**。

## 本地同样症状（可直接复现）
- 本机 Docker 里只有 `shang-postgres(55432)` / `shang-redis(56379)`，**没有任何 minio 容器，也没有 minio 镜像**。
- 本机也没有监听 9000 / 59000 / 59001 的进程。
- `curl http://127.0.0.1:8086/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg`
  → 同样返回 `{"code":40400,"msg":"文件不存在或无法读取"}`。
- `backend/.env`：`MINIO_ENDPOINT=localhost:59000`、`MINIO_BUCKET_NAME=luoshu-rag`、
  `MINIO_PUBLIC_BASE_URL=http://localhost:59000/luoshu-rag`（指向一个并不存在的 MinIO）。
- 本机此前因 Docker Hub / Quay 拉不到 MinIO 镜像（`minio/minio:latest`、`minio/mc:latest` 均不可用），
  本地部署时就把 MinIO 排除在启动脚本之外了。

## 后续部署 MinIO 时的两个已知坑
1. **桶名不一致**：应用默认桶 `shang-pivot`，`backend/docker-compose.yml` 的 `minio-init` 建的是 `luoshu-rag`。
   两边必须对齐（README「常见问题」已记录）。
2. **种子图必须手动上传**，且 `docs/05-assets/README.md` 里的 `size_bytes` 要跟文件实际大小一致。
