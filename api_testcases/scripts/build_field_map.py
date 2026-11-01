#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《字段映射》文档：数据库列 ↔ Java 实体字段 ↔ 接口 JSON 字段。

用途：把「一个业务字段在库里叫什么、在代码里叫什么、在接口里长什么样」三者对齐，
便于人工核对，也便于写接口自动化时确定断言用哪个名字、什么类型。

数据来源（都有单一权威，不猜）：
  - DB 列与类型      ← artifacts/schema-model.json（build_db_dict.py 从 Flyway 迁移解析）
  - Java 实体字段     ← backend/**/entity/*.java（直接解析源码）
  - 接口 JSON 形态    ← 全局序列化约定（JacksonConfig + application.yaml），并对每个端点标注
                        它返回的是「实体直出」还是 Map/DTO（来自 artifacts/endpoint-model.json）

一致性检查（本脚本的核心价值）：
  逐实体把「实体字段 snake_case」与「表列名」做双向比对，
  出现「有列无字段」「有字段无列」都会写进 §5 偏差清单并计为告警。
  标了 @TableField(exist = false) 的字段视为非持久化，不参与比对（不算偏差）。

用法：
  python3 scripts/build_field_map.py [--out-md <path>] [--out-json <path>]
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
ENTITY_DIRS = sorted(REPO.glob("backend/*/src/main/java/**/entity"))
BASE_ENTITY_FILE = "backend/shang-common/src/main/java/com/shangpivot/common/entity/BaseEntity.java"

# JSON 形态规则：依据 JacksonConfig（Long→String）+ application.yaml（non_null / 日期不转时间戳 / Asia/Shanghai）
# 备注只写「会咬人的」，全局规则见 §1，不在每行重复，保证表格可扫读。
JSON_TYPE_RULES = [
    (("Long", "long"), "string", "Long→字符串"),
    (("Integer", "int", "Short", "short", "Byte", "byte"), "number", ""),
    (("BigDecimal", "Double", "double", "Float", "float"), "number", ""),
    (("Boolean", "boolean"), "boolean", ""),
    (("String", "Character", "char"), "string", ""),
    (("OffsetDateTime", "LocalDateTime", "LocalDate", "Instant"), "string", ""),
]


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def unwrap(gen: str) -> str:
    """List<String> → List；Map<String,Object> → Map。"""
    return gen.split("<", 1)[0].strip()


def json_type(java_type: str) -> tuple[str, str]:
    base = unwrap(java_type)
    if base in ("List", "Set", "Collection"):
        return "array", "JSONB→array"
    if base in ("Map", "Object"):
        return "object", "JSONB→object"
    for names, jt, note in JSON_TYPE_RULES:
        if base in names:
            return jt, note
    return "object", ""


# --------------------------------------------------------------------------- #
# 实体解析
# --------------------------------------------------------------------------- #

FIELD_RE = re.compile(r"^\s*private\s+([\w<>,.\s\[\]]+?)\s+(\w+)\s*;\s*$")
ANNO_RE = re.compile(r"^\s*(@\w+.*)$")


def java_string_literal(args: str, key: str) -> str | None:
    """取 Java 注解里的字符串字面量值，正确处理 \\" 转义。

    例：value = "\\"order\\""  →  返回 '"order"'（含引号，由调用方决定是否剥掉）
    """
    m = re.search(rf'{key}\s*=\s*"((?:[^"\\]|\\.)*)"', args)
    if not m:
        return None
    return m.group(1).replace('\\"', '"').replace("\\\\", "\\")


def parse_controller_return_types() -> dict[tuple[str, str], str]:
    """从控制器源码抽出「(HTTP 方法, 完整路径) → Java 返回类型」。

    用于判定某端点是否**直接返回实体**（返回类型里出现实体类名），
    这是写断言时最有用的信息：响应字段名 = 实体字段名 = §3 的表。
    """
    out: dict[tuple[str, str], str] = {}
    annos = [("GetMapping", "GET"), ("PostMapping", "POST"), ("PutMapping", "PUT"),
             ("DeleteMapping", "DELETE"), ("PatchMapping", "PATCH")]
    for path in sorted(REPO.glob("backend/*/src/main/java/**/controller/*.java")):
        prefix = ""
        pending: list[tuple[str, str]] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not prefix:
                m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', line)
                if m:
                    prefix = m.group(1)
            for anno, meth in annos:
                if f"@{anno}" in line:
                    sm = re.search(r"@" + anno + r'\(\s*(?:value\s*=\s*)?"([^"]*)"', line)
                    sub = sm.group(1) if sm and sm.group(1) else ""
                    pending.append((meth, prefix + sub))
            md = re.match(r"\s*public\s+(.+?)\s+\w+\s*\(", line)
            if md and pending:
                rt = md.group(1).strip()
                for meth, full in pending:
                    out[(meth, full)] = rt
                pending = []
    return out


