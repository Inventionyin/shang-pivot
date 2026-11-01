#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跨版本基线 diff：比较两份 schema-model.json + endpoint-model.json，输出变更分级与受影响用例集合。

用途：版本升级时回答三个问题——
  1. 哪些是**破坏性**变更（必须出迁移/兼容方案并更新用例）？
  2. 哪些是**行为性**变更（必须更新业务规则文档与受影响用例）？
  3. 哪些是**增量**（补用例即可）？

设计约束：
  - 只吃 `build_db_dict.py` 与 `build_api_doc.py` 产出的 JSON，不吃手写文档（避免第二套真相）。
  - 判断是确定性的、可复算的；不做「影响面推理」这类需要判断的事。
  - 退出码：0 = 无破坏性变更；1 = 存在破坏性变更（可直接用作 CI 门禁）。

用法：
  python3 scripts/baseline_diff.py --old <旧-基线目录> --new <新-基线目录> [--out <报告.md>] [--json]
  基线目录内需含 schema-model.json 与 endpoint-model.json（缺失的维度自动跳过）。

示例（同一版本自比，应输出无变更）：
  python3 scripts/baseline_diff.py --old artifacts --new artifacts
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

BREAKING, BEHAVIORAL, ADDITIVE, INFO = "BREAKING", "BEHAVIORAL", "ADDITIVE", "INFO"
ORDER = {BREAKING: 0, BEHAVIORAL: 1, ADDITIVE: 2, INFO: 3}


def load(path: Path) -> dict | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- #
# schema 维度
# --------------------------------------------------------------------------- #

def diff_schema(old: dict, new: dict) -> list[dict]:
    changes: list[dict] = []
    old_t, new_t = old.get("tables", {}), new.get("tables", {})

    for name in sorted(set(old_t) - set(new_t)):
        changes.append(rule(BREAKING, "schema", f"表被删除：{name}",
                            "所有读写该表的接口与用例失效", name))
    for name in sorted(set(new_t) - set(old_t)):
        changes.append(rule(ADDITIVE, "schema", f"新增表：{name}",
                            "补该表的用例与数据字典章节", name))

    for name in sorted(set(old_t) & set(new_t)):
        ot, nt = old_t[name], new_t[name]
        changes += diff_columns(name, ot, nt)
        changes += diff_indexes(name, ot, nt)
        changes += diff_constraints(name, ot, nt)
        if ot.get("comment") != nt.get("comment"):
            changes.append(rule(INFO, "schema", f"{name}：表注释变更",
                                f"{ot.get('comment')!r} → {nt.get('comment')!r}", name))
    return changes


def diff_columns(table: str, ot: dict, nt: dict) -> list[dict]:
    changes: list[dict] = []
    oc = {c["name"]: c for c in ot["columns"]}
    nc = {c["name"]: c for c in nt["columns"]}

    for col in sorted(set(oc) - set(nc)):
        changes.append(rule(BREAKING, "schema", f"{table}.{col}：列被删除",
                            "读写该列的接口/导出/映射失效", table, col))
    for col in sorted(set(nc) - set(oc)):
        c = nc[col]
        if not c["nullable"] and not c["default"]:
            changes.append(rule(BREAKING, "schema", f"{table}.{col}：新增 NOT NULL 且无默认值",
                                "已有数据无法满足；需先给默认值或回填", table, col))
        else:
            changes.append(rule(ADDITIVE, "schema", f"{table}.{col}：新增列",
                                f"类型 {c['type']}，可空={c['nullable']}，默认={c['default']!r}", table, col))

    for col in sorted(set(oc) & set(nc)):
        a, b = oc[col], nc[col]
        if a["type"] != b["type"]:
            changes.append(rule(BREAKING, "schema", f"{table}.{col}：类型变更",
                                f"{a['type']} → {b['type']}", table, col))
        if a["nullable"] and not b["nullable"]:
            level = BEHAVIORAL if b["default"] else BREAKING
            changes.append(rule(level, "schema", f"{table}.{col}：变为 NOT NULL",
                                f"默认值={b['default']!r}；历史 NULL 行会被拒", table, col))
        elif not a["nullable"] and b["nullable"]:
            changes.append(rule(BEHAVIORAL, "schema", f"{table}.{col}：放宽为可空",
                                "读路径可能收到 NULL", table, col))
        if a["default"] != b["default"]:
            changes.append(rule(BEHAVIORAL, "schema", f"{table}.{col}：默认值变更",
                                f"{a['default']!r} → {b['default']!r}", table, col))
        if a.get("comment") != b.get("comment"):
            changes.append(rule(INFO, "schema", f"{table}.{col}：列注释变更",
                                f"{a.get('comment')!r} → {b.get('comment')!r}", table, col))
    return changes


