#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用例质量门禁（lint）v1.0

用法：
  python3 scripts/case_lint.py                    # 离线检查（默认清单路径）
  python3 scripts/case_lint.py --online           # 追加禅道 keyword/标题一致性核对
  python3 scripts/case_lint.py --strict           # warn 也视为失败（退出码 1）
  python3 scripts/case_lint.py --mdout x.md --jsonout x.json

规则见《lint方案.md》R01–R20。退出码：0=通过，1=存在 fail（或 strict 下有 warn），2=输入错误。
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

DEFAULT_INV = Path(__file__).resolve().parents[1] / "test_cases_inventory.md"


def _governance_doc(name: str, env_var: str) -> Path:
    """治理文档位置：环境变量 > 仓库内 docs/测试资料/ > 历史桌面路径（兼容旧运行方式）。"""
    env = os.getenv(env_var)
    if env:
        return Path(env)
    repo_doc = Path(__file__).resolve().parents[2] / "docs" / "测试资料" / name
    if repo_doc.exists():
        return repo_doc
    legacy = Path("/Users/imac/Desktop/电商自动化测试") / name
    return legacy if legacy.exists() else repo_doc


DEFAULT_DOC = _governance_doc("接口文档.md", "API_DOC_PATH")
DEFAULT_DICT = _governance_doc("领域词典.md", "DOMAIN_DICT_PATH")
ENUM_TOKEN = re.compile(r"\b[A-Z][A-Z_]{3,}\b")
ENUM_STOPWORDS = {"HTTP", "JSON", "JWT", "SKU", "SPU", "API", "URL", "GET", "POST", "PUT", "DELETE", "PATCH"}

def parse_domain_dict(dict_path: Path) -> set[str]:
    """从领域词典提取机器可读词表（‘机器可读词表：’ 行）。"""
    if not dict_path.exists():
        return set()
    for line in dict_path.read_text(encoding="utf-8").splitlines():
        if line.startswith("机器可读词表："):
            return set(line.split("：", 1)[1].split())
    return set()
AUTH_EXEMPT = [
    # 尚品汇 SecurityConfig 白名单（源码 shang-identity/config/SecurityConfig.java）
    r"^/admin/auth/login$", r"^/admin/auth/refresh$", r"^/api/auth/",
    r"^/admin/files/view$", r"^/api/files/view$", r"^/api/home$", r"^/api/site/brand$",
    r"^/api/products", r"^/api/categories", r"^/api/search",
]
BANNED_TITLE = re.compile(r"HTTP|code\s*=|UNAUTHORIZED|VALIDATION_FAILED|STATE_INVALID|PENDING|SUBMITTED|PROCESSING|待确认|API-AUTO|_id$|[^0-9](400|404|500)[^0-9]")
VAGUE_EXPECT = re.compile(r"待确认|TBD|待定|4xx|符合约定|允许枚举")
PLACEHOLDER = re.compile(r"<[a-zA-Z\u4e00-\u9fa5][^>]*>|account\"\s*:\s*\"\"|密码\":\"\"")
TOKEN_CONVENTION = re.compile(r"^<(运行时|管理员|普通用户) token>$")
BODYLESS_ACTIONS = {
    # 尚品汇无请求体的动作型接口（源码确认无 @RequestBody）
    "POST /api/orders/{id}/cancel", "POST /api/orders/{id}/confirm",
    "POST /admin/orders/{id}/ship", "POST /admin/auth/logout",
    "POST /api/auth/logout", "POST /api/cart/clear-invalid",
}
STALE_STEP_PHRASES = ("先调用对应列表接口动态获取路径参数", "baseUrl 与账号从 pytest 环境变量取")
HARDCODE_ENV = re.compile(r"\d+\.\d+\.\d+\.\d+|localhost|:6\d{4}")
FIXED_ID = re.compile(r"[Ii]d\s*[:=]\s*\d+")
CLEAN_ACTIONS = ("删除", "还原", "关闭", "移除", "撤销", "清理", "取消")
# 尚品汇成功业务码是 0（ErrorCode.SUCCESS），不是 200
SUCCESS_CODE = re.compile(r"^0([（(].*[）)])?$")
# 尚品汇失败族：BizException 无 @ResponseStatus → HTTP 200 + body 数值码
NEGATIVE_HTTP_200 = re.compile(r"^200[（(][^）)]*[）)]$")
# 路径前缀（源码 SecurityConfig：/admin/** 与 /api/**）
PATH_PREFIXES = ("/admin", "/api")
# v2.6 §3.6：断言可判定格式
R29_DATA_ANCHOR = re.compile(r"data\.|回查|重新查询|再查|复核|二次查询")
R29_FIELD_ANCHOR = re.compile(
    r"(数量|状态|金额|库存|余额|字段|列表|数组|token|编号|有效期|时间|等级)[^。；，,]{0,14}"
    r"(为|等于|包含|不含|非空|一致|不重复|生效|匹配|=)")
