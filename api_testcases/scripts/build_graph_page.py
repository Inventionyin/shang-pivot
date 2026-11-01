#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《调用链路图》交互页面（自包含单文件 HTML）。

设计取向（刻意不用可视化库）：
  - 布局是**确定性分层**（前端→端点→控制器→服务→跨上下文API→Mapper→表），不是力导向，
    所以手写 SVG 比引图库更可控、更可读；
  - 项目内没有任何可视化库（echarts/g6/d3 均未安装），引库需要联网安装 + 构建链，
    而本页面要求「双击可开、离线可用」；
  - 数据 209KB 可整体内联 → 单文件、无 CORS、无服务器依赖，与 `.stitch/原型/` 的静态 HTML 实践一致。

数据来源（全部是既有产物，不新增抽取逻辑）：
  - artifacts/call-graph.json     节点/边/端点链路（build_call_graph.py）
  - artifacts/endpoint-model.json 鉴权、响应键、失败码
  - artifacts/schema-model.json   表字段（点表节点时展示）
  - docs/测试资料/接口文档.md       每端点的「说明 + 请求体/参数 + 实现位置」文本

幂等：与其它机制区生成器一致——内容未变时沿用旧 generated_at，保证复算后 git diff 为空。

用法：
  python3 scripts/build_graph_page.py [--fingerprint <值>]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT / "scripts"))
from build_call_graph import domain_of  # noqa: E402  （复用同一套业务域判定，避免两套真相）

TEMPLATE = ROOT / "scripts/templates/call_graph_page.html"
DOC_MD = REPO / "docs/测试资料/接口文档.md"
DATA_RE = re.compile(r'<script id="data" type="application/json">(.*?)</script>', re.S)

LAYER_OF_KIND = {
    "fe": "fe", "endpoint": "endpoint", "controller": "controller", "service": "service",
    "cross_api": "cross_api", "mapper": "mapper", "table": "table", "module": "module", "job": "job",
}
FALLBACK_DOMAIN = "基础设施 / 模块（无业务域归属）"


def parse_api_doc() -> dict[tuple[str, str], dict]:
    """从《接口文档.md》的端点总表取每行的「说明 / 请求体·参数 / 实现位置」。

    复用既有生成器产物，而不是再写一份 @RequestParam 解析——避免第二套真相。
    """
    out: dict[tuple[str, str], dict] = {}
    if not DOC_MD.exists():
        return out
    for line in DOC_MD.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| ") or line.startswith("| 方法 |") or line.startswith("|---"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 7:
            continue
        method, path = cells[0], cells[1].strip("`")
        if method not in ("GET", "POST", "PUT", "DELETE", "PATCH") or not path.startswith("/"):
            continue
        out[(method, path)] = {
            "summary": cells[2],
            "params_text": cells[4],
            "codes_text": cells[5],
            "impl": cells[6].strip("`"),
        }
    return out


