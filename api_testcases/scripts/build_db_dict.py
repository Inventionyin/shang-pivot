#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Flyway 迁移生成《数据字典.md》与 schema-model.json。

真值层级：迁移 SQL > 实体类 > 设计态《数据模型.md》> PRD。
迁移是唯一权威——只有它能决定「最终态」是什么样（实体类可能漏字段，
设计文档可能过期）。

本脚本只做确定性解析，不做推断：
  识别 CREATE SCHEMA / CREATE TABLE / ALTER TABLE ADD COLUMN / DROP COLUMN /
  ALTER COLUMN SET|DROP DEFAULT|SET|DROP NOT NULL / DROP TABLE /
  CREATE [UNIQUE] INDEX / COMMENT ON TABLE|COLUMN。
  遇到无法识别的 DDL 语句**直接报错退出**并列出原文——绝不静默产出错误的最终态。
  （非 DDL 的 INSERT/UPDATE/DELETE/SELECT 语句一律忽略。）

用法：
  python3 scripts/build_db_dict.py [--migration-dir <dir>] [--out-md <path>] \
      [--out-json <path>] [--fingerprint <commit>]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 列修饰符关键字：解析 DEFAULT 表达式时在这些词处停下
MODIFIER_KEYWORDS = {
    "NOT", "NULL", "PRIMARY", "UNIQUE", "CHECK", "CONSTRAINT",
    "REFERENCES", "COLLATE", "GENERATED", "DEFAULT",
}
TABLE_CONSTRAINT_KEYWORDS = ("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE")
IGNORED_STATEMENT_PREFIXES = ("INSERT", "UPDATE", "DELETE", "SELECT", "WITH", "SET", "DO", "CREATE EXTENSION")
SCHEMA_QUALIFIED = r'(?:"?(\w+)"?\s*\.\s*)?"?(\w+)"?'


# --------------------------------------------------------------------------- #
# 语句切分
# --------------------------------------------------------------------------- #

def strip_line_comments(sql: str) -> str:
    """去掉 -- 行注释，但保留字符串字面量内的内容。"""
    out, i, n, in_str = [], 0, len(sql), False
    while i < n:
        ch = sql[i]
        if in_str:
            out.append(ch)
            if ch == "'":
                if i + 1 < n and sql[i + 1] == "'":  # 转义的单引号
                    out.append("'")
                    i += 2
                    continue
                in_str = False
            i += 1
            continue
        if ch == "'":
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "-" and i + 1 < n and sql[i + 1] == "-":
            while i < n and sql[i] != "\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_statements(sql: str) -> list[tuple[int, str]]:
    """按分号切分语句，返回 (行号, 语句)。忽略字符串字面量与括号内的分号。"""
    statements, buf, depth, in_str, line, start_line = [], [], 0, False, 1, 1
    i, n = 0, len(sql)
    while i < n:
        ch = sql[i]
        if not buf and ch in " \t\r\n":
            if ch == "\n":
                line += 1
            i += 1
            continue
        if not buf:
            start_line = line
        if in_str:
            buf.append(ch)
            if ch == "'":
                if i + 1 < n and sql[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_str = False
            elif ch == "\n":
                line += 1
            i += 1
            continue
        if ch == "'":
            in_str = True
            buf.append(ch)
        elif ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
        elif ch == ";" and depth == 0:
            statements.append((start_line, "".join(buf).strip()))
            buf = []
        else:
            buf.append(ch)
            if ch == "\n":
                line += 1
        i += 1
    if "".join(buf).strip():
        statements.append((start_line, "".join(buf).strip()))
    return statements


def split_top_level(text: str, sep: str = ",") -> list[str]:
    """按分隔符在括号深度 0 处切分，忽略字符串内的分隔符。"""
    parts, buf, depth, in_str = [], [], 0, False
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if in_str:
            buf.append(ch)
            if ch == "'":
                if i + 1 < n and text[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_str = False
            i += 1
            continue
        if ch == "'":
            in_str = True
            buf.append(ch)
        elif ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
        elif ch == sep and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf).strip())
    return parts


def parse_qualified(raw: str) -> tuple[str | None, str]:
    """解析 schema.table，表名可带引号（如 commerce."order"）。"""
    m = re.match(rf'^{SCHEMA_QUALIFIED}$', raw.strip())
    if not m:
        raise ValueError(f"无法解析表名: {raw!r}")
    return (m.group(1).lower() if m.group(1) else None), m.group(2)