R29_VAGUE_COMPARE = re.compile(r"结果一致|保持一致|结果正确|数据正确|状态正确|数量正确")

def parse_inventory(path: Path) -> list[dict[str, Any]]:
    rows = []
    for ln, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("| ") or line.startswith("| 用例ID") or line.startswith("|---"):
            continue
        cells = [c.strip().replace("\\|", "|") for c in line.split("|")[1:-1]]
        if len(cells) != 14 or not cells[0]:
            continue
        rows.append({
            "line": ln, "case_id": cells[0], "priority": cells[1], "kind": cells[2], "title": cells[3],
            "precondition": cells[4], "method_url": cells[5], "headers": cells[6], "payload": cells[7],
            "steps": cells[8], "expected_http": cells[9], "expected_code": cells[10],
            "assertions": cells[11], "cleanup": cells[12], "notes": cells[13],
        })
    return rows

class Violation:
    def __init__(self, rule: str, severity: str, case_id: str, field: str, message: str):
        self.rule, self.severity, self.case_id, self.field, self.message = rule, severity, case_id, field, message

def run_rules(rows: list[dict[str, Any]], catalog: set[str] | None = None,
              domain_dict: set[str] | None = None, req_ids: set[str] | None = None) -> list[Violation]:
    v: list[Violation] = []
    titles = [r["title"] for r in rows]
    seen_id: dict[str, int] = {}
    id_counts: dict[str, int] = {}
    for r in rows:
        id_counts[r["case_id"]] = id_counts.get(r["case_id"], 0) + 1
    model_global = endpoint_model()
    for r in rows:
        cid, t = r["case_id"], r["title"]
        # R01 三段式
        if t.count("_") != 2 or any(not s.strip() for s in t.split("_")):
            v.append(Violation("R01", "fail", cid, "标题", f"非三段式（下划线数={t.count('_')}）：{t[:50]}"))
        # R03 禁用词
        m = BANNED_TITLE.search(t)
        if m:
            v.append(Violation("R03", "fail", cid, "标题", f"含禁用词「{m.group(0)}」：{t[:50]}"))
        # R04 稳定ID格式
        if not (re.match(r"^BUS-[A-Z]+-\d{3}$", cid) or re.match(r"^[A-Z0-9_]+-\d{3}$", cid)):
            v.append(Violation("R04", "fail", cid, "用例ID", f"ID格式不合法：{cid}"))
        # R06 预期确定性
        for f in ("expected_http", "expected_code", "assertions"):
            if VAGUE_EXPECT.search(r[f]):
                v.append(Violation("R06", "fail", cid, f, f"预期含模糊表述：{r[f][:60]}"))
        # R07 必填列
        for f in ("priority", "kind", "title", "precondition", "method_url", "headers", "payload",
                  "steps", "expected_http", "expected_code", "assertions", "cleanup"):
            if not r[f].strip():
                v.append(Violation("R07", "fail", cid, f, "必填列为空"))
        # R08 清理有效性（"无需清理/无（只读…）"为合法值；写操作需动作词）
        c = r["cleanup"]
        if c and not (re.search(r"无需清理", c) or re.search(r"^无[（(].*(只读|不产生).*[）)]", c)
                      or any(a in c for a in CLEAN_ACTIONS)):
            v.append(Violation("R08", "fail", cid, "清理", f"写操作清理无动作词：{c[:40]}"))
        # R09 占位符（`<运行时/管理员/普通用户 token>` 为框架约定，豁免）
        for f in ("payload", "headers"):
            for pm in PLACEHOLDER.finditer(r[f]):
                if TOKEN_CONVENTION.match(pm.group(0)):
                    continue
                v.append(Violation("R09", "warn", cid, f, f"疑似占位符：{pm.group(0)[:40]}"))
        # R10 固定ID
        arr = re.search(r"\[\s*\d+\s*[,]?[^\]]{0,20}\]", r["payload"])
        if arr and "先调用" not in r["payload"] and "示例" not in r["payload"]:
            v.append(Violation("R10", "warn", cid, "payload",
                               f"请求体含裸数字数组（疑似固定数据库ID）：{arr.group(0)[:24]}"))
        pm = FIXED_ID.search(r["payload"])
        if pm and "示例" not in r["payload"]:
            v.append(Violation("R10", "warn", cid, "payload", f"疑似固定数据库ID：{pm.group(0)}"))
        # R11 环境硬编码
        for f in ("precondition", "headers", "steps"):
            hm = HARDCODE_ENV.search(r[f])
            if hm:
                v.append(Violation("R11", "fail", cid, f, f"环境硬编码：{hm.group(0)}"))
        # R32 清理动作必须可映射到真实端点（防"删除不存在的资源"）
        if model_global and "调用" in c:
            for ref in re.findall(r"(DELETE|POST|PUT)\s+(/\S+)", c):
                key = f"{ref[0]} {_norm_ref(ref[1])}"
                if key not in model_global:
                    v.append(Violation("R32", "fail", cid, "清理",
                                       f"清理引用的端点不存在：{key}"))
        # R33 请求体必须覆盖源码声明的必填字段（可解析为 JSON 时校验）
        bm = re.search(r"请求体：(\{.*\})", r["payload"])
        mu2 = re.match(r"^(GET|POST|PUT|DELETE|PATCH)\s+(/.*)$", r["method_url"])
        entry = model_global.get(f"{mu2.group(1)} {norm_path(mu2.group(2))}") if mu2 else None
        if bm and entry and entry.get("dto_required"):
            try:
                body = __import__("json").loads(bm.group(1))
            except ValueError:
                body = None
            if isinstance(body, dict):
                missing = [k for k in entry["dto_required"] if k not in body]
                if missing:
                    v.append(Violation("R33", "fail", cid, "payload",
                                       f"请求体缺少源码必填字段：{'、'.join(missing)}"))
        # R35 幂等用例必须标注策略的源码出处（防"默认策略"无依据漂移）
        if r["kind"] == "幂等" and "幂等策略：源码" not in r["notes"]:
            v.append(Violation("R35", "fail", cid, "备注",
                               "幂等用例未标注策略源码出处（应为「幂等策略：源码 <文件:行> <依据>」）"))
        # R34 请求体**顶层**字段名合法性（防内部标记 @xxx / 中文键名泄漏）。
        # 只查顶层：specs 之类映射的键是业务数据（如 {"规格":"标准"}），中文合法。
        if bm:
            try:
                parsed = __import__("json").loads(bm.group(1))
            except ValueError:
                parsed = None
            keys = list(parsed.keys()) if isinstance(parsed, dict) else \
                re.findall(r'"([^"]+)"\s*:', bm.group(1))
            bad_keys = [k for k in keys if k.startswith("@") or re.search(r"[\u4e00-\u9fa5]", k)]
            if bad_keys:
                v.append(Violation("R34", "fail", cid, "payload",
                                   f"请求体顶层字段名非法：{'、'.join(bad_keys[:3])}"))
        # R12 数据准备启发式
        if re.search(r"已有|存在|有.*(购物车|地址|订单|售后|账号|收藏)", r["precondition"]) and not re.search(r"准备方式|准备：|fixture|由.*生成|预置", r["precondition"]):
            v.append(Violation("R12", "warn", cid, "前置条件", "疑似数据依赖未写准备方式"))
        # R13 方法路径（业务用例允许 “METHOD/METHOD /path” 多方法形式）
        mu = r["method_url"]
        mm = re.match(r"^((?:GET|POST|PUT|DELETE|PATCH)(?:/(?:GET|POST|PUT|DELETE|PATCH))*)\s+(/.*)$", mu)
        if not mm:
            v.append(Violation("R13", "fail", cid, "请求方法", f"格式错误：{mu[:40]}"))
        elif not any(mm.group(2).startswith(p) for p in PATH_PREFIXES) and mm.group(2) != "/":
            v.append(Violation("R13", "fail", cid, "请求方法", f"非 /admin 或 /api 路径：{mm.group(2)[:40]}"))
        # R17 优先级
        if r["priority"] not in ("P0", "P1", "P2", "P3"):
            v.append(Violation("R17", "fail", cid, "优先级", f"非法优先级：{r['priority']}"))
        # R18 断言丰富度
        a = r["assertions"]
        if len(a) < 10 or not re.search(r"=|包含|非空|一致|不含|为", a):
            v.append(Violation("R18", "warn", cid, "断言", f"断言过弱：{a[:40]}"))
        # R21 请求体完备性：body 接口（POST/PUT，非无体动作）不得写"无请求体"（缺参场景除外）
        mm2 = re.match(r"^(GET|POST|PUT|DELETE|PATCH)\s+(/.*)$", r["method_url"])
        if mm2 and mm2.group(1) in ("POST", "PUT"):
            scen2 = r["title"].split("_")[1] if r["title"].count("_") >= 2 else ""
            if ("无请求体" in r["payload"] and "缺少" not in scen2
                    and f"{mm2.group(1)} {norm_path(mm2.group(2))}" not in BODYLESS_ACTIONS):
                v.append(Violation("R21", "fail", cid, "payload", f"body 接口却写'无请求体'：{r['method_url'][:40]}"))
        # R22 步骤区陈旧模板措辞
        for ph in STALE_STEP_PHRASES:
            if ph in r["steps"]:
                v.append(Violation("R22", "fail", cid, "steps", f"步骤区残留旧措辞「{ph[:20]}…」"))
        # R23 请求头 token 角色与端点匹配
        if mm2 and "Authorization" in r["headers"] and "不含" not in r["headers"]:
            is_admin = mm2.group(2).startswith("/admin/")
            if is_admin and "管理员" not in r["headers"]:
                v.append(Violation("R23", "fail", cid, "headers", "管理端接口请求头未标注管理员 token"))
            if not is_admin and "管理员" in r["headers"]:
                v.append(Violation("R23", "fail", cid, "headers", "非管理端接口请求头误标管理员 token"))
        # R24 派生一致性：步骤区的"请求/请求头/参数"必须是字段的程序化副本（禁止手工漂移）
        for seg in (f"请求：{r['method_url']}", f"请求头：{r['headers']}", f"参数：{r['payload']}"):
            if seg not in r["steps"]:
                v.append(Violation("R24", "fail", cid, "steps", f"步骤区与字段不一致，缺片段：{seg[:45]}"))
                break
        # R25 场景-操作一致性：缺参场景不得出现"获取被缺参数"的动作；正常路径参数场景必须给定取值方式
        if mm2:
            scen3 = r["title"].split("_")[1] if r["title"].count("_") >= 2 else ""
            has_path_param = "{" in mm2.group(2)
            if "缺少" in scen3 and "取一条记录的 id" in r["payload"]:
                v.append(Violation("R25", "fail", cid, "payload", "缺参场景却写入了获取参数的动作（自相矛盾）"))
            if "缺少" not in scen3 and has_path_param and not re.search(r"取|获取|动态", r["payload"]):
                v.append(Violation("R25", "fail", cid, "payload", "路径参数用例未给出取值方式"))
        # R26 参数适用性：无路径参数的接口不得出现"路径参数"说明
        if mm2 and "{" not in mm2.group(2) and "路径参数" in r["payload"]:
            v.append(Violation("R26", "fail", cid, "payload", "接口无路径参数却写了路径参数说明"))
        # R14 路径存在于接口文档清单（有文档时启用）
        if mm2 and catalog:
            for method in mm2.group(1).split("/"):
                key = f"{method} {norm_path(mm2.group(2))}"
                if key not in catalog:
                    v.append(Violation("R14", "warn", cid, "请求方法", f"接口文档中未找到：{key}"))
                    break
        # R27 领域词典：预期/断言中的大写枚举词必须来自领域词典（C8-lite）
        if domain_dict:
            for f in ("expected_code", "assertions"):
                for tok in set(ENUM_TOKEN.findall(r[f])):
                    if tok in ENUM_STOPWORDS or tok in domain_dict:
                        continue
                    v.append(Violation("R27", "fail", cid, f, f"枚举词不在领域词典：{tok}"))
        # R29 正向断言数据层锚点（rule.md §3.6 v2.6）：正向必须可机器判定到数据层
        # 尚品汇：失败族走 HTTP 200 + body 数值码，故正向判据用「业务码为 0」而非「HTTP=200」
        a = r["assertions"]
        if SUCCESS_CODE.match(r["expected_code"].strip()):
            # 剥离协议头部（新式 `HTTP=…；code=…；` 与旧式 `HTTP 状态码为…；响应业务码/错误码符合…；`），
            # 锚点必须出现在业务断言正文中，防止样板句被误判为锚点。
            a_body = re.sub(r"^(?:(?:HTTP|响应业务码/错误码符合)[^；]*；\s*)+", "", a).strip()
            if not (R29_DATA_ANCHOR.search(a) or R29_FIELD_ANCHOR.search(a_body)):
                v.append(Violation("R29", "fail", cid, "断言",
                                   "正向断言缺少数据层锚点（data.路径 / 回查指令 / 具名字段判定）"))
            if R29_VAGUE_COMPARE.search(a) and not R29_DATA_ANCHOR.search(a):
                v.append(Violation("R29", "fail", cid, "断言",
                                   "模糊比较（结果一致/正确）未写回查对象与字段"))
        # R30 预期业务码可判定性（rule.md §3.6 v2.6）：禁 HTTP 码混入业务码槽
        # 尚品汇：成功码=0；失败码=词典词条（BizException 走 HTTP 200）或 无（框架级）
        ec = r["expected_code"].strip()
        if not (SUCCESS_CODE.match(ec) or re.match(r"^无([（(].*[）)])?$", ec)
                or (domain_dict and re.match(r"^[A-Z][A-Z_]{3,}([（(].*[）)])?$", ec)
                    and ENUM_TOKEN.match(ec) and ENUM_TOKEN.match(ec).group(0) in domain_dict)
                or re.match(r"^[45]\d{2}[（(][^）)]*body[^）)]*[）)]$", ec)):
            v.append(Violation("R30", "fail", cid, "预期业务码",
                               f"业务码不可判定（须为 0 或词典词条或『无（原因）』）：{ec[:30]}"))
        # R31 回查地址必须真实存在（防"断言指向不存在的接口"造成假缺陷）
        model = endpoint_model()
        if model:
            for ref in re.findall(r"回查\s+(GET|POST|PUT|DELETE|PATCH)\s+(\S+)", a):
                key = f"{ref[0]} {_norm_ref(ref[1])}"
                if key not in model:
                    v.append(Violation("R31", "fail", cid, "断言",
                                       f"回查地址在接口清单中不存在：{key}"))
        m30 = re.search(r"错误码(符合|为|=)\s*(HTTP\s*)?(200|201|400|401|403|404|409|500)\b", a)
        if m30:
            v.append(Violation("R30", "fail", cid, "断言",
                               f"HTTP 码混入业务码槽位：{m30.group(0)[:30]}"))
    # R02 标题唯一
    tcount: dict[str, int] = {}
    for t in titles:
        tcount[t] = tcount.get(t, 0) + 1
    for t, n in tcount.items():
        if n > 1:
            for r in rows:
                if r["title"] == t:
                    v.append(Violation("R02", "fail", r["case_id"], "标题", f"重名 x{n}：{t[:40]}"))
    # R05 ID唯一
    for cid, n in id_counts.items():
        if n > 1:
            v.append(Violation("R05", "fail", cid, "用例ID", f"ID重复 x{n}"))
    # R16 优先级分布
    p0 = sum(1 for r in rows if r["priority"] == "P0")
    if rows and p0 / len(rows) > 0.4:
        v.append(Violation("R16", "warn", "-", "优先级", f"P0 占比 {p0}/{len(rows)} = {p0*100//len(rows)}% (>40% 通胀)"))
    # R15 覆盖矩阵
    v.extend(coverage_matrix(rows))
    # R28 需求覆盖报告：需求文档中未被任何用例引用的需求（追溯守卫）
    if req_ids:
        blob = [(r["notes"] + r["assertions"] + r["steps"]) for r in rows]
        for req in sorted(req_ids):
            if not any(req in b for b in blob):
                v.append(Violation("R28", "warn", "-", "需求追溯", f"需求 {req} 无任何用例引用"))
    # R36 词典反向检查（元气购版 §6-④）：词典里定义了、但没有任何用例触发过的词条。
    # 反向漏检 = 词典写了却没人测，是最容易被"正向合法"掩盖的覆盖缺口。
    # 豁免：词典「反向检查豁免」行列出的词条（如系统级 SYSTEM_ERROR）。
    if domain_dict:
        blob = " ".join(" ".join(str(r.get(k, "")) for k in
                                 ("case_id", "title", "precondition", "method_url", "headers", "payload",
                                  "steps", "expected_code", "assertions", "cleanup", "notes"))
                        for r in rows)
        # 用词边界匹配（而非 ENUM_TOKEN 的 4 字符下限），否则 ON/OFF 这类短词条永远算"未触发"
        triggered = {w for w in domain_dict if re.search(rf"\b{re.escape(w)}\b", blob)}
        for w in sorted(set(domain_dict) - triggered - dict_exempt()):
            v.append(Violation("R36", "warn", "-", "词典", f"词条定义了但无任何用例触发：{w}"))
    return v


