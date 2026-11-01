#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从后端源码 + springdoc OpenAPI 生成《接口文档.md》，并输出源码↔文档差异表。

真值层级（本任务约定）：源码 > springdoc OpenAPI > 接口文档 > PRD。
因此：以源码端点集为基准核对 OpenAPI，缺漏以源码为准补入文档。

用法：
  python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \
      --out ../docs/测试资料/接口文档.md --diff ../artifacts/api-doc-diff.md
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

METHOD_ANNOTATIONS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}

# SecurityConfig 白名单（源码 SecurityConfig.java 的机器化副本）
PERMIT_ALL = (
    "/admin/auth/login", "/admin/auth/refresh", "/api/auth/**",
    "/doc.html", "/knife4j/**", "/webjars/**", "/v3/api-docs/**",
    "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico", "/error",
)
PERMIT_GET = (
    "/admin/files/view", "/api/files/view", "/api/home", "/api/site/brand",
    "/api/products", "/api/products/**", "/api/categories", "/api/search",
)


def match_rule(path: str, pattern: str) -> bool:
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3])
    return path == pattern


def auth_requirement(method: str, path: str) -> str:
    if any(match_rule(path, p) for p in PERMIT_ALL):
        return "免认证"
    if method == "GET" and any(match_rule(path, p) for p in PERMIT_GET):
        return "免认证（GET）"
    if path.startswith("/admin/"):
        return "管理员 token"
    if path.startswith("/api/"):
        return "会员 token"
    return "免认证"


def split_top_level(text: str) -> list[str]:
    """按逗号切分，忽略字符串与括号内的逗号。"""
    out, buf, depth, in_str = [], [], 0, False
    for ch in text:
        if ch == '"':
            in_str = not in_str
        if not in_str:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif ch == "," and depth == 0:
                out.append("".join(buf).strip())
                buf = []
                continue
        buf.append(ch)
    if buf:
        out.append("".join(buf).strip())
    return [x for x in out if x]


def extract_paths(annotation_body: str) -> list[str]:
    body = annotation_body.strip()
    if not body:
        return [""]
    # 仅当整体以花括号开头时才按数组解析；否则 "/{id}" 会被误当数组语法剥掉花括号
    if body.startswith("{"):
        m = re.match(r"\{(.*)\}\s*$", body, re.S)
        if m:
            return [s.strip().strip('"') for s in split_top_level(m.group(1)) if s.strip()]
    m = re.search(r'"(.*?)"', body, re.S)
    if m:
        return [m.group(1)]
    return [""]