def unquote_ident(ident: str) -> str:
    return ident.strip().strip('"')


def qualify(schema: str | None, table: str) -> str:
    return f"{schema}.{table}" if schema else table


# --------------------------------------------------------------------------- #
# 列定义解析
# --------------------------------------------------------------------------- #

def parse_column_def(text: str) -> dict:
    """解析单个列定义，返回 name/type/nullable/default/primary_key/check。"""
    text = text.strip()
    m = re.match(r'^"?(\w+)"?\s+(.*)$', text, re.S)
    if not m:
        raise ValueError(f"无法解析列定义: {text!r}")
    name, rest = m.group(1), m.group(2).strip()

    type_match = re.match(r'^([A-Za-z][A-Za-z0-9_ ]*?)(\s*\(([^)]*)\))?(?=\s|$)(.*)$', rest, re.S)
    if not type_match:
        raise ValueError(f"无法解析列类型: {text!r}")
    base_type = type_match.group(1).strip()
    if type_match.group(2):
        base_type = f"{base_type}{type_match.group(2).strip()}"
    tail = (type_match.group(4) or "").strip()

    tokens = split_top_level(tail, " ") if tail else []
    nullable = True
    default = None
    primary_key = False
    checks: list[str] = []

    i = 0
    while i < len(tokens):
        tok = tokens[i]
        upper = tok.upper()
        if upper == "NOT" and i + 1 < len(tokens) and tokens[i + 1].upper() == "NULL":
            nullable = False
            i += 2
            continue
        if upper == "NULL":
            nullable = True
            i += 1
            continue
        if upper == "PRIMARY":
            primary_key = True
            i += 1
            continue
        if upper == "KEY":
            i += 1
            continue
        if upper == "DEFAULT":
            j, val_tokens, depth = i + 1, [], 0
            while j < len(tokens):
                cand = tokens[j]
                if depth == 0 and cand.upper() in MODIFIER_KEYWORDS:
                    break
                depth += cand.count("(") - cand.count(")")
                val_tokens.append(cand)
                j += 1
            default = " ".join(val_tokens) if val_tokens else None
            i = j
            continue
        if upper == "CHECK":
            checks.append(" ".join(tokens[i:]))
            break
        if upper == "CONSTRAINT":
            checks.append(" ".join(tokens[i:]))
            break
        i += 1

    return {
        "name": name,
        "type": base_type,
        "nullable": nullable,
        "default": default,
        "primary_key": primary_key,
        "checks": checks,
        "comment": None,
        "source": None,
        "altered_by": [],
    }


# --------------------------------------------------------------------------- #
# 迁移解析
# --------------------------------------------------------------------------- #

class UnsupportedDDL(Exception):
    pass


def parse_create_table(stmt: str, version: str) -> tuple[str, dict]:
    m = re.match(
        r'^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(.+?)\s*\((.*)\)\s*$',
        stmt, re.S | re.I,
    )
    if not m:
        raise UnsupportedDDL(stmt)
    schema, table = parse_qualified(m.group(1))
    body = m.group(2)

    columns: list[dict] = []
    constraints: list[dict] = []
    for item in split_top_level(body, ","):
        if not item:
            continue
        head = item.split(None, 1)[0].upper() if item.strip() else ""
        if head in TABLE_CONSTRAINT_KEYWORDS:
            name_match = re.match(r'^CONSTRAINT\s+"?(\w+)"?\s+(.*)$', item, re.S | re.I)
            if name_match:
                constraints.append({"name": name_match.group(1), "definition": name_match.group(2).strip()})
            else:
                first = item.split("(", 1)[0].strip()
                constraints.append({"name": None, "definition": item.strip(), "kind": first.upper()})
        else:
            col = parse_column_def(item)
            col["source"] = version
            columns.append(col)

    return qualify(schema, table), {
        "schema": schema,
        "table": table,
        "created_in": version,
        "columns": columns,
        "constraints": constraints,
        "indexes": [],
        "comment": None,
    }


