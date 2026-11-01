# 05-assets — 项目资产

放**不属于某一份文档、但项目运行/演示需要**的原始资产文件。文档正文里的插图、截图请放各自章节目录（如 `01-requirements/shots/`）；品牌视觉规范与 Logo 见 `02-design/brand/`。

## product/ — 演示商品图原件

`product_*.jpg`（30 张，约 384 KB）是**演示商品图片的原始字节**，用途单一：上传到 MinIO，供后端 `/admin/files/view` 代理读取。

### 与数据库的对应关系

`V4__seed_catalog_orders_from_projectku.sql` 在 `identity.file_object` 里声明了这批对象（id 201–246）：

| 本目录文件 | MinIO 对象键 | 桶 |
|---|---|---|
| `product_2.jpg` | `PRODUCT/seed/product_2.jpg` | `shang-pivot` |

`size_bytes` 字段与本目录文件的实际字节数**逐一对应**（迁移脚本里的数字就是这些文件的大小）。数据库里 `spu.pic` / `spu.pics` 存的是代理 URL：

```
/admin/files/view?objectKey=PRODUCT%2Fseed%2Fproduct_2.jpg
```

因此**改这些文件必须同步改迁移脚本里的 `size_bytes`**，否则元数据与实际对象不一致。

### 上传到 MinIO

仓库不包含自动上传步骤（`docker-compose.yml` 的 `minio-init` 只建桶）。新环境需要手动灌一次：

```bash
# 以本地 compose 起的 MinIO 为例
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb -p local/shang-pivot
mc cp docs/05-assets/product/*.jpg local/shang-pivot/PRODUCT/seed/
mc anonymous set download local/shang-pivot
```

> 注意桶名：应用默认读 `shang-pivot`（`application.yaml` / `.env`），而 `docker-compose.yml` 的 `minio-init` 建的桶是 `luoshu-rag`。两者不一致，详见根 `README.md` 的「常见问题」。

### 为什么放在这里

这批图原先在 `shang-admin-web/public/`，会被 Vite 原样复制进 `dist/` 并暴露成公开 URL，但它们与前端渲染无关（前端代码零引用，商品图一律走后端代理）。挪到此处后：前端构建不再打包它们，仓库仍保有可重放的演示数据原件。

**不要删除**：MinIO 服务端是运行时唯一来源，这里是从零重建演示环境的唯一原件。