def parse_controller(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    class_base = ""
    for i, ln in enumerate(lines):
        if re.match(r"\s*public\s+class\s+\w+", ln):
            # 向上找最近的 @RequestMapping
            for j in range(i - 1, max(-1, i - 8), -1):
                mm = re.match(r'\s*@RequestMapping\((.*)\)\s*$', lines[j])
                if mm:
                    got = extract_paths(mm.group(1))
                    class_base = got[0] if got else ""
                    break
            break
    endpoints = []
    for i, ln in enumerate(lines):
        mm = re.match(r"\s*@(Get|Post|Put|Delete|Patch)Mapping(?:\((.*)\))?\s*$", ln)
        if not mm:
            continue
        method = METHOD_ANNOTATIONS[mm.group(1) + "Mapping"]
        subs = extract_paths(mm.group(2) or "")
        # 向下扫到方法签名结束：取 @Operation summary 与 @RequestBody 类型、方法体错误码
        summary = ""
        body_type = ""
        codes: list[str] = []
        calls: list[str] = []
        body_keys: list[str] = []
        resp_keys: list[str] = []
        body_presence: list[str] = []
        body_param = ""
        has_valid = False
        for k in range(i + 1, min(len(lines), i + 120)):
            cur = lines[k]
            sm = re.search(r'summary\s*=\s*"([^"]*)"', cur)
            if sm and not summary:
                summary = sm.group(1)
            bm = re.search(r"@RequestBody\s+(?:@\w+(?:\([^)]*\))?\s+)*([A-Z]\w+(?:\.[A-Z]\w+)*(?:<[^>]*>)?)\s+(\w+)", cur)
            if bm:
                if not body_type:
                    body_type = bm.group(1)
                body_param = bm.group(2)
            # 逐行采集请求体参数自身的 getter（声明行之后的读取行才是字段来源）
            if body_param:
                for getter in re.findall(rf"\b{re.escape(body_param)}\.get([A-Z][A-Za-z0-9]*)\(\)", cur):
                    body_keys.append(getter[0].lower() + getter[1:])
            bm2 = re.search(r"@RequestBody\s+([A-Z]\w+(?:\.[A-Z]\w+)*)\s+(\w+)", cur)
            if bm2:
                if not body_type:
                    body_type = bm2.group(1)
                body_param = bm2.group(2)
            if re.search(r"@Valid\s*(?:\n|\s)*@?\w*RequestBody|@Valid\s+\w+\s+\w+", cur) and "@RequestBody" in cur:
                has_valid = True
            if re.search(r"ErrorCode\.([A-Z_]+)", cur):
                codes.extend(re.findall(r"ErrorCode\.([A-Z_]+)", cur))
            for cls, meth in re.findall(r"\b([a-z]\w*(?:Service|Api|Mapper|Util|Factory|Helper))\s*\.\s*(\w+)\s*\(", cur):
                calls.append(f"{cls}.{meth}")
            for key in re.findall(r'\b\w+\.get\("(\w+)"\)', cur):
                body_keys.append(key)
            for key in re.findall(r'\b\w+\.put\("([^"]+)"', cur):
                resp_keys.append(key)
            if body_param:
                window = cur + "\n" + "\n".join(lines[k + 1:k + 4])
                # 存在性校验 = 非空判断后紧跟 throw；仅赋默认值不算校验
                for fld in re.findall(rf"hasText\(\s*{re.escape(body_param)}\.get(\w+)\(\)\s*\)", cur):
                    if "throw" in window:
                        body_presence.append(fld[0].lower() + fld[1:])
                for fld in re.findall(rf"{re.escape(body_param)}\.get(\w+)\(\)\s*==\s*null", cur):
                    if "throw" in window:
                        body_presence.append(fld[0].lower() + fld[1:])
            if re.search(r"^    \}", cur):
                break
        for sub in subs:
            full = (class_base + sub) if sub.startswith("/") or class_base else (class_base + "/" + sub if sub else class_base)
            full = re.sub(r"//+", "/", full) or class_base
            endpoints.append({
                "method": method, "path": full, "class_base": class_base,
                "file": str(path), "line": i + 1,
                "summary": summary, "body_type": body_type, "has_valid": has_valid,
                "body_keys": sorted(set(body_keys)),
                "resp_keys": sorted(set(resp_keys)),
                "body_presence": sorted(set(body_presence)),
                "codes": sorted(set(codes)), "calls": sorted(set(calls)),
            })
    return endpoints


AUDIT_FIELDS = {"id", "createdAt", "updatedAt", "deleted", "traceId", "version", "page", "size"}


def dto_facts(index: dict[str, Path], class_name: str) -> tuple[list[str], list[str]]:
    """从源码 DTO 类解析字段与必填字段（Bean Validation 注解驱动）。"""
    if not class_name:
        return [], []
    raw = re.sub(r"<.*>", "", class_name).strip()
    parts = [x for x in raw.split(".") if x]
    simple = parts[-1]
    path = index.get(simple) or (index.get(parts[0]) if parts else None)
    if path is None:
        return [], []
    src_lines = path.read_text(encoding="utf-8").splitlines()
    if len(parts) > 1 and path.stem != simple:
        # 嵌套类：从内部类声明处开始解析字段
        for i, ln in enumerate(src_lines):
            if re.search(rf"\bclass\s+{re.escape(simple)}\b", ln):
                src_lines = src_lines[i:]
                break
    fields: list[str] = []
    required: list[str] = []
    pending: list[str] = []
    for ln in src_lines:
        s = ln.strip()
        if s.startswith("@"):
            pending.append(s)
            continue
        m = re.match(r"private\s+([\w<>,.\[\]]+)\s+(\w+)\s*;", s)
        if m:
            ftype, fname = m.group(1), m.group(2)
            fields.append(f"{fname}:{ftype}")
            if any(a.startswith(("@NotBlank", "@NotNull", "@NotEmpty")) for a in pending):
                required.append(fname)
            pending = []
            continue
        if s and not s.startswith("//"):
            pending = []
    return fields, required


def collect_source_endpoints(repo_root: Path) -> list[dict]:
    out = []
    for f in sorted(repo_root.glob("backend/**/controller/*.java")):
        out.extend(parse_controller(f))
    return out


def _build_class_index(repo_root: Path) -> dict[str, Path]:
    idx: dict[str, Path] = {}
    for f in repo_root.glob("backend/**/*.java"):
        idx.setdefault(f.stem, f)
    return idx


def _method_body_codes(lines: list[str], start: int, limit: int = 200,
                      param_name: str | None = None) -> tuple[set[str], set[str], set[str]]:
    """扫一个方法体，返回 (ErrorCode 词条, 被调用的 服务.方法, 请求体字段)。

    param_name 给出时只采集「该参数自身的 getter」——即请求体真实携带的字段，
    避免把无关服务的 getter（如 JwtUtil.getAccessTtlSeconds）误判为请求体字段。
    """
    codes: set[str] = set()
    calls: set[str] = set()
    reads: set[str] = set()
    for k in range(start, min(len(lines), start + limit)):
        cur = lines[k]
        codes.update(re.findall(r"ErrorCode\.([A-Z_]+)", cur))
        if param_name:
            for getter in re.findall(rf"\b{re.escape(param_name)}\.get([A-Z][A-Za-z0-9]*)\(\)", cur):
                reads.add(getter[0].lower() + getter[1:])
        for key in re.findall(r'\b\w+\.put\("([^"]+)"', cur):
            reads.add("@" + key)
        for cls, meth in re.findall(r"\b([a-z]\w*(?:Service|Api|Mapper|Util|Factory|Helper))\s*\.\s*(\w+)\s*\(", cur):
            calls.add(f"{cls}.{meth}")
        if re.match(r"^    \}", cur):
            break
    return codes, calls, reads


def service_facts(repo_root: Path, calls: set[str], index: dict[str, Path],
                  depth: int = 0, seen: set[str] | None = None,
                  biz: set[str] | None = None,
                  body_type: str = "") -> tuple[set[str], set[str], set[str]]:
    """把 Service 调用解析为 (ErrorCode 词条, 被读取的请求体字段)（最多递归 2 层）。"""
    seen = seen or set()
    biz = biz if biz is not None else set()
    out: set[str] = set()
    resp: set[str] = set()
    presence: set[str] = set()
    for call in calls:
        cls, _, meth = call.partition(".")
        cls_name = cls[0].upper() + cls[1:]
        # 变量名 → 类型名的最接近映射：按文件名匹配（首字母大写）
        path = index.get(cls_name)
        if path is None or depth >= 2:
            continue
        key = f"{cls_name}.{meth}"
        if key in seen:
            continue
        seen.add(key)
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, ln in enumerate(lines):
            if re.search(rf"\b{meth}\s*\(", ln) and ("public" in ln or "private" in ln or "protected" in ln):
                simple = re.sub(r"<.*>", "", body_type).strip().split(".")[-1] if body_type else ""
                param = None
                if simple:
                    sig = " ".join(lines[i:min(i + 4, len(lines))])
                    pm = re.search(rf"\b{re.escape(simple)}\s+(\w+)\s*[,)]", sig)
                    param = pm.group(1) if pm else None
                codes, calls2, reads = _method_body_codes(lines, i + 1, param_name=param)
                out |= codes
                biz.update(reads)
                body = "\n".join(lines[i + 1:i + 1 + 60])
                if param:
                    for fld in re.findall(rf"hasText\(\s*{re.escape(param)}\.get(\w+)\(\)\s*\)", body):
                        if "throw" in body:
                            presence.add(fld[0].lower() + fld[1:])
                resp.update({k[1:] for k in reads if k.startswith("@")})
                if calls2 and depth < 2:
                    sub_codes, sub_reads, sub_resp, sub_pres2 = service_facts(
                        repo_root, calls2, index, depth + 1, seen, body_type=body_type)
                    out |= sub_codes
                    biz.update(sub_reads)
                    resp.update(sub_resp)
                    presence.update(sub_pres2)
                break
    return out, biz, resp, presence


def deref(schema: dict, comps: dict, seen: set | None = None) -> dict:
    seen = seen or set()
    if not schema:
        return {}
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        if name in seen:
            return {"type": "object", "title": name}
        seen = seen | {name}
        return deref(comps.get(name, {}), comps, seen)
    return schema


def schema_text(schema: dict, comps: dict, depth: int = 0) -> str:
    s = deref(schema, comps)
    if not s:
        return "—"
    t = s.get("type")
    if t == "array":
        return "array[" + schema_text(s.get("items", {}), comps, depth) + "]"
    if t == "object" or "properties" in s:
        props = s.get("properties") or {}
        if not props:
            return "object"
        if depth >= 1:
            return "object{…}"
        req = set(s.get("required") or [])
        bits = []
        for name, val in props.items():
            mark = "*" if name in req else ""
            bits.append(f"{name}{mark}:{schema_text(val, comps, depth + 1)}")
        return "{" + ", ".join(bits) + "}"
    bits = [t or "object"]
    if s.get("format"):
        bits.append(s["format"])
    if s.get("enum"):
        bits.append("enum=" + "/".join(map(str, s["enum"])))
    if s.get("example") is not None:
        bits.append(f"例:{s['example']}")
    return " ".join(bits)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--openapi", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--diff", type=Path, default=None)
    args = ap.parse_args()

    src = collect_source_endpoints(args.repo_root)
    index = _build_class_index(args.repo_root)
    for e in src:
        merged = set(e["codes"])
        merged |= service_facts(args.repo_root, set(e["calls"]), index,
                                body_type=e.get("body_type") or "")[0]
        if auth_requirement(e["method"], e["path"]) != "免认证":
            merged.add("UNAUTHORIZED")
        e["codes"] = sorted(merged)
    doc = json.loads(args.openapi.read_text(encoding="utf-8"))
    comps = doc.get("components", {}).get("schemas", {})

    src_set = {(e["method"], e["path"]) for e in src}
    oa_set: set[tuple[str, str]] = set()
    oa_ops: dict[tuple[str, str], dict] = {}
    for path, item in doc.get("paths", {}).items():
        for m, op in item.items():
            if m.upper() in ("GET", "POST", "PUT", "DELETE", "PATCH"):
                key = (m.upper(), path)
                oa_set.add(key)
                oa_ops[key] = op

    only_src = sorted(src_set - oa_set)
    only_oa = sorted(oa_set - src_set)
    both = sorted(src_set & oa_set)

    # 控制台摘要
    print(json.dumps({
        "源码端点": len(src_set), "OpenAPI 端点": len(oa_set),
        "共有": len(both), "仅源码有": len(only_src), "仅 OpenAPI 有": len(only_oa),
    }, ensure_ascii=False))

    # 差异表
    if args.diff:
        lines = ["# 接口文档 · 源码 ↔ OpenAPI 差异表", "",
                 f"- 源码端点数：{len(src_set)}；OpenAPI 端点数：{len(oa_set)}；交集 {len(both)}", ""]
        lines.append("## 仅源码存在（需补进文档）" if only_src else "## 仅源码存在：无")
        lines.append("")
        if only_src:
            lines.append("| 方法 | 路径 | 位置 |")
            lines.append("|---|---|---|")
            for m, p in only_src:
                loc = next((f"{e['file'].split('/backend/')[-1]}:{e['line']}" for e in src if e["method"] == m and e["path"] == p), "")
                lines.append(f"| {m} | `{p}` | {loc} |")
        lines.append("")
        lines.append("## 仅 OpenAPI 存在（源码无对应注解）" if only_oa else "## 仅 OpenAPI 存在：无")
        lines.append("")
        if only_oa:
            lines.append("| 方法 | 路径 |")
            lines.append("|---|---|")
            for m, p in only_oa:
                lines.append(f"| {m} | `{p}` |")
        args.diff.parent.mkdir(parents=True, exist_ok=True)
        args.diff.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # 文档主体
    by_ctrl: dict[str, list[dict]] = {}
    for e in src:
        ctrl = Path(e["file"]).stem
        by_ctrl.setdefault(ctrl, []).append(e)

    out = []
    out.append("# 尚品汇（Shang Pivot）接口文档")
    out.append("")
    out.append("> 生成方式：`api_testcases/scripts/build_api_doc.py` 从**后端源码**提取端点，与 springdoc OpenAPI")
    out.append("> （`docs/openapi-v3-api-docs.json`）交叉核对后生成；差异表见 `api_testcases/artifacts/api-doc-diff.md`。")
    out.append("> 真值层级：源码 > OpenAPI > 本文档。整理日期：2026-10-05。")
    out.append("")
    out.append("## 1. 通用约定")
    out.append("")
    out.append("| 项 | 说明 |")
    out.append("|---|---|")
    out.append("| 管理端前缀 | `/admin/**`（除 `/admin/auth/login`、`/admin/auth/refresh` 外均需管理员 token） |")
    out.append("| C 端前缀 | `/api/**`（`/api/auth/**` 与少量 GET 浏览接口免认证） |")
    out.append("| 鉴权 | `Authorization: Bearer <access token>`；JWT 双令牌，access 900s / refresh 604800s |")
    out.append("| 成功信封 | `{\"code\":0,\"msg\":\"success\",\"data\":{...},\"traceId\":\"...\"}`，**成功业务码为 0** |")
    out.append("| 失败信封 | `{\"code\":<错误码数值>,\"msg\":\"<中文提示>\",\"traceId\":\"...\"}`，错误码词条见《领域词典.md》§9 |")
    out.append("| 分页 | MyBatis-Plus 分页，列表接口通用 `page`（从 1 起）/`size` 参数 |")
    out.append("| 删除 | 逻辑删（`deleted` 标记） |")
    out.append("| 免认证白名单（源码 SecurityConfig） | " + "、".join(f"`{x}`" for x in PERMIT_ALL) + " |")
    out.append("| 免认证 GET 白名单 | " + "、".join(f"`{x}`" for x in PERMIT_GET) + " |")
    out.append("")
    out.append("## 2. 端点总表")
    out.append("")
    out.append("> 总计 " + str(len(src)) + " 个端点操作；每行含鉴权要求与主要错误码（源码 `ErrorCode` 引用）。")
    out.append("")
    out.append("| 方法 | 路径 | 说明 | 鉴权 | 请求体/参数 | 错误码 | 实现位置 |")
    out.append("|---|---|---|---|---|---|---|")
    for e in sorted(src, key=lambda x: (x["path"], x["method"])):
        key = (e["method"], e["path"])
        op = oa_ops.get(key, {})
        params = op.get("parameters") or []
        ptext = []
        for p in params:
            req = "必填" if p.get("required") else "可选"
            ptext.append(f"{p.get('name')}({p.get('in')},{req})")
        op_body = op.get("requestBody")
        body_desc = ""
        if op_body:
            sch = (op_body.get("content", {}).get("application/json", {}) or {}).get("schema", {})
            body_desc = schema_text(sch, comps) if sch else ""
            if body_desc in ("object", "—", "") and e["body_type"]:
                body_desc = f"{e['body_type']}（OpenAPI 未展开，见源码）"
        elif e["body_type"]:
            body_desc = f"{e['body_type']}（源码 @RequestBody）"
        param_text = "；".join(filter(None, ["params: " + ", ".join(ptext) if ptext else "", body_desc]))
        codes = "、".join(e["codes"]) if e["codes"] else "—"
        summary = e["summary"] or op.get("summary") or ""
        loc = e["file"].split("/backend/")[-1] + f":{e['line']}"
        out.append(f"| {e['method']} | `{e['path']}` | {summary} | {auth_requirement(e['method'], e['path'])} | {param_text or '—'} | {codes} | `{loc}` |")
    out.append("")
    out.append("## 3. 按 Controller 分组")
    out.append("")
    for ctrl in sorted(by_ctrl):
        eps = sorted(by_ctrl[ctrl], key=lambda x: (x["path"], x["method"]))
        rel = eps[0]["file"].split("/backend/")[-1]
        out.append(f"### {ctrl}（`{rel}`）")
        out.append("")
        out.append("| 方法 | 路径 | 说明 | 鉴权 |")
        out.append("|---|---|---|---|")
        for e in eps:
            out.append(f"| {e['method']} | `{e['path']}` | {e['summary']} | {auth_requirement(e['method'], e['path'])} |")
        out.append("")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(out) + "\n", encoding="utf-8")
    model = []
    for e in sorted(src, key=lambda x: (x["path"], x["method"])):
        svc_codes, svc_biz, svc_resp, svc_pres = service_facts(args.repo_root, set(e["calls"]), index,
                                                               body_type=e.get("body_type") or "")
        codes = set(e["codes"]) | svc_codes
        # 过滤：审计字段、以及以 "@" 标记的响应键（仅用于 resp_keys，不是请求体字段）
        biz_fields = {f for f in (set(e.get("body_keys") or []) | svc_biz)
                      if f not in AUDIT_FIELDS and not f.startswith("@")}
        if auth_requirement(e["method"], e["path"]) != "免认证":
            codes.add("UNAUTHORIZED")
        op = oa_ops.get((e["method"], e["path"]), {})
        path_params = [pp for pp in (op.get("parameters") or []) if pp.get("in") == "path"]
        dto_fields, dto_required = dto_facts(index, e.get("body_type") or "")
        model.append({
            "method": e["method"], "path": e["path"], "summary": e["summary"],
            "dto_class": e.get("body_type") or "",
            "dto_fields": dto_fields, "dto_required": dto_required,
            "biz_fields": sorted(biz_fields & {f.split(":")[0] for f in dto_fields} | biz_fields),
            "resp_keys": sorted(set(e.get("resp_keys") or []) | svc_resp),
            "auth": auth_requirement(e["method"], e["path"]),
            "has_path_param": bool(path_params),
            "body_keys": e.get("body_keys") or [],
            "body_presence": sorted((set(e.get("body_presence") or []) | svc_pres) - AUDIT_FIELDS),
            "has_valid": bool(e.get("has_valid")),
            # 参数维度只认「存在性校验」（Bean Validation / hasText(...) / == null 显式判断）；
            # 仅取值范围校验（长度/非零）不算——提交空体在源码里是"无操作成功"，不会报参数错误。
            "param_enforced": bool(path_params) or bool(e.get("has_valid")) or bool(e.get("body_presence")),
            "codes": sorted(codes),
        })
    # ---- 事实源自检：不变量 + 覆盖率（不达标即失败，防止静默失真）----
    problems: list[str] = []
    for e in model:
        declared = {f.split(":")[0] for f in (e.get("dto_fields") or [])}
        if declared:
            leaked = [f for f in (e.get("biz_fields") or []) if f not in declared]
            if leaked:
                problems.append(f"{e['method']} {e['path']}：请求体字段超出声明字段 {leaked}")
    writes = [e for e in model if e["method"] in ("POST", "PUT", "PATCH")]
    with_facts = [e for e in writes if e["dto_required"] or e["biz_fields"] or e["dto_fields"]]
    if not all(e.get("auth") for e in model):
        problems.append("存在缺少鉴权事实的端点")
    if not all(e.get("codes") for e in model):
        problems.append("存在缺少错误码事实的端点")
    coverage = {
        "端点": len(model), "写接口": len(writes), "写接口有请求体字段事实": len(with_facts),
        "有响应键事实": sum(1 for e in model if e.get("resp_keys")),
        "有源码必填字段": sum(1 for e in model if e.get("dto_required")),
        "参数维度适用": sum(1 for e in model if e.get("param_enforced")),
    }
    print(json.dumps(coverage, ensure_ascii=False))
    if problems:
        print("✘ 事实源自检未通过：")
        for x in problems[:10]:
            print("   -", x)
        raise SystemExit(2)

    model_path = Path(__file__).resolve().parents[1] / "artifacts" / "endpoint-model.json"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    model_path.write_text(json.dumps({"endpoints": model}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"已写出 {args.out} 与 {model_path}")


if __name__ == "__main__":
    main()