def parse_alter_table(stmt: str, version: str) -> tuple[str, list[dict]]:
    m = re.match(r'^ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(ONLY\s+)?(.+?)\s+(.*)$', stmt, re.S | re.I)
    if not m:
        raise UnsupportedDDL(stmt)
    schema, table = parse_qualified(m.group(2))
    actions = split_top_level(m.group(3), ",")

    parsed: list[dict] = []
    for action in actions:
        a = action.strip()
        add = re.match(r'^ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(.*)$', a, re.S | re.I)
        if add:
            col = parse_column_def(add.group(1))
            col["source"] = version
            parsed.append({"action": "add_column", "column": col})
            continue
        drop = re.match(r'^DROP\s+COLUMN\s+(?:IF\s+EXISTS\s+)?"?(\w+)"?', a, re.S | re.I)
        if drop:
            parsed.append({"action": "drop_column", "column": drop.group(1)})
            continue
        alter = re.match(r'^ALTER\s+COLUMN\s+"?(\w+"?)\s+(.*)$', a, re.S | re.I)
        if alter:
            rest_upper = re.sub(r"\s+", " ", alter.group(2)).strip().upper()
            if rest_upper.startswith("SET DEFAULT"):
                parsed.append({"action": "set_default", "column": alter.group(1),
                               "value": alter.group(2).strip()[len("SET DEFAULT"):].strip()})
                continue
            if rest_upper.startswith("DROP DEFAULT"):
                parsed.append({"action": "drop_default", "column": alter.group(1)})
                continue
            if rest_upper.startswith("SET NOT NULL"):
                parsed.append({"action": "set_not_null", "column": alter.group(1)})
                continue
            if rest_upper.startswith("DROP NOT NULL"):
                parsed.append({"action": "drop_not_null", "column": alter.group(1)})
                continue
            parsed.append({"action": "alter_column_other", "column": alter.group(1),
                           "raw": alter.group(2).strip()})
            continue
        # 表级约束新增（ADD CONSTRAINT ...）——当前版本无此用法，显式报错而非静默忽略
        raise UnsupportedDDL(stmt)

    return qualify(schema, table), parsed


def parse_index(stmt: str, version: str) -> dict:
    m = re.match(
        r'^CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(.+?)\s+ON\s+(.+?)\s*\((.*?)\)\s*(WHERE\s+(.*))?$',
        stmt, re.S | re.I,
    )
    if not m:
        raise UnsupportedDDL(stmt)
    schema, table = parse_qualified(m.group(3))
    return {
        "name": unquote_ident(m.group(2)),
        "table": qualify(schema, table),
        "unique": bool(m.group(1)),
        "columns": unquote_ident(m.group(4)).strip(),
        "where": (m.group(6) or "").strip() or None,
        "created_in": version,
    }


def parse_comment(stmt: str, version: str) -> tuple[str, str, str | None, str]:
    m = re.match(r'^COMMENT\s+ON\s+COLUMN\s+(\S+)\s+IS\s+(.+)$', stmt, re.S | re.I)
    if m:
        target = m.group(1).strip()
        # schema.table.column —— 表名可能带引号
        cm = re.match(rf'^{SCHEMA_QUALIFIED}\."?(\w+)"?$', target)
        if not cm:
            raise UnsupportedDDL(stmt)
        schema = cm.group(1).lower() if cm.group(1) else None
        return "column", qualify(schema, cm.group(2)), cm.group(3), m.group(2)
    m = re.match(r'^COMMENT\s+ON\s+TABLE\s+(\S+)\s+IS\s+(.+)$', stmt, re.S | re.I)
    if m:
        schema, table = parse_qualified(m.group(1))
        return "table", qualify(schema, table), None, m.group(2)
    m = re.match(r'^COMMENT\s+ON\s+SCHEMA\s+"?(\w+)"?\s+IS\s+(.+)$', stmt, re.S | re.I)
    if m:
        return "schema", m.group(1), None, m.group(2)
    raise UnsupportedDDL(stmt)


def literal_value(raw: str) -> str:
    v = (raw or "").strip()
    if v.startswith("'") and v.endswith("'") and len(v) >= 2:
        v = v[1:-1].replace("''", "'")
    return v