def diff_indexes(table: str, ot: dict, nt: dict) -> list[dict]:
    changes: list[dict] = []
    oi = {i["name"]: i for i in ot["indexes"]}
    ni = {i["name"]: i for i in nt["indexes"]}

    for name in sorted(set(oi) - set(ni)):
        level = BREAKING if oi[name]["unique"] else BEHAVIORAL
        changes.append(rule(level, "schema", f"{table}：索引被删除 {name}",
                            "唯一索引消失 = DB 层不变量丢失（可能产生重复数据）" if oi[name]["unique"]
                            else "查询可能退化为全表扫", table))
    for name in sorted(set(ni) - set(oi)):
        i = ni[name]
        level = BEHAVIORAL if i["unique"] else ADDITIVE
        changes.append(rule(level, "schema", f"{table}：新增{'唯一' if i['unique'] else ''}索引 {name}",
                            "原先合法的重复数据会被拒" if i["unique"] else f"列 {i['columns']}", table))
    for name in sorted(set(oi) & set(ni)):
        a, b = oi[name], ni[name]
        if a["columns"] != b["columns"]:
            changes.append(rule(BREAKING, "schema", f"{table}：索引列变更 {name}",
                                f"{a['columns']} → {b['columns']}", table))
        if a["where"] != b["where"]:
            changes.append(rule(BEHAVIORAL, "schema", f"{table}：索引条件变更 {name}",
                                f"{a['where']!r} → {b['where']!r}", table))
        if a["unique"] != b["unique"]:
            changes.append(rule(BREAKING, "schema", f"{table}：索引唯一性变更 {name}",
                                f"unique {a['unique']} → {b['unique']}", table))
    return changes


def diff_constraints(table: str, ot: dict, nt: dict) -> list[dict]:
    changes: list[dict] = []
    fmt = lambda cs: {c["definition"].upper().replace(" ", ""): c.get("name") for c in cs}
    oc, nc = fmt(ot["constraints"]), fmt(nt["constraints"])
    for key in sorted(set(oc) - set(nc)):
        changes.append(rule(BREAKING, "schema", f"{table}：约束被删除 {oc[key] or key}",
                            "DB 层不变量丢失", table))
    for key in sorted(set(nc) - set(oc)):
        changes.append(rule(BEHAVIORAL, "schema", f"{table}：新增约束 {nc[key] or key}",
                            "原先合法的数据可能被拒", table))
    return changes


# --------------------------------------------------------------------------- #
# endpoint 维度
# --------------------------------------------------------------------------- #

def ep_key(e: dict) -> str:
    return f"{e['method']} {e['path']}"


def case_prefix(e: dict) -> str:
    """复用既有稳定 ID 体系：METHOD_PATH（路径分隔符与花括号转下划线、去大写化不变）。"""
    seg = re_sub_path(e["path"])
    return f"{e['method']}{seg}"


def re_sub_path(path: str) -> str:
    import re
    s = re.sub(r"[{}]", "", path)
    s = re.sub(r"[/\-]+", "_", s)
    return s.upper()


