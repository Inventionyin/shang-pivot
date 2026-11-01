# 基线快照 manifest — `810986c`

> 当前交付版本（初始提交）

- 冻结时间：2026-10-10 15:53:18
- Git：`810986c`（main，提交数 1，tag 无）
- 迁移范围：`V1–V20`（20 个文件）
- 契约面：36 表 / 359 列 / 135 端点

## 指纹表

| 指纹 | 对象 |
|---|---|
| `810986c` | Git 提交 |
| `ca40a00517169e90` | `db/migration/**` 聚合 |
| `deead8755b091baa` | `backend/**/*.java` 聚合（166 文件） |
| `70903a027192f752` | `backend/pom.xml` |
| `641695c4195d7251` | `backend/shang-server/pom.xml` |
| `ba9740a13b0019d9` | `backend/shang-identity/pom.xml` |
| `83c313ecbb8c7004` | `backend/shang-commerce/pom.xml` |
| `f73f1a42332f0007` | `backend/shang-common/pom.xml` |
| `829b6a10b47d389d` | `backend/shang-server/src/main/resources/application.yaml` |
| `db3c7ae8bad3faad` | `backend/docker-compose.yml` |
| `6c6963619f03af92` | `shang-admin-web/package.json` |
| `fa0a105cc771e2d0` | `shang-mall/package.json` |
| `39d488c8a451dc06` | `ui_testcases/pom.xml` |
| `c2554af33132e0fd` | `api_testcases/requirements.txt` |
| `57d5d5b4a4c06f2a` | `docs/openapi-v3-api-docs.json` |
| `513cbafd8f25351e` | `docs/测试资料/接口文档.md` |
| `78226d11b242fdfc` | `artifacts/schema-model.json` |
| `5ff0509089d26f53` | `artifacts/endpoint-model.json` |
| `eaa7314694bc37eb` | `artifacts/field-map.json` |
| `acbd23268bd045b2` | `artifacts/call-graph.json` |

## 复算方式

```bash
python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \
    --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md
python3 scripts/build_db_dict.py --fingerprint "810986c"
python3 scripts/snapshot_baseline.py --label 810986c
```

复算后与快照逐字节一致 = 该版本基线仍成立；否则机制区文档已过期。