def load_migrations(migration_dir: Path) -> tuple[dict, dict, list[str], str]:
    files = sorted(migration_dir.glob("V*.sql"), key=lambda p: int(re.match(r"V(\d+)", p.name).group(1)))
    if not files:
        raise SystemExit(f"未找到迁移文件: {migration_dir}")

    tables: dict[str, dict] = {}
    schemas: dict[str, dict] = {}
    unsupported: list[str] = []
    digest = hashlib.sha256()

    for path in files:
        version = path.name.split("__")[0]
        raw = path.read_text(encoding="utf-8")
        digest.update(path.name.encode())
        digest.update(raw.encode())
        sql = strip_line_comments(raw)

        for lineno, stmt in split_statements(sql):
            if not stmt:
                continue
            head = stmt.split(None, 1)[0].upper() if stmt.split() else ""
            if head in IGNORED_STATEMENT_PREFIXES:
                continue
            upper = stmt.upper()
            try:
                if upper.startswith("CREATE SCHEMA"):
                    name = re.match(r'^CREATE\s+SCHEMA\s+(?:IF\s+NOT\s+EXISTS\s+)?"?(\w+)"?', stmt, re.I).group(1)
                    schemas.setdefault(name, {"name": name, "comment": None, "created_in": version})
                elif re.match(r'^CREATE\s+TABLE', stmt, re.I):
                    key, table_def = parse_create_table(stmt, version)
                    if key in tables:
                        tables[key]["redefined_in"] = version
                    else:
                        tables[key] = table_def
                elif re.match(r'^ALTER\s+TABLE', stmt, re.I):
                    key, actions = parse_alter_table(stmt, version)
                    if key not in tables:
                        raise UnsupportedDDL(f"ALTER 目标表未定义: {key}")
                    for act in actions:
                        apply_alter(tables[key], act, version, path.name, lineno)
                elif re.match(r'^DROP\s+TABLE', stmt, re.I):
                    m = re.match(r'^DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(.+?)(?:\s+CASCADE)?$', stmt, re.S | re.I)
                    key = qualify(*parse_qualified(m.group(1)))
                    tables.pop(key, None)
                elif re.match(r'^CREATE\s+(UNIQUE\s+)?INDEX', stmt, re.I):
                    idx = parse_index(stmt, version)
                    target = tables.get(idx["table"])
                    if target is None:
                        raise UnsupportedDDL(f"索引目标表不存在: {idx['table']}")
                    target["indexes"].append(idx)
                elif re.match(r'^DROP\s+INDEX', stmt, re.I):
                    m = re.match(r'^DROP\s+INDEX\s+(?:IF\s+EXISTS\s+)?(\S+)', stmt, re.I)
                    name = unquote_ident(m.group(1))
                    for t in tables.values():
                        t["indexes"] = [x for x in t["indexes"] if x["name"] != name]
                elif upper.startswith("COMMENT ON"):
                    kind, target, column, text = parse_comment(stmt, version)
                    value = literal_value(text)
                    if kind == "table":
                        tables[target]["comment"] = value
                    elif kind == "column":
                        col = find_column(tables[target], column)
                        if col is None:
                            raise UnsupportedDDL(f"注释目标列不存在: {target}.{column}")
                        col["comment"] = value
                    else:
                        schemas.setdefault(target, {"name": target, "comment": None, "created_in": version})
                        schemas[target]["comment"] = value
                else:
                    raise UnsupportedDDL(stmt)
            except UnsupportedDDL as exc:
                snippet = " ".join(str(exc).split())[:160]
                unsupported.append(f"{path.name}:{lineno} {snippet}")

    if unsupported:
        print("发现无法识别的 DDL 语句，已中止（不产出可能错误的最终态）：", file=sys.stderr)
        for item in unsupported:
            print(f"  - {item}", file=sys.stderr)
        raise SystemExit(2)

    for table in tables.values():
        apply_primary_key_not_null(table)

    return tables, schemas, [p.name for p in files], digest.hexdigest()


def apply_primary_key_not_null(table: dict) -> None:
    """PostgreSQL 的 PRIMARY KEY 列隐含 NOT NULL（DDL 里不写）。

    不补这一步会把 id 之类的主键列标成「可空」，属误导性输出。
    补上时记录 nullable_implied_by，让读者知道「否」是 PG 推导的而非迁移明写。
    """
    pk_columns: set[str] = {c["name"] for c in table["columns"] if c["primary_key"]}
    for con in table["constraints"]:
        if con["definition"].upper().startswith("PRIMARY KEY"):
            inner = re.search(r"\((.*)\)", con["definition"], re.S)
            if inner:
                pk_columns.update(unquote_ident(x) for x in inner.group(1).split(","))
    for col in table["columns"]:
        if col["name"] in pk_columns and col["nullable"]:
            col["nullable"] = False
            col["nullable_implied_by"] = "PRIMARY KEY（PostgreSQL 隐式 NOT NULL）"