def build(fingerprint: str) -> dict:
    cg = json.loads((ROOT / "artifacts/call-graph.json").read_text(encoding="utf-8"))
    em = json.loads((ROOT / "artifacts/endpoint-model.json").read_text(encoding="utf-8"))
    sm = json.loads((ROOT / "artifacts/schema-model.json").read_text(encoding="utf-8"))
    doc = parse_api_doc()

    ep_model = {(e["method"], e["path"]): e for e in em["endpoints"]}
    edges = cg["edges"]

    # 节点：补 layer + domain + method
    # 键必须是**节点 id**（"ep:METHOD /path"），不是 endpoint 字符串——
    # 边的 from/to 用的是节点 id，键错了会让所有节点都落到兜底域。
    ep_domain = {f"ep:{e['endpoint']}": domain_of(e.get("path") or e["endpoint"])
                 for e in cg["endpoints"]}
    # 业务域归属：从每个端点出发做多跳传播，累加「可达端点的域」投票，取票数最高者。
    # 单跳投票不够：Mapper/表 与端点之间隔着服务这一跳，会全部落到兜底域。
    from collections import defaultdict, deque

    adj: dict[str, set[str]] = defaultdict(set)
    for e in edges:
        adj[e["from"]].add(e["to"])
        adj[e["to"]].add(e["from"])

    MAX_HOPS = 3
    domain_votes: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for ep_id, d in ep_domain.items():
        if ep_id not in {n["id"] for n in cg["nodes"]}:
            continue
        seen = {ep_id}
        q = deque([(ep_id, 0)])
        while q:
            cur, hops = q.popleft()
            if hops >= MAX_HOPS:
                continue
            for nxt in adj.get(cur, ()):  # 无向传播：上下游都算
                if nxt in seen:
                    continue
                seen.add(nxt)
                domain_votes[nxt][d] += MAX_HOPS - hops   # 越远权重越低
                q.append((nxt, hops + 1))

    nodes = []
    for n in cg["nodes"]:
        nid = n["id"]
        node = {
            "id": nid,
            "kind": n["kind"],
            "layer": LAYER_OF_KIND.get(n["kind"], "service"),
            "label": n["label"],
        }
        if n["kind"] == "endpoint":
            node["domain"] = ep_domain.get(nid, FALLBACK_DOMAIN)
            node["method"] = nid.split(" ", 1)[0]
        else:
            votes = dict(domain_votes.get(nid, {}))
            node["domain"] = max(sorted(votes), key=lambda k: votes[k]) if votes else FALLBACK_DOMAIN
        if n.get("file"):
            node["file"] = n["file"]
        nodes.append(node)

    # 端点详情：合并 call-graph 的链路 + endpoint-model 的契约 + 接口文档的文本
    endpoints = []
    for e in cg["endpoints"]:
        key = tuple(e["endpoint"].split(" ", 1))       # ("GET", "/admin/orders")
        eme = ep_model.get(key, {})
        dm = doc.get(key, {})
        endpoints.append({
            "endpoint": e["endpoint"],
            "http": key[0],
            "path": key[1],
            "summary": dm.get("summary") or eme.get("summary") or "",
            "auth": e.get("auth") or eme.get("auth") or "",
            "controller": e["controller"],
            "method": e["method"],
            "services": e["services"],
            "tables": e["tables"],
            "cross_api": e["cross_api"],
            "fe_callers": sorted(set(e["fe_callers"])),
            "detail": {
                "params_text": dm.get("params_text", ""),
                "impl": dm.get("impl", ""),
                "body_keys": eme.get("body_keys") or [],
                "body_required": eme.get("dto_required") or [],
                "resp_keys": eme.get("resp_keys") or [],
                "codes": eme.get("codes") or [],
            },
        })

    # 表字段（点表节点时展示）
    tables = {}
    for tkey, t in sm.get("tables", {}).items():
        tables[tkey] = {
            "comment": t.get("comment"),
            "columns": [{"name": c["name"], "type": c["type"], "nullable": c["nullable"]}
                        for c in t["columns"]],
        }

    return {
        "meta": {
            "fingerprint": fingerprint,
            "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "generator": "api_testcases/scripts/build_graph_page.py",
        },
        "nodes": nodes,
        "edges": edges,
        "endpoints": endpoints,
        "tables": tables,
    }


def previous_generated_at(out_html: Path) -> str | None:
    if not out_html.exists():
        return None
    m = DATA_RE.search(out_html.read_text(encoding="utf-8"))
    if not m:
        return None
    try:
        return json.loads(m.group(1)).get("meta", {}).get("generated_at")
    except json.JSONDecodeError:
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(REPO / "docs/06-baseline/05-调用链路图.html"))
    ap.add_argument("--fingerprint", default="(unset)")
    ap.add_argument("--generated-at", default=None)
    args = ap.parse_args()

    out = Path(args.out)
    data = build(args.fingerprint)

    # 内容未变则沿用旧时间戳（否则每次复算都因时间戳产生 diff，新鲜度门禁失效）
    if args.generated_at:
        data["meta"]["generated_at"] = args.generated_at
    else:
        prev = previous_generated_at(out)
        if prev:
            probe = json.loads(json.dumps(data))
            probe["meta"]["generated_at"] = prev
            cur_wo = {k: v for k, v in data.items() if k != "meta"}
            old_m = DATA_RE.search(out.read_text(encoding="utf-8")) if out.exists() else None
            if old_m:
                try:
                    old = json.loads(old_m.group(1))
                    if {k: v for k, v in old.items() if k != "meta"} == cur_wo:
                        data["meta"]["generated_at"] = prev
                except json.JSONDecodeError:
                    pass

    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    # 防止 JSON 里出现 </script> 提前闭合
    payload = payload.replace("</", "<\\/")

    tpl = TEMPLATE.read_text(encoding="utf-8")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(tpl.replace("/*__GRAPH_DATA__*/", payload), encoding="utf-8")

    print(json.dumps({
        "节点": len(data["nodes"]), "边": len(data["edges"]),
        "端点": len(data["endpoints"]), "表": len(data["tables"]),
        "业务域": sorted({n["domain"] for n in data["nodes"]}),
        "输出": str(out),
        "体积KB": round(out.stat().st_size / 1024),
        "生成时间": data["meta"]["generated_at"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