def diff_endpoints(old: dict, new: dict) -> list[dict]:
    changes: list[dict] = []
    oe = {ep_key(e): e for e in old.get("endpoints", [])}
    ne = {ep_key(e): e for e in new.get("endpoints", [])}

    for k in sorted(set(oe) - set(ne)):
        changes.append(rule(BREAKING, "api", f"端点被删除：{k}",
                            f"原摘要「{oe[k].get('summary','')}」；调用方与用例全部失效",
                            None, None, case_prefix(oe[k])))
    for k in sorted(set(ne) - set(oe)):
        changes.append(rule(ADDITIVE, "api", f"新增端点：{k}",
                            f"摘要「{ne[k].get('summary','')}」", None, None, case_prefix(ne[k])))

    for k in sorted(set(oe) & set(ne)):
        a, b = oe[k], ne[k]
        pref = case_prefix(a)
        if a.get("auth") != b.get("auth"):
            changes.append(rule(BREAKING, "api", f"{k}：鉴权要求变更",
                                f"{a.get('auth')!r} → {b.get('auth')!r}", None, None, pref))
        old_req, new_req = set(a.get("dto_required") or []), set(b.get("dto_required") or [])
        if new_req - old_req:
            changes.append(rule(BREAKING, "api", f"{k}：新增必填字段",
                                f"{sorted(new_req - old_req)}", None, None, pref))
        elif old_req - new_req:
            changes.append(rule(BEHAVIORAL, "api", f"{k}：必填字段放宽",
                                f"{sorted(old_req - new_req)}", None, None, pref))
        old_bk, new_bk = set(a.get("body_keys") or []), set(b.get("body_keys") or [])
        if new_bk - old_bk:
            changes.append(rule(ADDITIVE, "api", f"{k}：请求体新增字段",
                                f"{sorted(new_bk - old_bk)}", None, None, pref))
        elif old_bk - new_bk:
            changes.append(rule(BREAKING, "api", f"{k}：请求体字段被删除",
                                f"{sorted(old_bk - new_bk)}", None, None, pref))
        old_rk, new_rk = set(a.get("resp_keys") or []), set(b.get("resp_keys") or [])
        if old_rk - new_rk:
            changes.append(rule(BREAKING, "api", f"{k}：响应字段被删除",
                                f"{sorted(old_rk - new_rk)}", None, None, pref))
        elif new_rk - old_rk:
            changes.append(rule(ADDITIVE, "api", f"{k}：响应新增字段",
                                f"{sorted(new_rk - old_rk)}", None, None, pref))
        old_c, new_c = set(a.get("codes") or []), set(b.get("codes") or [])
        if new_c - old_c:
            changes.append(rule(BEHAVIORAL, "api", f"{k}：新增失败码",
                                f"{sorted(new_c - old_c)}", None, None, pref))
        elif old_c - new_c:
            changes.append(rule(BEHAVIORAL, "api", f"{k}：失败码消失",
                                f"{sorted(old_c - new_c)}（可能是错误码重命名或分支被删）", None, None, pref))
        if a.get("summary") != b.get("summary"):
            changes.append(rule(INFO, "api", f"{k}：摘要变更",
                                f"{a.get('summary')!r} → {b.get('summary')!r}", None, None, pref))
    return changes


# --------------------------------------------------------------------------- #
# field-map 维度（DB 列 ↔ 实体字段 ↔ 接口 JSON 的映射）
# --------------------------------------------------------------------------- #