def find_column(table: dict | None, name: str) -> dict | None:
    if table is None:
        return None
    for col in table["columns"]:
        if col["name"] == name:
            return col
    return None


def apply_alter(table: dict, act: dict, version: str, filename: str, lineno: int) -> None:
    action = act["action"]
    if action == "add_column":
        col = act["column"]
        existing = find_column(table, col["name"])
        if existing is None:
            table["columns"].append(col)
        else:
            existing.update({k: v for k, v in col.items() if k != "altered_by"})
            existing["altered_by"].append({"version": version, "action": "add_column_again", "source": f"{filename}:{lineno}"})
        return
    if action == "drop_column":
        table["columns"] = [c for c in table["columns"] if c["name"] != act["column"]]
        return

    col = find_column(table, act["column"])
    if col is None:
        raise UnsupportedDDL(f"ALTER 目标列不存在: {table['table']}.{act['column']}")
    if action == "set_default":
        col["default"] = act["value"]
    elif action == "drop_default":
        col["default"] = None
    elif action == "set_not_null":
        col["nullable"] = False
    elif action == "drop_not_null":
        col["nullable"] = True
    else:
        raise UnsupportedDDL(f"未支持的列变更: {act.get('raw')}")
    col["altered_by"].append({"version": version, "action": action, "source": f"{filename}:{lineno}"})


# --------------------------------------------------------------------------- #
# 输出
# --------------------------------------------------------------------------- #

DOMAIN_ORDER = ["identity", "commerce"]