_DICT_EXEMPT: set[str] | None = None


def dict_exempt() -> set[str]:
    """词典中「反向检查豁免」行声明的词条（不参与 R36 反向检查）。"""
    global _DICT_EXEMPT
    if _DICT_EXEMPT is None:
        try:
            text = DEFAULT_DICT.read_text(encoding="utf-8")
        except OSError:
            text = ""
        m = re.search(r"反向检查豁免[:：]\s*(.+)", text)
        _DICT_EXEMPT = set(re.findall(r"[A-Z][A-Z_]{2,}", m.group(1))) if m else set()
    return _DICT_EXEMPT

def norm_path(p: str) -> str:
    return re.sub(r"\{[^}]+\}", "{id}", p)

def parse_endpoint_catalog(doc_path: Path) -> set[str]:
    """从接口文档提取“METHOD 归一化路径”全集（支持代码块与表格两种写法，/api/v1→/v1）。"""
    if not doc_path.exists():
        return set()
    text = doc_path.read_text(encoding="utf-8")
    catalog: set[str] = set()
    pattern = re.compile(r"(GET|POST|PUT|DELETE|PATCH)\s*\|?\s*`?(/(?:admin|api)/[^\s`|,，)）]+)")
    for m in pattern.finditer(text):
        path = m.group(2).rstrip("。;；")
        if path.startswith(("/admin/", "/api/")):
            catalog.add(f"{m.group(1)} {norm_path(path)}")
    return catalog