def diff_field_map(old: dict, new: dict) -> list[dict]:
    """比对两版字段映射。JSON 字段名或类型变了 = 断言与客户端会断。"""
    changes: list[dict] = []
    oe, ne = old.get("entities", {}), new.get("entities", {})

    def rows(entities: dict, cls: str) -> dict:
        e = entities.get(cls)
        if not e:
            return {}
        return {(r["column"], r["json_field"]): r for r in e["rows"]}

    for cls in sorted(set(oe) - set(ne)):
        changes.append(rule(BREAKING, "field-map", f"实体消失：{cls}", "对应端点契约随之变化", cls))
    for cls in sorted(set(ne) - set(oe)):
        changes.append(rule(ADDITIVE, "field-map", f"新增实体：{cls}", "补该实体的映射用例", cls))

    for cls in sorted(set(oe) & set(ne)):
        o, n = rows(oe, cls), rows(ne, cls)
        # 用 json_field 对齐（字段改名会表现为旧的消失 + 新的出现）
        o_by_json = {k[1]: v for k, v in o.items()}
        n_by_json = {k[1]: v for k, v in n.items()}
        for jf in sorted(set(o_by_json) - set(n_by_json)):
            changes.append(rule(BREAKING, "field-map", f"{cls}.{jf}：JSON 字段消失",
                                f"原列 {o_by_json[jf]['column']}（{o_by_json[jf]['json_type']}）", cls))
        for jf in sorted(set(n_by_json) - set(o_by_json)):
            changes.append(rule(ADDITIVE, "field-map", f"{cls}.{jf}：新增 JSON 字段",
                                f"列 {n_by_json[jf]['column']}（{n_by_json[jf]['json_type']}）", cls))
        for jf in sorted(set(o_by_json) & set(n_by_json)):
            a, b = o_by_json[jf], n_by_json[jf]
            if a["json_type"] != b["json_type"]:
                changes.append(rule(BREAKING, "field-map", f"{cls}.{jf}：JSON 类型变更",
                                    f"{a['json_type']} → {b['json_type']}（断言与客户端会断）", cls))
            if a["column"] != b["column"]:
                changes.append(rule(BEHAVIORAL, "field-map", f"{cls}.{jf}：来源列变更",
                                    f"{a['column']} → {b['column']}", cls))
            if (a["db_type"] != b["db_type"]) or (a["java_type"] != b["java_type"]):
                changes.append(rule(BEHAVIORAL, "field-map", f"{cls}.{jf}：底层类型变更",
                                    f"{a['db_type']}/{a['java_type']} → {b['db_type']}/{b['java_type']}", cls))
        for w in (new.get("warnings") or []):
            if w.get("class") == cls:
                changes.append(rule(BREAKING, "field-map", f"{cls}：新增一致性偏差",
                                    f"[{w['kind']}] {w['detail']}", cls))
    return changes


def rule(level: str, dim: str, title: str, detail: str,
         table: str | None = None, column: str | None = None,
         case_prefix_: str | None = None) -> dict:
    return {"level": level, "dim": dim, "title": title, "detail": detail,
            "table": table, "column": column, "case_prefix": case_prefix_}


# --------------------------------------------------------------------------- #
# 报告
# --------------------------------------------------------------------------- #