def render_markdown(model: dict) -> str:
    tables: dict = model["tables"]
    lines: list[str] = []
    add = lines.append

    add("# 数据字典（最终态）")
    add("")
    add("> **机制区文档（禁止手改）**。由 `api_testcases/scripts/build_db_dict.py` 从 Flyway 迁移")
    add("> 解析 V1→" + model["migration_range"] + " 全部 DDL 后的**最终态**生成。改内容 = 改迁移脚本，再重新生成。")
    add(">")
    add(f"> 生成命令：`python3 api_testcases/scripts/build_db_dict.py`  ")
    add(f"> 版本指纹：`{model['fingerprint']}`  ·  生成时间：{model['generated_at']}  ")
    add(f"> 迁移文件：{len(model['migrations'])} 个（{model['migrations'][0]} … {model['migrations'][-1]}）  ")
    add(f"> 迁移目录 sha256：`{model['migration_sha256']}`")
    add("")
    add("## 0. 怎么读这份字典")
    add("")
    add("| 项 | 说明 |")
    add("|---|---|")
    add("| 真值来源 | 迁移 SQL（唯一权威）> 实体类 > 设计态《数据模型.md》> PRD |")
    add("| `来源` 列 | 该列由哪个迁移创建；括号内为后续变更它的迁移 |")
    add("| 可空 | `否` = NOT NULL；`否 ᴾᴳ` = PostgreSQL 由 PRIMARY KEY 隐式推导（DDL 未明写）；`是` = 允许 NULL |")
    add("| 主键 | 单列 PK 直接标在该列；复合 PK 记在「约束」 |")
    add("| 外键 | **全库零 DB 外键约束**，所有关系均为逻辑引用，仅在 Java 服务层校验 |")
    add("| 软删 | 仅 `extends BaseEntity` 的实体对应表有 `deleted`；无 `deleted` 的表即物理删除语义 |")
    add("| 主键生成 | 所有 BIGINT 主键**无 sequence/默认值**，由应用侧雪花算法（MyBatis-Plus `ASSIGN_ID`）赋值；直连 DB 插入必须自行给 id |")
    add("")
    add("## 1. 注释覆盖率（语义完备度）")
    add("")
    add("迁移里的 `COMMENT ON` 是**唯一写在 DB 里的业务语义**。覆盖率低的表，其字段含义只能回源码读，")
    add("反推时应优先标注为「语义待补」。")
    add("")
    commented = {k: sum(1 for c in t["columns"] if c["comment"]) for k, t in tables.items()}
    total_cols = sum(len(t["columns"]) for t in tables.values())
    total_commented = sum(commented.values())
    add(f"- 列注释覆盖：**{total_commented}/{total_cols}**（{total_commented * 100 // total_cols}%）")
    add(f"- 表注释覆盖：**{sum(1 for t in tables.values() if t['comment'])}/{len(tables)}**")
    add("")
    add("| 表 | 列数 | 有注释 | 覆盖率 |")
    add("|---|---|---|---|")
    for key in sorted(tables, key=lambda k: (commented[k] / len(tables[k]["columns"]), k)):
        t = tables[key]
        n, c = len(t["columns"]), commented[key]
        add(f"| `{key}` | {n} | {c} | {c * 100 // n}% |")
    add("")
    add("## 2. 总览")
    add("")
    add(f"共 **{len(tables)} 张表**：")
    add("")
    add("| schema | 表数 | 说明 |")
    add("|---|---|---|")
    for schema_name in DOMAIN_ORDER:
        count = sum(1 for t in tables.values() if t["schema"] == schema_name)
        comment = model["schemas"].get(schema_name, {}).get("comment") or ""
        add(f"| `{schema_name}` | {count} | {comment} |")
    add("")
    add("| # | 表 | 说明 | 列数 | 主键 | 迁移 |")
    add("|---|---|---|---|---|---|")
    for i, (key, t) in enumerate(sorted(tables.items(), key=lambda kv: (DOMAIN_ORDER.index(kv[1]["schema"]) if kv[1]["schema"] in DOMAIN_ORDER else 9, kv[0])), 1):
        pk = primary_key_text(t)
        add(f"| {i} | `{key}` | {t['comment'] or '—'} | {len(t['columns'])} | {pk} | {t['created_in']} |")
    add("")

    add("## 3. 表清单")
    add("")
    for key, t in sorted(tables.items(), key=lambda kv: (DOMAIN_ORDER.index(kv[1]["schema"]) if kv[1]["schema"] in DOMAIN_ORDER else 9, kv[0])):
        add(f"### {key}")
        add("")
        add(f"- 说明：{t['comment'] or '（迁移未写表注释）'}")
        add(f"- 创建于：`{t['created_in']}`" + ("　·　后续增列：" + "、".join(sorted({c['source'] for c in t['columns'] if c['source'] != t['created_in']})) if any(c["source"] != t["created_in"] for c in t["columns"]) else ""))
        if t.get("redefined_in"):
            add(f"- ⚠️ 重复定义于：`{t['redefined_in']}`")
        add("")
        add("| 列 | 类型 | 可空 | 默认 | 主键 | 说明 | 来源 |")
        add("|---|---|---|---|---|---|---|")
        for col in t["columns"]:
            if col["nullable"]:
                nullable = "是"
            elif col.get("nullable_implied_by"):
                nullable = "否 ᴾᴳ"  # PG 由 PRIMARY KEY 推导，非迁移明写
            else:
                nullable = "否"
            default = f"`{col['default']}`" if col["default"] else "—"
            pk = "✔" if col["primary_key"] else ""
            altered = [a["version"] for a in col["altered_by"]]
            source = col["source"] + (f"（{ '、'.join(altered) }）" if altered else "")
            add(f"| `{col['name']}` | `{col['type']}` | {nullable} | {default} | {pk} | {col['comment'] or '—'} | {source} |")
        add("")
        hidden = [c for c in t["columns"] if c["checks"]]
        if hidden:
            add("列级 CHECK：")
            add("")
            for c in hidden:
                for chk in c["checks"]:
                    add(f"- `{c['name']}`: `{chk}`")
            add("")
        if t["constraints"]:
            add("表级约束：")
            add("")
            for con in t["constraints"]:
                name = f"`{con['name']}` " if con.get("name") else ""
                add(f"- {name}`{con['definition']}`")
            add("")
        if t["indexes"]:
            add("索引：")
            add("")
            add("| 名称 | 唯一 | 列 | 条件 | 来源 |")
            add("|---|---|---|---|---|")
            for idx in t["indexes"]:
                add(f"| `{idx['name']}` | {'✔' if idx['unique'] else ''} | `{idx['columns']}` | {('`' + idx['where'] + '`') if idx['where'] else '—'} | {idx['created_in']} |")
            add("")
        if not t["indexes"] and not t["constraints"] and not hidden:
            add("索引：无（仅主键）。")
            add("")

    add("## 4. 跨切面列适用范围")
    add("")
    add("`extends BaseEntity` 的实体由 MyBatis-Plus 自动填充，因此这些表必须有对应列；不带这些列的表，其实体**未**继承 `BaseEntity`。")
    add("")
    add("| 表 | created_at | updated_at | deleted | trace_id |")
    add("|---|---|---|---|---|")
    for key, t in sorted(tables.items()):
        names = {c["name"] for c in t["columns"]}
        cells = ["✔" if n in names else "—" for n in ("created_at", "updated_at", "deleted", "trace_id")]
        if all(c == "✔" for c in cells):
            continue
        add(f"| `{key}` | {' | '.join(cells)} |")
    add("")

    add("## 5. 逻辑引用关系（无 DB 外键）")
    add("")
    add("以下关系均由列命名与服务层代码维护，DB 不做校验；删除父行不会级联。")
    add("")
    add("| 源 | 列 | 目标 | 说明 |")
    add("|---|---|---|---|")
    for src, col_name, dst, note in LOGICAL_REFS:
        add(f"| `{src}` | `{col_name}` | `{dst}` | {note} |")
    add("")
    add("## 6. 无索引的常用查询列（性能风险清单）")
    add("")
    for item in NO_INDEX_NOTES:
        add(f"- {item}")
    add("")
    return "\n".join(lines) + "\n"