def parse_req_ids(doc_path: Path) -> set[str]:
    """从需求文档提取需求编号（尚品汇前缀：PRD/RBAC/STK/ME/MALL/CFG/CUS/INF）。"""
    if not doc_path.exists():
        return set()
    text = doc_path.read_text(encoding="utf-8")
    return {m.group(0).replace(" ", "") for m in re.finditer(r"\b(?:PRD|RBAC|STK|ME|MALL|CFG|CUS|INF)-\d{2}\b", text)}

def is_auth_exempt(path: str) -> bool:
    return any(re.match(pat, path) for pat in AUTH_EXEMPT)

_ENDPOINT_MODEL: dict[str, dict] | None = None


def endpoint_model() -> dict[str, dict]:
    """{f"{METHOD} {归一化路径}": 端点事实}；模型缺失时返回空字典。"""
    global _ENDPOINT_MODEL
    if _ENDPOINT_MODEL is None:
        model = Path(__file__).resolve().parents[1] / "artifacts" / "endpoint-model.json"
        try:
            eps = json.loads(model.read_text(encoding="utf-8")).get("endpoints", [])
        except (FileNotFoundError, ValueError, OSError):
            eps = []
        _ENDPOINT_MODEL = {f"{e['method']} {norm_path(e['path'])}": e for e in eps}
    return _ENDPOINT_MODEL