def render(changes: list[dict], meta: dict) -> str:
    lines = ["# 基线 diff 报告", ""]
    lines.append(f"- 旧基线：`{meta['old_dir']}`"
                 + (f"（schema 指纹 `{meta.get('old_schema_sha') or '—'}`，"
                    f"迁移范围 `{meta.get('old_migration_range') or '—'}`）" if meta.get("old_schema_sha") or meta.get("old_migration_range") else ""))
    lines.append(f"- 新基线：`{meta['new_dir']}`"
                 + (f"（schema 指纹 `{meta.get('new_schema_sha') or '—'}`，"
                    f"迁移范围 `{meta.get('new_migration_range') or '—'}`）" if meta.get("new_schema_sha") or meta.get("new_migration_range") else ""))
    lines.append("")
    counts = {lvl: sum(1 for c in changes if c["level"] == lvl) for lvl in ORDER}
    lines.append("## 结论")
    lines.append("")
    if not changes:
        lines.append("**两个基线完全一致**：无端点、字段、索引、约束层面的差异。")
        lines.append("")
        return "\n".join(lines) + "\n"
    lines.append("| 级别 | 数量 | 门禁动作 |")
    lines.append("|---|---|---|")
    lines.append(f"| 🔴 破坏性 BREAKING | {counts[BREAKING]} | 必须出迁移/兼容方案，并更新受影响用例 |")
    lines.append(f"| 🟡 行为性 BEHAVIORAL | {counts[BEHAVIORAL]} | 必须更新业务规则文档与受影响用例 |")
    lines.append(f"| 🟢 增量 ADDITIVE | {counts[ADDITIVE]} | 补用例即可 |")
    lines.append(f"| ⚪ 信息 INFO | {counts[INFO]} | 无需动作（注释/摘要变更） |")
    lines.append("")
    lines.append(f"**判定：{'🔴 存在破坏性变更，版本升级需人工确认' if counts[BREAKING] else '✅ 无破坏性变更'}**")
    lines.append("")

    for level in ORDER:
        group = [c for c in changes if c["level"] == level]
        if not group:
            continue
        lines.append(f"## {level}（{len(group)}）")
        lines.append("")
        lines.append("| 维度 | 变更 | 说明 | 对象 |")
        lines.append("|---|---|---|---|")
        for c in group:
            target = c["table"] or c["case_prefix"] or "—"
            if c.get("column"):
                target = f"{c['table']}.{c['column']}"
            lines.append(f"| {c['dim']} | {c['title']} | {c['detail']} | `{target}` |")
        lines.append("")

    prefixes = sorted({c["case_prefix"] for c in changes if c.get("case_prefix")})
    if prefixes:
        lines.append("## 需要复核的用例集合")
        lines.append("")
        lines.append("按既有稳定 ID 前缀筛选（`-k` 可直接用）：")
        lines.append("")
        lines.append("```bash")
        expr = " or ".join(prefixes)
        lines.append(f"cd api_testcases && python3 -m pytest tests/contract -k '{expr}'")
        lines.append("```")
        lines.append("")
        lines.append("受影响前缀：" + "、".join(f"`{p}`" for p in prefixes))
        lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", required=True, help="旧基线目录（含 schema-model.json / endpoint-model.json）")
    ap.add_argument("--new", required=True, help="新基线目录")
    ap.add_argument("--out", default=None, help="报告输出路径；默认打印到 stdout")
    ap.add_argument("--json", action="store_true", help="改为输出 JSON")
    args = ap.parse_args()

    old_dir, new_dir = Path(args.old), Path(args.new)
    old_schema, new_schema = load(old_dir / "schema-model.json"), load(new_dir / "schema-model.json")
    old_ep, new_ep = load(old_dir / "endpoint-model.json"), load(new_dir / "endpoint-model.json")
    old_fm, new_fm = load(old_dir / "field-map.json"), load(new_dir / "field-map.json")

    have_any = lambda *ms: any(m is not None for m in ms)
    if not have_any(old_schema, old_ep, old_fm):
        print(f"旧基线目录无可比对文件（需 schema-model.json / endpoint-model.json / field-map.json 之一）: {old_dir}",
              file=sys.stderr)
        return 2
    if not have_any(new_schema, new_ep, new_fm):
        print(f"新基线目录无可比对文件（需三者之一）: {new_dir}", file=sys.stderr)
        return 2

    changes: list[dict] = []
    if old_schema and new_schema:
        changes += diff_schema(old_schema, new_schema)
    if old_ep and new_ep:
        changes += diff_endpoints(old_ep, new_ep)
    if old_fm and new_fm:
        changes += diff_field_map(old_fm, new_fm)

    changes.sort(key=lambda c: (ORDER[c["level"]], c["dim"], c["title"]))
    meta = {
        "old_dir": str(old_dir), "new_dir": str(new_dir),
        "old_schema_sha": (old_schema or {}).get("migration_sha256", "")[:16],
        "new_schema_sha": (new_schema or {}).get("migration_sha256", "")[:16],
        "old_migration_range": (old_schema or {}).get("migration_range"),
        "new_migration_range": (new_schema or {}).get("migration_range"),
    }
    breaking = sum(1 for c in changes if c["level"] == BREAKING)

    if args.json:
        payload = {"meta": meta, "counts": {lvl: sum(1 for c in changes if c["level"] == lvl) for lvl in ORDER},
                   "changes": changes}
        text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    else:
        text = render(changes, meta)

    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"报告已写出 {args.out}（破坏性 {breaking} 项）")
    else:
        print(text, end="")

    if not args.json:
        print(f"[summary] BREAKING={breaking} BEHAVIORAL={sum(1 for c in changes if c['level']==BEHAVIORAL)} "
              f"ADDITIVE={sum(1 for c in changes if c['level']==ADDITIVE)}", file=sys.stderr)
    return 1 if breaking else 0


if __name__ == "__main__":
    raise SystemExit(main())