def entities_in_return_type(rt: str, entity_classes: set[str]) -> list[str]:
    """返回类型里出现的实体类名（词边界匹配，避免 Category 命中 CategoryController）。"""
    found = []
    for cls in sorted(entity_classes):
        if re.search(rf"\b{cls}\b", rt):
            found.append(cls)
    return found


def parse_fields(lines: list[str]) -> list[dict]:
    """从一个 Java 类的源码行里抽出字段声明（带前置注解与真实行号）。"""
    fields: list[dict] = []
    pending: list[str] = []
    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "*", "/*")):
            continue
        if ANNO_RE.match(line) and not stripped.startswith("@TableName"):
            pending.append(stripped.rstrip())
            continue
        fm = FIELD_RE.match(line)
        if fm:
            fields.append({
                "field": fm.group(2),
                "java_type": re.sub(r"\s+", "", fm.group(1)),
                "annotations": pending,
                "line": idx,
                "inherited": False,
            })
            pending = []
            continue
        if not stripped.startswith("@"):
            pending = []
    return fields


def load_base_entity_fields() -> list[dict]:
    """解析 BaseEntity.java 得到基类字段（含真实行号），供 extends BaseEntity 的实体复用。

    不硬编码字段表：基类改动时这里自动跟上，避免文档与源码脱节。
    """
    p = REPO / BASE_ENTITY_FILE
    if not p.exists():
        return []
    return parse_fields(p.read_text(encoding="utf-8").splitlines())


def parse_entity(path: Path) -> dict | None:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    table = None
    schema = None
    auto_result_map = False
    class_name = None
    extends_base = False

    for line in lines:
        m = re.match(r'\s*@TableName\((.*)\)\s*$', line)
        if m and table is None:
            args = m.group(1)
            tv = java_string_literal(args, "value")
            if tv:
                # 保留字表在注解里写成 "\"order\""，剥掉引号得到物理表名 order
                table = tv.strip().strip('"')
            sv = java_string_literal(args, "schema")
            if sv:
                schema = sv
            auto_result_map = "autoResultMap" in args and "true" in args
        if class_name is None:
            cm = re.match(r"\s*public\s+(?:abstract\s+)?class\s+(\w+)", line)
            if cm:
                class_name = cm.group(1)
                extends_base = "extends BaseEntity" in line
    if not table or not class_name:
        return None  # BaseEntity 自身

    fields = parse_fields(lines)

    entry = {
        "class": class_name,
        "table": f"{schema}.{table}" if schema else table,
        "schema": schema,
        "table_name": table,
        "auto_result_map": auto_result_map,
        "extends_base_entity": extends_base,
        "file": str(path.relative_to(REPO)),
        "fields": fields,
    }
    return entry


def load_entities() -> list[dict]:
    entities = []
    for d in ENTITY_DIRS:
        for p in sorted(d.glob("*.java")):
            e = parse_entity(p)
            if e:
                entities.append(e)
    return entities


# --------------------------------------------------------------------------- #
# 组装
# --------------------------------------------------------------------------- #