def primary_key_text(table: dict) -> str:
    single = [c["name"] for c in table["columns"] if c["primary_key"]]
    if single:
        return f"`{single[0]}`"
    for con in table["constraints"]:
        if con["definition"].upper().startswith("PRIMARY KEY"):
            return f"复合 `{con['definition']}`"
    return "—"


LOGICAL_REFS = [
    ("identity.dept", "parent_id", "identity.dept", "自引用树；root = 0"),
    ("identity.admin_user", "dept_id", "identity.dept", "所属部门"),
    ("identity.admin_role", "admin_user_id / role_id", "identity.admin_user / identity.role", "N-M 关联表"),
    ("identity.role_permission", "role_id / permission_id", "identity.role / identity.permission", "N-M 关联表"),
    ("identity.permission", "parent_id", "identity.permission", "自引用菜单树；root = 0"),
    ("identity.dict_item", "dict_id", "identity.dict", "字典项归属"),
    ("identity.file_object", "uploader_id", "identity.admin_user", "上传者（后台文件）"),
    ("identity.operation_log", "user_id", "identity.admin_user", "操作者快照，非强引用"),
    ("commerce.category", "parent_id", "commerce.category", "自引用类目树；root = 0"),
    ("commerce.brand", "category_id", "commerce.category", "品牌归属类目（V7 增列，可空）"),
    ("commerce.attribute", "category_id", "commerce.category", "类目下的规格/参数定义"),
    ("commerce.spu", "category_id / brand_id", "commerce.category / commerce.brand", "商品归属（均可空）"),
    ("commerce.sku", "spu_id", "commerce.spu", "SPU 1-N SKU"),
    ("commerce.stock", "sku_id / warehouse_id", "commerce.sku / commerce.warehouse", "唯一对 (sku, warehouse)"),
    ("commerce.stock_log", "sku_id / warehouse_id / operator_id", "commerce.sku / commerce.warehouse / identity.admin_user", "库存流水"),
    ("commerce.order", "customer_id", "identity.customer", "**跨 schema**；种子数据曾先于 customer 插入"),
    ("commerce.order", "idempotent_key", "—", "客户维度幂等键，唯一性见索引"),
    ("commerce.order_item", "order_id / sku_id / spu_id", "commerce.order / commerce.sku / commerce.spu", "下单时快照 title/pic/price/specs"),
    ("commerce.payment", "order_id", "commerce.order", "沙箱支付记录"),
    ("commerce.shipment", "order_id", "commerce.order", "发货记录与轨迹"),
    ("commerce.after_sale", "order_id / customer_id", "commerce.order / identity.customer", "售后单"),
    ("commerce.cart", "customer_id", "identity.customer", "一顾客一车（唯一索引保证）"),
    ("commerce.cart_item", "cart_id / sku_id", "commerce.cart / commerce.sku", "同车同 SKU 唯一"),
    ("commerce.address", "customer_id", "identity.customer", "收货地址"),
    ("commerce.favorite", "customer_id / spu_id", "identity.customer / commerce.spu", "收藏唯一对"),
    ("commerce.browse_history", "customer_id / spu_id", "identity.customer / commerce.spu", "足迹唯一对 + 最近时间索引"),
    ("commerce.review", "order_item_id / customer_id / spu_id", "commerce.order_item / identity.customer / commerce.spu", "一订单项一评（部分唯一索引）"),
    ("commerce.home_slot_item", "slot_id", "commerce.home_slot", "运营位内容项"),
    ("commerce.home_slot_item", "spu_id / link_value", "commerce.spu / commerce.category", "`link_type` 决定 `link_value` 的语义（多态，无约束）"),
    ("commerce.in_out_order", "warehouse_id / operator_id", "commerce.warehouse / identity.admin_user", "出入库单"),
    ("commerce.in_out_order", "related_order_no", "commerce.order", "以 order_no 字符串关联，非 id"),
    ("commerce.in_out_item", "bill_id / sku_id", "commerce.in_out_order / commerce.sku", "有符号数量：入正出负"),
    ("commerce.site_brand", "updated_by", "identity.admin_user", "单行配置 id=1"),
]