def _norm_ref(text: str) -> str:
    return norm_path(text.split("?")[0].rstrip("；,，。"))


def param_enforced_endpoints() -> set[str] | None:
    """从源码派生的端点模型读取「参数维度是否适用」（build_api_doc.py 生成）。
    模型缺失时返回 None，回退到旧的启发式判定。"""
    model = Path(__file__).resolve().parents[1] / "artifacts" / "endpoint-model.json"
    if not model.exists():
        return None
    import json as _json
    try:
        eps = _json.loads(model.read_text(encoding="utf-8")).get("endpoints", [])
    except (ValueError, OSError):
        return None
    return {f"{e['method']} {norm_path(e['path'])}" for e in eps if e.get("param_enforced")}


def coverage_matrix(rows: list[dict[str, Any]]) -> list[Violation]:
    v: list[Violation] = []
    # 无参数可缺的接口（无路径参数、无必填 body）——"参数"维度不适用
    bodyless = {"POST /admin/orders/{id}/ship", "POST /api/orders/{id}/cancel",
                "POST /api/orders/{id}/confirm", "POST /admin/auth/logout",
                "POST /api/auth/logout", "POST /api/cart/clear-invalid"}
    enforced = param_enforced_endpoints()
    groups: dict[str, dict[str, Any]] = {}
    for r in rows:
        mm = re.match(r"^((?:GET|POST|PUT|DELETE|PATCH)(?:/(?:GET|POST|PUT|DELETE|PATCH))*)\s+(/.*)$", r["method_url"])
        if not mm:
            continue
        method = mm.group(1).split("/")[0]
        key = f"{method} {norm_path(mm.group(2))}"
        g = groups.setdefault(key, {"path": mm.group(2), "method": method, "dims": set()})
        t = r["title"]
        if "典型请求" in t:
            g["dims"].add("正向")
        if "未带Token" in t or "无效Token" in t:
            g["dims"].add("鉴权")
        if "缺少" in t:
            g["dims"].add("参数")
        if "重复" in t:
            g["dims"].add("幂等")
        if "分页" in t:
            g["dims"].add("边界")
    for key, g in sorted(groups.items()):
        has_path_param = "{" in g["path"]
        applicable = {
            "正向": True,
            "鉴权": not is_auth_exempt(g["path"]),
            # 参数维度仅在「有路径参数」或「源码有显式参数校验」时适用（源码派生模型）
            "参数": (has_path_param or key in enforced) if enforced is not None else
                    (has_path_param or (g["method"] in ("POST", "PUT") and key not in bodyless)),
            "幂等": g["method"] in ("POST", "PUT", "DELETE"),
            "边界": False,  # 仅当该接口已有分页用例时才要求
        }
        if "边界" in g["dims"]:
            applicable["边界"] = True
        missing = [d for d, need in applicable.items() if need and d not in g["dims"]]
        if missing:
            v.append(Violation("R15", "fail", key, "覆盖矩阵", f"缺维度：{'、'.join(missing)}"))
    return v