def build(entities: list[dict], schema_model: dict, endpoint_model: dict, fingerprint: str,
          base_fields: list[dict]) -> dict:
    tables = schema_model.get("tables", {})
    by_class: dict[str, dict] = {}
    warnings: list[dict] = []

    for e in entities:
        tkey = e["table"]
        cols = tables.get(tkey, {}).get("columns", [])
        col_by_name = {c["name"]: c for c in cols}

        all_fields = list(e["fields"])
        if e["extends_base_entity"]:
            all_fields += [dict(f, inherited=True) for f in base_fields]

        rows = []
        matched_cols = set()
        for f in all_fields:
            col_name = "id" if (f["inherited"] and f["field"] == "id") else snake(f["field"])
            col = col_by_name.get(col_name)
            non_persistent = any("exist = false" in a for a in f["annotations"])
            if non_persistent:
                col = None
            if col:
                matched_cols.add(col_name)
            elif not non_persistent:
                warnings.append({
                    "kind": "字段无对应列",
                    "table": tkey, "class": e["class"],
                    "detail": f"{e['class']}.{f['field']} → 期望列 `{col_name}` 在 {tkey} 不存在",
                })
            jt, jnote = json_type(f["java_type"])
            rows.append({
                "column": col_name,
                "db_type": col["type"] if col else ("（非持久化）" if non_persistent else "—"),
                "nullable": col["nullable"] if col else None,
                "default": col["default"] if col else None,
                "field": f["field"],
                "java_type": f["java_type"],
                "json_field": f["field"],
                "json_type": jt,
                "note": jnote,
                "annotations": f["annotations"],
                "inherited": f.get("inherited", False),
                "line": f["line"],
                "non_persistent": non_persistent,
            })

        for c in cols:
            if c["name"] not in matched_cols:
                warnings.append({
                    "kind": "列无对应字段",
                    "table": tkey, "class": e["class"],
                    "detail": f"列 `{c['name']}`（{c['type']}）在 {e['class']} 无对应字段",
                })

        rows.sort(key=lambda r: (0 if r["column"] == "id" else 1, r["column"]))
        by_class[e["class"]] = {**e, "rows": rows}

    # 端点 → 实体，分三类：
    #   响应直出：控制器方法返回类型里出现实体类名（读接口最有价值）
    #   请求体即实体：dto_class 命中实体类名（写接口，请求字段 = 实体字段）
    #   其余：Map / VO 响应，字段需读代码
    entity_classes = set(by_class)
    return_types = parse_controller_return_types()
    resp_direct: dict[str, list[dict]] = {}
    req_entity: dict[str, list[dict]] = {}
    map_based: list[dict] = []
    for ep in endpoint_model.get("endpoints", []):
        key = (ep.get("method"), ep.get("path"))
        rt = return_types.get(key, "")
        hits = entities_in_return_type(rt, entity_classes) if rt else []
        if hits:
            for cls in hits:
                resp_direct.setdefault(cls, []).append({**ep, "return_type": rt})
            continue
        dc = (ep.get("dto_class") or "").strip()
        target = dc.split("<")[0]
        if target in entity_classes:
            req_entity.setdefault(target, []).append(ep)
        else:
            map_based.append({**ep, "return_type": rt})

    return {
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "generator": "api_testcases/scripts/build_field_map.py",
        "fingerprint": fingerprint,
        "schema_fingerprint": schema_model.get("migration_sha256", "")[:16],
        "entities": by_class,
        "resp_direct": resp_direct,
        "req_entity": req_entity,
        "map_endpoints": map_based,
        "return_type_coverage": (sum(1 for ep in endpoint_model.get("endpoints", [])
                                     if (ep.get("method"), ep.get("path")) in return_types),
                                 len(endpoint_model.get("endpoints", []))),
        "warnings": warnings,
    }


