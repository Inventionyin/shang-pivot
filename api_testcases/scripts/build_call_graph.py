#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成《调用链路》文档 + 机读 call-graph.json。

为什么是本项目专属的抽取方式（而不是通用 API 图工具）：
  本项目是**模块化单体**——没有 Feign/Dubbo/RestTemplate/WebClient，因此
  「接口调用接口」这个关系类型在代码里**不存在**。真实链路是进程内的：
      前端 api 函数 → 端点 → 控制器方法 → 应用服务 → Mapper → 表
                                          └→ 跨上下文 *Api（同进程接口）
  另外还有两类跨端点关系：鉴权前置（先登录取 token）与资源嵌套（路径前缀）。

抽取来源（全部来自源码/既有产物，不猜）：
  - 控制器/服务/定时任务：解析 Java 源码（Lombok @RequiredArgsConstructor + private final 注入，模式统一）
  - 端点与方法映射：@GetMapping/@PostMapping/... + 紧随的方法签名
  - Mapper → 表：Mapper 名去掉 Mapper 后缀 = 实体名，经 field-map.json 的 entities[].table 落地
  - 鉴权要求：artifacts/endpoint-model.json 的 auth 字段
  - 前端调用：两个前端 src/api/*.ts 里的 `http({ url, method })`

输出：
  - docs/06-baseline/05-调用链路.md（机制区）
  - api_testcases/artifacts/call-graph.json（供跨版本追踪与 diff）

用法：
  python3 scripts/build_call_graph.py [--fingerprint <值>]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from baseline_common import sticky_generated_at  # noqa: E402
REPO = ROOT.parent
BACKEND = REPO / "backend"

HTTP_METHODS = {"Get": "GET", "Post": "POST", "Put": "PUT", "Delete": "DELETE", "Patch": "PATCH"}
LAYER_SUFFIX = (("Service", "service"), ("Mapper", "mapper"), ("Api", "cross_api"),
                ("ApiImpl", "service"), ("Repository", "mapper"))


def classify(type_name: str) -> str:
    for suffix, kind in LAYER_SUFFIX:
        if type_name.endswith(suffix):
            return kind
    return "other"


# --------------------------------------------------------------------------- #
# Java 解析
# --------------------------------------------------------------------------- #

INJECT_RE = re.compile(r"^\s*private\s+final\s+([\w<>,.\s]+?)\s+(\w+)\s*;")
CLASS_RE = re.compile(r"^\s*public\s+(?:abstract\s+)?class\s+(\w+)")
PREFIX_RE = re.compile(r'^\s*@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
METHOD_DECL_RE = re.compile(r"^\s*(?:public|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>,\[\]\.\? ]+\s+(\w+)\s*\(")
MAPPING_LINE_RE = re.compile(r'^\s*@(Get|Post|Put|Delete|Patch)Mapping\b')
ANNO_LINE_RE = re.compile(r'^\s*@\w+')


def mapping_paths(line: str) -> list[str]:
    """从映射注解行取路径列表，支持三种写法：
        @GetMapping                            → [""]（仅类前缀）
        @GetMapping("/{id}")                   → ["/{id}"]
        @PostMapping({"/api/auth/login", "/api/auth/phone"})  → 两条

    只保留以 / 开头的字面量，避免把 produces= 之类的字符串当路径。
    """
    vals = [v for v in re.findall(r'"([^"]*)"', line) if v.startswith("/")]
    return vals or [""]
CALL_RE = re.compile(r"\b(\w+)\.(\w+)\s*\(")


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def extract_methods(lines: list[str]) -> list[dict]:
    """抽出方法：映射注解 + 方法名 + 方法体内对注入字段的调用。

    关键点：注解可能跨多行（如 @Operation(summary=..., description=...)），
    其续行不以 @ 开头。若把续行当成普通代码行，会清空刚记下的映射注解，
    导致端点匹配不上控制器方法（本项目实测会影响 3 个端点）。
    因此用「括号深度」跟踪注解块：深度 > 0 时一律视为注解续行。
    """
    methods: list[dict] = []
    pending: list[tuple[str, str]] = []
    anno_depth = 0
    idx, n = 0, len(lines)
    while idx < n:
        line = lines[idx]
        stripped = line.strip()

        if anno_depth > 0:                      # 注解续行
            anno_depth += line.count("(") - line.count(")")
            idx += 1
            continue
        mm = MAPPING_LINE_RE.match(line)
        if mm:
            pending += [(HTTP_METHODS[mm.group(1)], p) for p in mapping_paths(line)]
            anno_depth = max(line.count("(") - line.count(")"), 0)
            idx += 1
            continue
        if ANNO_LINE_RE.match(line):            # 其它注解（@Operation/@Parameter…）
            anno_depth = max(line.count("(") - line.count(")"), 0)
            idx += 1
            continue

        dm = METHOD_DECL_RE.match(line)
        if dm and pending:
            depth, body, j, started = 0, [], idx, False
            while j < n:
                cur = lines[j]
                depth += cur.count("{") - cur.count("}")
                if "{" in cur:
                    started = True
                body.append(cur)
                if started and depth <= 0:
                    break
                j += 1
            body_text = "\n".join(body)
            calls = sorted({f"{a}.{b}" for a, b in CALL_RE.findall(body_text)
                            if not a.startswith(("if", "for", "while", "return", "new", "String", "Math", "log"))})
            methods.append({"name": dm.group(1), "mappings": pending, "calls": calls,
                            "line": idx + 1, "body_lines": len(body)})
            pending = []
            idx = j + 1
            continue

        if stripped and not stripped.startswith(("//", "*", "/*", "}")):
            pending = []
        idx += 1
    return methods


def parse_java(path: Path) -> dict:
    lines = read_lines(path)
    cls, prefix = None, ""
    injected: list[dict] = []
    for line in lines:
        if cls is None:
            cm = CLASS_RE.match(line)
            if cm:
                cls = cm.group(1)
        if not prefix:
            pm = PREFIX_RE.match(line)
            if pm:
                prefix = pm.group(1)
        im = INJECT_RE.match(line)
        if im:
            t = re.sub(r"\s+", "", im.group(1))
            injected.append({"type": t, "name": im.group(2), "kind": classify(t)})
    return {
        "class": cls,
        "prefix": prefix,
        "file": str(path.relative_to(REPO)),
        "injected": injected,
        "methods": extract_methods(lines),
    }


# --------------------------------------------------------------------------- #
# 前端解析
# --------------------------------------------------------------------------- #

# 前端写法有两种：http({...}) 与 http<泛型>({...})；泛型里可能含 > （如 Array<Record<..>>），
# 所以不用「匹配整个调用」的正则，改为定位 url: 再向前回看 http、向前看 method。
URL_RE = re.compile(r"""url:\s*[`'"]([^`'"]+)[`'"]""")
METHOD_RE = re.compile(r"""method:\s*['"](\w+)['"]""")


def parse_frontend() -> list[dict]:
    out: list[dict] = []
    for app in ("shang-admin-web", "shang-mall"):
        api_dir = REPO / app / "src" / "api"
        if not api_dir.is_dir():
            continue
        for p in sorted(api_dir.glob("*.ts")):
            text = p.read_text(encoding="utf-8")
            for um in URL_RE.finditer(text):
                back = text.rfind("http", max(0, um.start() - 400), um.start())
                if back == -1:
                    continue  # url: 不在 http(...) 调用里
                nxt = text.find("url:", um.end())
                window = text[um.end(): nxt if nxt != -1 else um.end() + 300]
                mm = METHOD_RE.search(window)
                url = um.group(1)
                out.append({
                    "app": app, "file": str(p.relative_to(REPO)),
                    "url": url,
                    "normalized": re.sub(r"\$\{[^}]+\}", "{p}", url),
                    "method": (mm.group(1).upper() if mm else "GET"),
                })
    return out


def norm_ep_path(path: str) -> str:
    return re.sub(r"\{[^}]+\}", "{p}", path)


# --------------------------------------------------------------------------- #
# 构图
# --------------------------------------------------------------------------- #

def build(fingerprint: str, generated_at: str | None = None) -> dict:
    endpoint_model = json.loads((ROOT / "artifacts/endpoint-model.json").read_text(encoding="utf-8"))
    field_map = json.loads((ROOT / "artifacts/field-map.json").read_text(encoding="utf-8"))
    class_to_table = {c: e["table"] for c, e in field_map["entities"].items()}

    controllers, services, jobs = {}, {}, {}
    for p in sorted(BACKEND.glob("*/*/main/java/**/controller/*.java")):
        j = parse_java(p)
        if j["class"]:
            controllers[j["class"]] = j
    for p in sorted(BACKEND.glob("*/*/main/java/**/application/*.java")):
        j = parse_java(p)
        if j["class"]:
            services[j["class"]] = j
    for p in sorted(BACKEND.glob("*/*/main/java/**/job/*.java")):
        j = parse_java(p)
        if j["class"]:
            jobs[j["class"]] = j

    mapper_files = {q.stem: str(q.relative_to(REPO))
                    for q in BACKEND.glob("*/*/main/java/**/mapper/*.java")}

    nodes: dict[str, dict] = {}
    edges: list[dict] = []

    def node(nid: str, kind: str, label: str, file: str | None = None) -> str:
        n = nodes.setdefault(nid, {"id": nid, "kind": kind, "label": label})
        if file and not n.get("file"):
            n["file"] = file
        return nid

    def edge(src: str, dst: str, kind: str, detail: str = "") -> None:
        edges.append({"from": src, "to": dst, "kind": kind, "detail": detail})

    # 模块
    for m in ("shang-server", "shang-common", "shang-identity-api", "shang-identity",
              "shang-commerce-api", "shang-commerce", "shang-customerservice"):
        node(f"module:{m}", "module", m)
    for a, b in (("shang-server", "shang-identity"), ("shang-server", "shang-commerce"),
                 ("shang-server", "shang-customerservice"),
                 ("shang-commerce", "shang-identity-api"), ("shang-commerce", "shang-common"),
                 ("shang-commerce", "shang-commerce-api"),
                 ("shang-identity", "shang-common"), ("shang-identity", "shang-identity-api"),
                 ("shang-identity", "shang-commerce-api")):
        edge(f"module:{a}", f"module:{b}", "module_depends")

    # 控制器 / 服务 / Mapper / 跨上下文 Api
    for cls, j in controllers.items():
        node(f"ctrl:{cls}", "controller", cls, j["file"])
        for inj in j["injected"]:
            t, k = inj["type"], inj["kind"]
            if k == "service":
                node(f"svc:{t}", "service", t); edge(f"ctrl:{cls}", f"svc:{t}", "injects")
            elif k == "mapper":
                node(f"mapper:{t}", "mapper", t, mapper_files.get(t)); edge(f"ctrl:{cls}", f"mapper:{t}", "injects")
            elif k == "cross_api":
                node(f"api:{t}", "cross_api", t); edge(f"ctrl:{cls}", f"api:{t}", "injects")
    for cls, j in services.items():
        node(f"svc:{cls}", "service", cls, j["file"])
        for inj in j["injected"]:
            t, k = inj["type"], inj["kind"]
            if k == "service":
                node(f"svc:{t}", "service", t); edge(f"svc:{cls}", f"svc:{t}", "injects")
            elif k == "mapper":
                node(f"mapper:{t}", "mapper", t, mapper_files.get(t)); edge(f"svc:{cls}", f"mapper:{t}", "injects")
            elif k == "cross_api":
                node(f"api:{t}", "cross_api", t); edge(f"svc:{cls}", f"api:{t}", "cross_api")
    for cls, j in jobs.items():
        node(f"job:{cls}", "job", cls, j["file"])
        for inj in j["injected"]:
            if inj["kind"] == "service":
                node(f"svc:{inj['type']}", "service", inj["type"]); edge(f"job:{cls}", f"svc:{inj['type']}", "injects")

    # Mapper → 表
    for nid in [k for k in list(nodes) if k.startswith("mapper:")]:
        mapper_cls = nid.split(":", 1)[1]
        entity = mapper_cls[:-len("Mapper")] if mapper_cls.endswith("Mapper") else mapper_cls
        table = class_to_table.get(entity)
        if table:
            node(f"table:{table}", "table", table)
            edge(nid, f"table:{table}", "accesses")

    # 端点 → 控制器方法（并用方法体内的调用来细化服务边）
    inj_by_ctrl = {c: {i["name"]: i for i in j["injected"]} for c, j in controllers.items()}
    ep_rows: list[dict] = []
    for ep in endpoint_model["endpoints"]:
        method, path, auth = ep["method"], ep["path"], ep.get("auth")
        ep_id = f"ep:{method} {path}"
        node(ep_id, "endpoint", f"{method} {path}")
        owner = None
        for cls, j in controllers.items():
            if path.startswith(j["prefix"] or "\0") or (not j["prefix"]):
                for m in j["methods"]:
                    if any((hm == method and (j["prefix"] + sub) == path) for hm, sub in m["mappings"]):
                        owner, o_method = cls, m
                        break
            if owner:
                break
        if not owner:
            ep_rows.append({"endpoint": f"{method} {path}", "path": path, "http": method,
                            "controller": "—", "method": "—",
                            "services": [], "tables": [], "cross_api": [], "auth": auth,
                            "fe_callers": []})
            continue
        node(f"ctrl:{owner}", "controller", owner)
        edge(ep_id, f"ctrl:{owner}", "handled_by", owner)
        svc_hits, api_hits, tbl_hits = [], [], []
        for call in o_method["calls"]:
            recv = call.split(".", 1)[0]
            inj = inj_by_ctrl[owner].get(recv)
            if not inj:
                continue
            if inj["kind"] == "service":
                svc_hits.append(inj["type"]); edge(ep_id, f"svc:{inj['type']}", "calls", call)
            elif inj["kind"] == "cross_api":
                api_hits.append(inj["type"]); edge(ep_id, f"api:{inj['type']}", "calls", call)
        for s in svc_hits:
            for e2 in edges:
                if e2["from"] == f"svc:{s}" and e2["kind"] == "injects" and e2["to"].startswith("mapper:"):
                    mapper = e2["to"].split(":", 1)[1]
                    ent = mapper[:-6] if mapper.endswith("Mapper") else mapper
                    t = class_to_table.get(ent)
                    if t:
                        tbl_hits.append(t)
        ep_rows.append({
            "endpoint": f"{method} {path}", "path": path, "http": method,
            "controller": owner, "method": o_method["name"],
            "services": sorted(set(svc_hits)), "cross_api": sorted(set(api_hits)),
            "tables": sorted(set(tbl_hits)), "auth": auth, "fe_callers": [],
        })

    # 前端 → 端点
    fe = parse_frontend()
    by_norm: dict[tuple[str, str], list[dict]] = {}
    for e in ep_rows:
        m, p = e["endpoint"].split(" ", 1)
        by_norm.setdefault((m, norm_ep_path(p)), []).append(e)
    for call in fe:
        for hit in by_norm.get((call["method"], call["normalized"]), []):
            hit["fe_callers"].append(f"{call['app']}/{Path(call['file']).name}")
            fid = f"fe:{call['file']}"
            node(fid, "fe", f"{call['app'].replace('shang-', '').replace('-web', '')}/{Path(call['file']).name}",
                 call["file"])
            edge(fid, f"ep:{hit['endpoint']}", "fetches")

    # 鉴权前置
    for e in ep_rows:
        a = e.get("auth") or ""
        if a == "管理员 token":
            edge(f"ep:{e['endpoint']}", "ep:POST /admin/auth/login", "requires_auth")
        elif a == "会员 token":
            edge(f"ep:{e['endpoint']}", "ep:POST /api/auth/login", "requires_auth")

    return {
        "generated_at": generated_at or "",
        "generator": "api_testcases/scripts/build_call_graph.py",
        "fingerprint": fingerprint,
        "nodes": sorted(nodes.values(), key=lambda x: x["id"]),
        "edges": edges,
        "endpoints": ep_rows,
        "frontend_calls": fe,
    }


# --------------------------------------------------------------------------- #
# 渲染
# --------------------------------------------------------------------------- #

DOMAIN_RULES = [
    ("admin-identity", lambda e: e.startswith("/admin/") and any(
        k in e for k in ("/auth/", "/users", "/roles", "/permissions", "/depts", "/dicts", "/dict-items", "/operation-logs", "/system-configs", "/files"))),
    ("admin-catalog", lambda e: e.startswith("/admin/") and any(
        k in e for k in ("/products", "/categories", "/brands", "/attributes", "/stocks", "/warehouses", "/in-out"))),
    ("admin-trade", lambda e: e.startswith("/admin/") and any(
        k in e for k in ("/orders", "/after-sales", "/reviews", "/customers"))),
    ("admin-content", lambda e: e.startswith("/admin/") and any(
        k in e for k in ("/site/", "/home/", "/dashboard"))),
    ("storefront-auth", lambda e: e.startswith("/api/auth") or e.startswith("/api/me") or e.startswith("/api/files")),
    ("storefront-browse", lambda e: any(k in e for k in ("/api/home", "/api/site", "/api/categories", "/api/products", "/api/search"))),
    ("storefront-trade", lambda e: any(k in e for k in ("/api/cart", "/api/addresses", "/api/orders", "/api/payment", "/api/after-sales", "/api/favorites", "/api/history", "/api/reviews"))),
]


def domain_of(endpoint: str) -> str:
    for name, pred in DOMAIN_RULES:
        if pred(endpoint):
            return name
    return "other"


def mermaid_layers(model: dict) -> str:
    """控制器 → 服务 → 表 的分层聚合图（按业务域分子图，避免一张图过大）。"""
    eps = model["endpoints"]
    lines = ["```mermaid", "flowchart LR"]
    domains = {}
    for e in eps:
        domains.setdefault(domain_of(e.get("path") or e["endpoint"]), []).append(e)
    for d in sorted(domains):
        ctrls = sorted({e["controller"] for e in domains[d] if e["controller"] != "—"})
        svcs = sorted({s for e in domains[d] for s in e["services"]})
        tbls = sorted({t for e in domains[d] for t in e["tables"]})
        if not ctrls:
            continue
        lines.append(f"  subgraph D_{d.replace('-', '_')}[\"{d}\"]")
        lines.append("    direction LR")
        for c in ctrls:
            lines.append(f'    C_{c}["{c}"]')
        for s in svcs:
            lines.append(f'    S_{s}["{s}"]')
        for t in tbls:
            lines.append(f'    T_{t.replace(".", "_")}[("{t}")]')
        lines.append("  end")
    # 边（跨域去重，只画存在的关系）
    ctrl_svc = sorted({(e["controller"], s) for e in eps for s in e["services"] if e["controller"] != "—"})
    svc_tbl = []
    for e in eps:
        for s in e["services"]:
            for t in e["tables"]:
                svc_tbl.append((s, t))
    for c, s in ctrl_svc:
        lines.append(f"  C_{c} --> S_{s}")
    for s, t in sorted(set(svc_tbl)):
        lines.append(f"  S_{s} --> T_{t.replace('.', '_')}")
    lines.append("```")
    return "\n".join(lines)


def render(model: dict) -> str:
    L: list[str] = []
    add = L.append
    eps = model["endpoints"]
    edges = model["edges"]
    nodes = model["nodes"]

    add("# 调用链路（前端 → 端点 → 控制器 → 服务 → 表）")
    add("")
    add("> **机制区文档（禁止手改）**。由 `api_testcases/scripts/build_call_graph.py` 解析后端 Java 源码、")
    add("> 两个前端 `src/api/*.ts`、以及 `endpoint-model.json` / `field-map.json` 生成。")
    add(">")
    add(f"> 生成命令：`python3 api_testcases/scripts/build_call_graph.py`  ")
    add(f"> 版本指纹：`{model['fingerprint']}`  ·  生成时间：{model['generated_at']}  ")
    add(f"> 规模：{len(nodes)} 节点 / {len(edges)} 边 / {len(eps)} 端点链路")
    add("")
    add("## 0. 先读：本项目**没有**「接口调接口」")
    add("")
    add("这是**模块化单体**（`shang-server` 聚合启动，7 个 Maven 模块同进程）。全仓无 `RestTemplate` /")
    add("`WebClient` / `Feign` / Dubbo / MQ —— 所以通用 API 图工具里那些「服务间调用」关系在本项目**不存在**，")
    add("硬套只会画出一张空图。真实链路只有这五类：")
    add("")
    add("| 关系 | 形态 | 本图怎么表示 |")
    add("|---|---|---|")
    add("| 前端调用 | `http({url,method})` → 端点 | `fe:*.ts → ep:*`（`fetches`） |")
    add("| 端点 → 控制器 | 映射注解 → 方法 | `ep:* → ctrl:*`（`handled_by`） |")
    add("| 调用 | 控制器/服务方法体内调用注入的 Bean | `calls` / `injects` |")
    add("| 数据访问 | 服务 → Mapper → 表 | `mapper:* → table:*`（`accesses`） |")
    add("| **跨上下文** | 同进程 `*-api` 接口（**非 HTTP**） | `svc:* → api:*`（`cross_api`） |")
    add("| 鉴权前置 | 受保护端点 → 登录端点 | `requires_auth` |")
    add("")
    add("## 1. 模块依赖（构建期，来自各 pom.xml）")
    add("")
    add("```mermaid")
    add("flowchart LR")
    add('  S["shang-server<br/>聚合启动"]')
    add('  CM["shang-common<br/>Result/JWT/审计/OSS"]')
    add('  IA["shang-identity-api"]')
    add('  CA["shang-commerce-api"]')
    add('  I["shang-identity<br/>12 控制器"]')
    add('  C["shang-commerce<br/>11 控制器"]')
    add('  CS["shang-customerservice<br/>空占位"]')
    add("  S --> I")
    add("  S --> C")
    add("  S --> CS")
    add("  C --> IA")
    add("  C --> CA")
    add("  C --> CM")
    add("  I --> CM")
    add("  I --> IA")
    add("  I --> CA")
    add("```")
    add("")
    add("跨上下文的**唯一**通道是 `*-api` 接口：`commerce → identity-api`（`CustomerQueryApi`、`SystemConfigApi`），")
    add("`identity → commerce-api`（`OrderQueryApi`）。`commerce-api` 里的 `StockApi` 是空接口、零实现零引用。")
    add("")
    add("## 2. 分层链路聚合图（按业务域分图）")
    add("")
    add("节点类型：`控制器` → `服务` → `表`。表用圆角节点表示。")
    add("")
    add(mermaid_layers(model))
    add("")

    add("## 3. 端点级链路明细")
    add("")
    add("逐端点的完整链路。**这一节是「追踪」的主表**：改一个服务/表，按 `表` 列反查即可定位受影响端点。")
    add("")
    for d in sorted({domain_of(e.get("path") or e["endpoint"]) for e in eps}):
        group = [e for e in eps if domain_of(e.get("path") or e["endpoint"]) == d]
        add(f"### {d}（{len(group)} 个端点）")
        add("")
        add("| 端点 | 控制器.方法 | 服务 | 触及的表 | 跨上下文 API | 前端调用方 |")
        add("|---|---|---|---|---|---|")
        for e in sorted(group, key=lambda x: x["endpoint"]):
            add(f"| `{e['endpoint']}` | `{e['controller']}.{e['method']}` | "
                f"{'、'.join('`'+s+'`' for s in e['services']) or '—'} | "
                f"{'、'.join('`'+t+'`' for t in e['tables']) or '—'} | "
                f"{'、'.join('`'+a+'`' for a in e['cross_api']) or '—'} | "
                f"{'、'.join(sorted(set(e['fe_callers']))) or '—'} |")
        add("")

    add("## 4. 鉴权前置链路")
    add("")
    add("所有受保护端点都依赖登录端点产出的 token。**这决定了自动化的执行顺序**：")
    add("先登录拿 token，再打业务端点；token 按 `clientType` 单向隔离（admin token 打 `/api/**` 会被 401）。")
    add("")
    add("```mermaid")
    add("flowchart LR")
    add('  AL["POST /admin/auth/login"] --> AT(["管理员 token 900s"])')
    add('  ML["POST /api/auth/login<br/>（演示固定码 123456）"] --> MT(["会员 token 900s"])')
    add('  AT --> AA["全部 /admin/** 端点<br/>（除 login/refresh）"]')
    add('  MT --> MA["全部 /api/** 端点<br/>（除 auth 白名单）"]')
    add('  AR["POST /admin/auth/refresh"] -.-> AT')
    add('  MR["POST /api/auth/refresh"] -.-> MT')
    add("```")
    add("")
    auth_rows = [e for e in eps if e.get("auth") in ("管理员 token", "会员 token")]
    add(f"受保护端点：**{len(auth_rows)}/{len(eps)}**（其余为免认证白名单）。")
    add("")

    add("## 5. 未经服务层的端点（逻辑直接落在控制器 / 直接注入 Mapper）")
    add("")
    nosvc = [e for e in eps if e["controller"] != "—" and not e["services"]]
    svc_ctrls = {e["controller"] for e in eps if e["services"]}
    mixed = sorted({e["controller"] for e in nosvc} & svc_ctrls)
    add(f"**{len(nosvc)}/{len(eps)}** 个端点在方法体内没有调用到应用服务——要么逻辑内联在控制器里"
        f"（如 C 端评价创建），要么该端点直接操作注入的 Mapper。")
    add("")
    add("⚠️ 注意这是**按端点**判定，不是按控制器：")
    if mixed:
        add(f"其中 `{'`、`'.join(mixed)}` 是**混合型**——同一控制器下部分端点走服务、部分不走，"
            "不能整类归为「无服务层」。")
    add("")
    if nosvc:
        add("| 控制器 | 无服务端点 | 端点 |")
        add("|---|---|---|")
        for c in sorted({e["controller"] for e in nosvc}):
            g = sorted(e["endpoint"] for e in nosvc if e["controller"] == c)
            add(f"| `{c}` | {len(g)} | {'、'.join('`'+x+'`' for x in g)} |")
    add("")
    add("读图表时的含义：这类端点在图上是 `端点 → 控制器 → Mapper → 表` 的短链，")
    add("它们的业务规则**不会**出现在服务层，追溯规则时要直接读控制器方法。")
    add("")

    add("## 6. 怎么用它做跨版本追踪")
    add("")
    add("`call-graph.json` 与其它机制区产物同构，可交给 `baseline_diff.py` 比对：")
    add("")
    add("```bash")
    add("cp artifacts/call-graph.json /tmp/old-call-graph.json   # 旧版本留档")
    add("python3 scripts/build_call_graph.py                     # 新版本重新生成")
    add("diff <(python3 -c \"import json;d=json.load(open('/tmp/old-call-graph.json'));print('\\n'.join(sorted(e['from']+' -> '+e['to'] for e in d['edges'])))\") \\")
    add("     <(python3 -c \"import json;d=json.load(open('artifacts/call-graph.json'));print('\\n'.join(sorted(e['from']+' -> '+e['to'] for e in d['edges'])))\")")
    add("```")
    add("")
    add("新增/消失的边 = 链路变化，比逐接口比对更容易发现「悄悄改了调用路径」。")
    add("")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-md", default=str(REPO / "docs/06-baseline/05-调用链路.md"))
    ap.add_argument("--out-json", default=str(ROOT / "artifacts/call-graph.json"))
    ap.add_argument("--fingerprint", default="(unset)")
    ap.add_argument("--generated-at", default=None)
    args = ap.parse_args()

    out_json = Path(args.out_json)
    prev = None
    if out_json.exists():
        try:
            prev = json.loads(out_json.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            prev = None
    candidate = build(args.fingerprint)
    model = build(args.fingerprint, sticky_generated_at(prev, candidate, args.generated_at))
    out_md, out_json = Path(args.out_md), Path(args.out_json)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text(render(model), encoding="utf-8")
    out_json.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    kinds: dict[str, int] = {}
    for e in model["edges"]:
        kinds[e["kind"]] = kinds.get(e["kind"], 0) + 1
    print(json.dumps({
        "节点": len(model["nodes"]), "边": len(model["edges"]),
        "边类型": kinds, "端点链路": len(model["endpoints"]),
        "前端调用点": len(model["frontend_calls"]),
        "输出md": str(out_md),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