def online_check(rows: list[dict[str, Any]], base: str) -> list[Violation]:
    v: list[Violation] = []
    try:
        import requests
    except ImportError:
        return [Violation("R19", "warn", "-", "在线", "requests 未安装，跳过在线核对")]
    user, pwd = os.getenv("ZENTAO_USER"), os.getenv("ZENTAO_PASSWORD")
    if not user or not pwd:
        return [Violation("R19", "warn", "-", "在线", "未设置 ZENTAO_USER/ZENTAO_PASSWORD，跳过在线核对")]
    s = requests.Session()
    page = s.get(base + "/", timeout=20)
    rand = s.get(base + "/user-refreshRandom.html", timeout=20).text.strip()
    pw = hashlib.md5((hashlib.md5(pwd.encode()).hexdigest() + rand).encode()).hexdigest()
    s.post(page.url, data={"account": user, "password": pw, "passwordStrength": 1, "referer": "/",
                           "verifyRand": rand, "keepLogin": 1, "captcha": ""},
           headers={"X-Requested-With": "XMLHttpRequest", "Referer": page.url}, timeout=20)
    product_id = os.getenv("ZENTAO_PRODUCT_ID", "1")
    r = s.get(base + f"/testcase-browse-{product_id}--all-0--sort_asc,id_desc-0-1000-1-0-testcase-0.html", timeout=30)
    import html as H
    t = H.unescape(r.text)
    remote = {}
    for ch in t.split('"id":"case_')[1:]:
        mm = re.match(r"(\d+)", ch)
        if not mm:
            continue
        tm = re.search(r'"title":"([^"]*)"', ch)
        km = re.search(r'"keywords":"([^"]*)"', ch)
        if tm:
            remote[int(mm.group(1))] = {"title": tm.group(1), "keywords": km.group(1) if km else ""}
    def norm_title(x: str) -> str:
        # 禅道存储时把 "/" 转义为 "\/"，比对前先归一化
        return x.replace("\\/", "/").strip()

    local = {r["case_id"]: norm_title(r["title"]) for r in rows}
    found = {}
    for cid, info in remote.items():
        km = re.match(r"API-AUTO,(.+)$", info["keywords"])
        if km:
            found[km.group(1).strip()] = info
    for cid, title in local.items():
        info = found.get(cid)
        if not info:
            v.append(Violation("R19", "fail", cid, "在线", "禅道中找不到该稳定ID（keyword缺失）"))
            continue
        if norm_title(info["title"]) != title:
            v.append(Violation("R20", "fail", cid, "在线", f"标题不一致：禅道《{norm_title(info['title'])[:40]}》vs 清单《{title[:40]}》"))
    return v

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", type=Path, default=DEFAULT_INV)
    ap.add_argument("--doc", type=Path, default=DEFAULT_DOC)
    ap.add_argument("--online", action="store_true")
    ap.add_argument("--base", default=os.getenv("ZENTAO_BASE_URL", "http://111.170.33.2:60082"))
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--mdout", type=Path, default=None)
    ap.add_argument("--jsonout", type=Path, default=None)
    args = ap.parse_args()
    if not args.inventory.exists():
        print(f"清单不存在: {args.inventory}")
        return 2
    rows = parse_inventory(args.inventory)
    if not rows:
        print("清单解析为空——检查格式")
        return 2
    catalog = parse_endpoint_catalog(args.doc)
    domain_dict = parse_domain_dict(DEFAULT_DICT)
    req_ids = parse_req_ids(_governance_doc("需求文档.md", "REQ_DOC_PATH"))
    missing = []
    if not catalog:
        missing.append(f"接口文档（R14 依赖）：{args.doc}")
    if not domain_dict:
        missing.append(f"领域词典词表行（R27 依赖）：{DEFAULT_DICT}")
    if not req_ids:
        missing.append("需求文档编号（R28 依赖）：docs/测试资料/需求文档.md")
    if not endpoint_model():
        missing.append("端点事实模型（R15/R31/R32/R33 依赖）：artifacts/endpoint-model.json")
    if missing:
        # 依赖缺失时规则会静默失效（报 fail 0 但什么都没查）→ 视为输入错误，拒绝出具门禁结论
        print("✘ 门禁依赖缺失，拒绝给出结论（防假绿）：")
        for x in missing:
            print("   -", x)
        return 2
    violations = run_rules(rows, catalog, domain_dict, req_ids)
    print(f"规则启用：接口清单 {len(catalog)} 条（R14）、词典 {len(domain_dict)} 词（R27）、"
          f"需求 {len(req_ids)} 条（R28）、端点模型 {len(endpoint_model())} 条（R15/R31/R32/R33）")
    if args.online:
        violations.extend(online_check(rows, args.base))
    fails = [x for x in violations if x.severity == "fail"]
    warns = [x for x in violations if x.severity == "warn"]
    print(f"用例 {len(rows)} 条 | fail {len(fails)} | warn {len(warns)}")
    from collections import Counter
    for rule, n in Counter(x.rule for x in fails).most_common():
        print(f"  [fail] {rule}: {n}")
    for rule, n in Counter(x.rule for x in warns).most_common():
        print(f"  [warn] {rule}: {n}")
    report = {"total": len(rows), "fails": len(fails), "warns": len(warns),
              "violations": [vars(x) for x in violations]}
    # R36 词典反向检查产物：真覆盖缺口清单（供下一轮补用例/补断言）
    gaps = [x.message.split("：", 1)[-1] for x in warns if x.rule == "R36"]
    gap_file = Path(__file__).resolve().parents[1] / "artifacts" / "coverage-gap.json"
    gap_file.parent.mkdir(parents=True, exist_ok=True)
    gap_file.write_text(json.dumps({
        "generated_from": str(args.inventory),
        "total_cases": len(rows), "untested_terms": gaps,
        "note": "词典定义了但无任何用例触发的词条（记法约定类已由词典『反向检查豁免』行排除）",
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    if args.jsonout:
        args.jsonout.parent.mkdir(parents=True, exist_ok=True)
        args.jsonout.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    if args.mdout:
        lines = [f"# 用例 Lint 报告（{len(rows)} 条，fail {len(fails)} / warn {len(warns)}）", ""]
        lines.append("| 规则 | 级别 | 用例 | 字段 | 问题 |")
        lines.append("|---|---|---|---|---|")
        for x in violations:
            lines.append(f"| {x.rule} | {x.severity} | {x.case_id} | {x.field} | {x.message.replace('|', '/')} |")
        args.mdout.parent.mkdir(parents=True, exist_ok=True)
        args.mdout.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if fails or (args.strict and warns):
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