def render(model: dict) -> str:
    L: list[str] = []
    add = L.append
    ents = model["entities"]
    total_fields = sum(len(e["rows"]) for e in ents.values())

    add("# 字段映射：数据库列 ↔ 实体字段 ↔ 接口 JSON")
    add("")
    add("> **机制区文档（禁止手改）**。由 `api_testcases/scripts/build_field_map.py` 生成：")
    add("> DB 列来自 `schema-model.json`（Flyway 迁移解析），Java 字段来自 `backend/**/entity/*.java` 源码解析，")
    add("> JSON 形态来自全局序列化约定并对每个端点标注返回类型。")
    add(">")
    add(f"> 生成命令：`python3 api_testcases/scripts/build_field_map.py`  ")
    add(f"> 版本指纹：`{model['fingerprint']}`（schema `{model['schema_fingerprint']}`）  ·  生成时间：{model['generated_at']}  ")
    add(f"> 覆盖：{len(ents)} 个实体 / {total_fields} 个字段映射")
    add("")
    add("## 1. 三层转换规则（先读这节，后面所有表都按它读）")
    add("")
    add("```text")
    add("数据库列名            Java 实体字段         接口 JSON 字段")
    add("snake_case     ⇄     camelCase      ⇄     camelCase（同名）")
    add("```")
    add("")
    add("| 环节 | 规则 | 证据 |")
    add("|---|---|---|")
    add("| 列名 → 字段 | MyBatis-Plus `map-underscore-to-camel-case: true`，自动把 `snake_case` 映射成 `camelCase` | `backend/shang-server/src/main/resources/application.yaml:76-78` |")
    add("| 字段 → JSON | Jackson 直接用字段名，**不做任何改名** | `JacksonConfig.java`（只注册 Long 序列化器，无命名策略） |")
    add("| **`Long` → JSON 字符串** | 全局 `ToStringSerializer`：所有 `Long`/`long` **返回字符串**，不是数字 | `JacksonConfig.java:17-22`；实测 `'total':'3'`、`'customerId':'9001'` |")
    add("| `Integer` / `BigDecimal` / `Boolean` | 保持 number / boolean | 实测 `'quantity':49`、`'price':599.00` |")
    add("| 时间字段 | ISO-8601 **字符串**（带偏移，Asia/Shanghai，不转时间戳） | `application.yaml:39-45`；实测 `'2026-09-30T00:27:19.62973+08:00'` |")
    add("| JSONB 列 | 映射为 `List` / `Map`，在 JSON 里就是 array / object | 实体上的 `@TableField(typeHandler = JacksonTypeHandler.class)` |")
    add("| `null` 字段 | **整个键被省略**（不是 `null`） | `application.yaml:44`（`default-property-inclusion: non_null`） |")
    add("| 分页响应 | `PageResult` 包成 `{total, records}`；**`total` 是字符串**（Long） | `PageResult.java`；实测 `'total':'3'` |")
    add("| 逻辑删列 | `deleted` 是**实体字段**，会出现在 JSON 里（`false`） | `BaseEntity.java:23-24` |")
    add("")
    add("> **写断言时的直接后果**：`total`、`id`、`*Id`、`soldCount`、`orderCount` 等 Long 字段一律按**字符串**比对；")
    add("> 按数字断言会假失败。缺字段可能只是 `null` 被省略——先判存在再取值。")
    add("")

    add("## 2. 实体 ↔ 表 映射总览")
    add("")
    add("| 实体 | 表 | 字段数 | 继承 BaseEntity | autoResultMap | 源码 |")
    add("|---|---|---|---|---|---|")
    for name in sorted(ents):
        e = ents[name]
        add(f"| `{e['class']}` | `{e['table']}` | {len(e['rows'])} | {'✔' if e['extends_base_entity'] else '—'} | "
            f"{'✔' if e['auto_result_map'] else '—'} | `{e['file']}` |")
    add("")

    add("## 3. 逐字段映射")
    add("")
    for name in sorted(ents):
        e = ents[name]
        add(f"### {e['class']} → `{e['table']}`")
        add("")
        add(f"- 源码：[`{e['file']}`](../../{e['file']})")
        base_link = f"[`BaseEntity.java`](../../{BASE_ENTITY_FILE})"
        add(f"- 继承 `BaseEntity`：{'是（含 id/createdAt/updatedAt/deleted/traceId，见 ' + base_link + '）' if e['extends_base_entity'] else '否'}"
            + ("　·　`autoResultMap = true`（JSONB 字段需要）" if e["auto_result_map"] else ""))
        add("")
        add("| 数据库列 | DB 类型 | 可空 | 默认 | Java 字段 | Java 类型 | JSON 字段 | JSON 类型 | 备注 | 源码行 |")
        add("|---|---|---|---|---|---|---|---|---|---|")
        for r in e["rows"]:
            db_type = r["db_type"]
            nullable = "—" if r["nullable"] is None else ("是" if r["nullable"] else "否")
            default = f"`{r['default']}`" if r["default"] else "—"
            note = r["note"]
            if r["non_persistent"]:
                note = "非持久化（`@TableField(exist = false)`）"
            elif r["annotations"]:
                note = (note + " " if note else "") + "`" + "` `".join(r["annotations"]) + "`"
            if r["inherited"]:
                src = f"`BaseEntity:L{r['line']}`"
            else:
                src = f"`L{r['line']}`"
            add(f"| `{r['column']}` | `{db_type}` | {nullable} | {default} | `{r['field']}` | `{r['java_type']}` | "
                f"`{r['json_field']}` | {r['json_type']} | {note or '—'} | {src} |")
        add("")

    add("## 4. 端点 ↔ 实体 的三种关系（决定字段名能不能直接套用 §3）")
    add("")
    cov, total = model.get("return_type_coverage", (0, 0))
    add(f"> 返回类型解析覆盖：**{cov}/{total}** 个端点从控制器源码取到了 Java 返回类型——")
    add("> 覆盖内的端点分类可信；未覆盖的会落到 4.3。")
    add("")

    add("### 4.1 响应直出实体（读接口 — 响应字段名 = §3 的 JSON 字段名）")
    add("")
    add("这类端点的 `Result<...>` / `PageResult<...>` 泛型里就是实体，**响应字段可直接用 §3 的表核对**。")
    add("")
    if model["resp_direct"]:
        add("| 实体 | 端点 | 原始返回类型 |")
        add("|---|---|---|")
        for cls in sorted(model["resp_direct"]):
            for x in sorted(model["resp_direct"][cls], key=lambda z: (z["path"], z["method"])):
                add(f"| `{cls}` | `{x['method']} {x['path']}` | `{x.get('return_type') or '—'}` |")
    else:
        add("无。")
    add("")

    add("### 4.2 请求体即实体（写接口 — 请求字段名 = §3 的 JSON 字段名）")
    add("")
    add("这类端点 `@RequestBody` 直接收实体，**提交体字段也可用 §3 核对**（注意 Long 入参按数字/字符串都接受，但回显是字符串）。")
    add("")
    if model["req_entity"]:
        add("| 实体 | 端点 |")
        add("|---|---|")
        for cls in sorted(model["req_entity"]):
            items = "、".join(f"`{x['method']} {x['path']}`"
                             for x in sorted(model["req_entity"][cls], key=lambda z: (z["path"], z["method"])))
            add(f"| `{cls}` | {items} |")
    else:
        add("无。")
    add("")

    add("### 4.3 其余端点（Map / VO / DTO 响应或请求 — 字段需按响应键或读代码）")
    add("")
    add(f"共 **{len(model['map_endpoints'])}** 个。**这些端点的字段无法从实体推导**；下表列出生成器已知的响应键，")
    add("标「未解析」的必须读控制器源码。")
    add("")
    add("| 方法 路径 | 原始返回类型 | 已知响应键 | 说明 |")
    add("|---|---|---|---|")
    for ep in sorted(model["map_endpoints"], key=lambda z: (z["path"], z["method"])):
        keys = "、".join(f"`{k}`" for k in (ep.get("resp_keys") or [])) or "（未解析，需读代码）"
        add(f"| `{ep['method']} {ep['path']}` | `{ep.get('return_type') or '—'}` | {keys} | {ep.get('summary') or ''} |")
    add("")
    add("> 写断言前对 4.3 的端点务必先发一次真实请求看响应体，不要照猜。")
    add("")

    add("## 5. 一致性偏差清单（生成器自动比对结果）")
    add("")
    warns = model["warnings"]
    if not warns:
        add("**无偏差**：所有实体字段与表列**双向完全对应**（既无「有字段无列」，也无「有列无字段」）。")
        add("")
        add("这条是本次生成的强断言——所以对任何一张表，§3 的映射表都可以放心当作「接口字段名与类型」的权威来源。")
    else:
        add(f"发现 **{len(warns)}** 处偏差，逐条核对后再采信 §3：")
        add("")
        add("| 类型 | 实体 | 对象 | 说明 |")
        add("|---|---|---|---|")
        for w in warns:
            add(f"| {w['kind']} | `{w['class']}`（`{w['table']}`） | `{w['class']}` | {w['detail']} |")
    add("")
    return "\n".join(L) + "\n"