NO_INDEX_NOTES = [
    "`commerce.sku.spu_id`：SPU 详情读 SKU 走全表扫（`uk_stock_sku_wh(sku_id, warehouse_id)` 的前缀是 sku_id，不覆盖此查询）。",
    "`commerce.order_item.order_id`、`commerce.payment.order_id`、`commerce.shipment.order_id`、`commerce.after_sale.order_id`：订单详情/支付/物流/售后均按 order_id 查，无索引。",
    "`commerce.after_sale.customer_id`、`commerce.after_sale.status`：C 端「我的售后」按 customer_id + status 分页，无索引。",
    "`commerce.spu.category_id`、`commerce.spu.brand_id`：类目/品牌列表按此筛，无索引。",
    "`commerce.attribute.category_id`、`identity.dict_item.dict_id`、`commerce.in_out_order.warehouse_id`、`commerce.home_slot_item.spu_id`：外键列无索引。",
    "结论：当前数据量（演示级）下不构成问题；若上量，上表列是对账性能瓶颈的第一批候选。",
]


def build_model(migration_dir: Path, fingerprint: str, generated_at: str | None = None) -> dict:
    tables, schemas, migrations, sha = load_migrations(migration_dir)
    versions = [int(re.match(r"V(\d+)", m).group(1)) for m in migrations]
    return {
        "generated_at": generated_at or now_str(),
        "generator": "api_testcases/scripts/build_db_dict.py",
        "fingerprint": fingerprint,
        "migration_sha256": sha,
        "migration_range": f"V{max(versions)}",
        "migrations": migrations,
        "schemas": schemas,
        "tables": tables,
    }


def now_str() -> str:
    return __import__("datetime").datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def resolve_generated_at(candidate: dict, out_json: Path, explicit: str | None) -> str | None:
    """委托给 baseline_common.sticky_generated_at（避免同一规则多份实现）。"""
    sys.path.insert(0, str(ROOT / "scripts"))
    from baseline_common import sticky_generated_at  # noqa: E402
    prev = None
    if out_json.exists():
        try:
            prev = json.loads(out_json.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            prev = None
    return sticky_generated_at(prev, candidate, explicit)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--migration-dir", default=str(ROOT.parent / "backend/shang-server/src/main/resources/db/migration"))
    ap.add_argument("--out-md", default=str(ROOT.parent / "docs/06-baseline/01-数据字典.md"))
    ap.add_argument("--out-json", default=str(ROOT / "artifacts/schema-model.json"))
    ap.add_argument("--fingerprint", default="(unset)")
    ap.add_argument("--generated-at", default=None,
                    help="强制指定生成时间（默认：内容未变则沿用旧值，保证复算幂等）")
    args = ap.parse_args()

    migration_dir = Path(args.migration_dir)
    out_md = Path(args.out_md)
    out_json = Path(args.out_json)

    candidate = build_model(migration_dir, args.fingerprint)
    generated_at = resolve_generated_at(candidate, out_json, args.generated_at)
    model = build_model(migration_dir, args.fingerprint, generated_at)

    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text(render_markdown(model), encoding="utf-8")
    out_json.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total_cols = sum(len(t["columns"]) for t in model["tables"].values())
    print(json.dumps({
        "表": len(model["tables"]),
        "列": total_cols,
        "迁移": f"{model['migrations'][0]}..{model['migrations'][-1]}",
        "迁移目录sha256": model["migration_sha256"][:16] + "…",
        "生成时间": model["generated_at"],
        "输出md": str(out_md),
        "输出json": str(out_json),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