def endpoint_model_endpoints(model: dict) -> list[dict]:
    """仅用于统计响应键覆盖率（从生成时注入的原始列表读）。"""
    return model.get("_all_endpoints", [])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-md", default=str(REPO / "docs/06-baseline/04-字段映射.md"))
    ap.add_argument("--out-json", default=str(ROOT / "artifacts/field-map.json"))
    ap.add_argument("--fingerprint", default="(unset)")
    args = ap.parse_args()

    schema_model = json.loads((ROOT / "artifacts/schema-model.json").read_text(encoding="utf-8"))
    endpoint_model = json.loads((ROOT / "artifacts/endpoint-model.json").read_text(encoding="utf-8"))
    entities = load_entities()
    model = build(entities, schema_model, endpoint_model, args.fingerprint, load_base_entity_fields())
    model["_all_endpoints"] = endpoint_model.get("endpoints", [])

    out_md, out_json = Path(args.out_md), Path(args.out_json)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text(render(model), encoding="utf-8")

    slim = {k: v for k, v in model.items() if k != "_all_endpoints"}
    out_json.write_text(json.dumps(slim, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({
        "实体": len(model["entities"]),
        "字段映射": sum(len(e["rows"]) for e in model["entities"].values()),
        "偏差": len(model["warnings"]),
        "响应直出实体组": len(model["resp_direct"]),
        "请求体即实体组": len(model["req_entity"]),
        "其余端点": len(model["map_endpoints"]),
        "返回类型解析覆盖": f"{model.get('return_type_coverage',(0,0))[0]}/{model.get('return_type_coverage',(0,0))[1]}",
        "输出md": str(out_md),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
