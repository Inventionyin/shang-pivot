# -*- coding: utf-8 -*-
"""清单驱动的契约执行（参数化自 test_cases_inventory.md）。

运行：
  ADMIN_BASE_URL=http://YOUR_SERVER_IP:60085 MALL_BASE_URL=http://YOUR_SERVER_IP:60084 \
  APP_TEST_ACCOUNT=13800000001 APP_TEST_SMS_CODE=123456 \
  ADMIN_TEST_ACCOUNT=admin ADMIN_TEST_PASSWORD='<运行时输入>' \
  python3 -m pytest tests/contract/test_inventory_driven.py -q --alluredir artifacts/allure

约束（首轮）：
- 只执行**无副作用**用例：GET 正向、鉴权、参数、边界。写操作用例（POST/PUT/DELETE 正向与幂等）
  需要"专属造数 + 用后清理"链路，首轮未接，按 skip 处理并给出原因（绝不静默丢弃）。
- 每条用例 = 一个 pytest 参数 = 禅道稳定 ID，便于结果回写。
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))

from core import assert_engine  # noqa: E402
from core.schema_validator import SchemaValidator, summarize as schema_summarize  # noqa: E402
from core.shang_driver import (  # noqa: E402
    Client, Resolver, build_request, check, endpoint_ops, family_of, login_admin,
    login_customer, parse_row, parse_body_text, role_of,
)

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None

# 契约校验层（L3 结构 + L3 Long 序列化 + L4 traceId）。默认开启，设 SCHEMA_CHECK=0 关闭。
SCHEMA = SchemaValidator(enabled=os.getenv("SCHEMA_CHECK", "1") == "1")
SCHEMA_VIOLATIONS: list[str] = []

EXECUTE_WRITES = os.getenv("EXECUTE_WRITES", "0") == "1"
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


INCLUDE_BUS = os.getenv("INCLUDE_BUS", "1") == "1"


def _load_rows() -> tuple[list[dict], dict]:
    """返回 (用例, 造数模板)。造数模板 = 各资源根对应的 POST 正向用例请求体。

    INCLUDE_BUS=1 时把 BUS-* 业务链路用例一并纳入执行（同一套取值解析与断言引擎）。
    """
    import case_lint as L
    raw = [parse_row(r) for r in L.parse_inventory(L.DEFAULT_INV)
           if INCLUDE_BUS or not r["case_id"].startswith("BUS")]
    tmpl = {}
    for c in raw:
        if c["method"] == "POST" and c["kind"] == "正向":
            tmpl[c["path"]] = c
    return raw, tmpl


CASES, CREATE_TMPL = _load_rows()
OPS = endpoint_ops()
# 无 DELETE 的资源：指定替代清理动作
# 替代清理：(方法, 路径, 可选请求体)
ALT_CLEANUP = {"/api/orders": ("POST", "/api/orders/{id}/cancel", None)}
AFTER_SALE_CLEANUP = ("POST", "/admin/after-sales/{id}/audit", {"approved": False, "remark": "自动化测试清理"})
# 清理失败必须可见（否则会静默留下测试数据）
LEAKS: list[str] = []
UNCHECKED: list[str] = []


def _get_nested(obj, dotted, default=None):
    cur = obj
    for seg in dotted.split("."):
        if isinstance(cur, dict):
            cur = cur.get(seg)
        else:
            return default
    return cur if cur is not None else default


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    if SCHEMA_VIOLATIONS:
        print(f"\n=== 契约校验违规 {len(SCHEMA_VIOLATIONS)} 条 ===")
        for v in SCHEMA_VIOLATIONS[:25]:
            print("  " + v)
        if len(SCHEMA_VIOLATIONS) > 25:
            print(f"  … 其余 {len(SCHEMA_VIOLATIONS)-25} 条见 allure 报告")
    if UNCHECKED:
        import collections
        c = collections.Counter(x.split("｜", 1)[1][:34] for x in UNCHECKED)
        terminalreporter.write_sep("-", f"未机检断言 {len(UNCHECKED)} 项（Top 5 句式）")
        for k, v in c.most_common(5):
            terminalreporter.write_line(f"  {v:4} × {k}")
    if LEAKS:
        terminalreporter.write_sep("!", f"清理失败 {len(LEAKS)} 条（可能残留测试数据）")
        for x in LEAKS:
            terminalreporter.write_line("  " + x)


NO_CLEANUP_CREATE = {"/admin/warehouses"}  # 无 DELETE 接口，创建后无法清理 → 不执行


ENV_GAP_PATHS = ("/api/files/", "/admin/files/")   # 对象存储未部署 → 环境缺口，下轮重跑


def _skip_reason(case: dict) -> str:
    if any(k in case["path"] for k in ENV_GAP_PATHS):
        return "环境缺口：对象存储未部署（本轮不部署，下轮重跑）"
    if "/api/addresses" in case["path"]:
        return ("地址模块 list/delete 均返回 500（缺陷 D-14/D-15），执行会产生无法清理的数据，暂不执行")
    if "files/confirm" in case["path"]:
        return "依赖真实上传产物（objectKey 需由 MinIO 上传产生），环境未部署 MinIO"
    if "multipart/form-data" in case["payload"]:
        return "multipart 上传用例：需真实文件与对象存储校验（环境未部署 MinIO）"
    if (case["method"] == "POST" and case["path"] in NO_CLEANUP_CREATE
            and case["kind"] in ("正向", "幂等")):
        return "该资源无删除接口，创建后无法清理（避免污染环境，跳过；仅跳过会真正写入的用例）"
    if case["method"] in WRITE_METHODS:
        if case["method"] == "POST":
            root = case["path"]
        else:
            fam = family_of(case["path"], OPS)
            if not fam:
                return "无对应造数/清理链路（动作类或需链式前置）"
            root = fam["root"]
        if root not in CREATE_TMPL and case["method"] == "POST" and root in ALT_CLEANUP:
            return ""
        if root not in CREATE_TMPL:
            return "该资源无 POST 造数入口，未接链式前置"
    return ""


def _make(client, res, admin, user, case, create_case=None):
    """造一条专属记录；返回 (新建 id, 清理动作 或 None, 失败原因)。"""
    if create_case is None:
        return None, None, ""
    req, err = build_request(create_case, res, admin, user)
    if err:
        return None, None, f"造数请求构造失败：{err}"
    st, body, _ = client.call(req["method"], req["path"], token=req["token"], body=req["body"])
    if st != 200 or body.get("code") != 0:
        return None, None, f"造数失败：HTTP {st} code={body.get('code')} {str(body.get('msg'))[:60]}"
    new_id = body.get("data") if not isinstance(body.get("data"), dict) else body["data"].get("id")
    if new_id is None:
        return None, None, "造数响应未返回新记录 id"
    path = case["path"]
    if case["method"] == "POST":
        family_path = re.sub(r"\{[^}]+\}", str(new_id), path + "/{id}")
        cleanup = ("DELETE", family_path) if f"DELETE {path + '/{id}'}" in OPS else None
        if cleanup is None and path in ALT_CLEANUP:
            cleanup = (ALT_CLEANUP[path][0], ALT_CLEANUP[path][1].replace("{id}", str(new_id)))
    else:
        cleanup = ("DELETE", re.sub(r"\{[^}]+\}", str(new_id), path))
    return new_id, cleanup, ""


@pytest.fixture(scope="session")
def ctx():
    c = Client()
    admin, admin_r = login_admin(c)
    user, user_r = login_customer(c)
    if not admin:
        pytest.exit("管理员登录失败：检查 ADMIN_TEST_ACCOUNT / ADMIN_TEST_PASSWORD", returncode=3)
    rt = {"admin": admin_r, "user": user_r}
    yield {"client": c, "admin": admin, "user": user,
           "resolver": Resolver(c, admin, user, rt), "rt": rt}
    c.close()


@pytest.mark.parametrize("case", [
    pytest.param(c, id=c["case_id"], marks=pytest.mark.zt_case(c["case_id"])) for c in CASES
])
def test_inventory_case(ctx, case):
    cid = case["case_id"]
    if allure:
        allure.dynamic.title(case["title"])
        allure.dynamic.description(f"{case['method']} {case['path']}\n\n{case['assertions']}")
        allure.dynamic.severity({"P0": "blocker", "P1": "critical", "P2": "normal"}.get(case["priority"], "normal"))
        allure.dynamic.tag(case["kind"], case["priority"])

    reason = _skip_reason(case)
    if reason:
        pytest.skip(reason)

    client: Client = ctx["client"]
    created_id = cleanup = None
    ok = detail = None
    req = {}
    # 写操作用例默认不执行：本套件的写用例会**真实创建记录**并依赖清理链路，
    # 而清理并非万无一失（实测一次全量运行可在 brands/categories/depts/dicts/permissions
    # 各残留约 20 条）。原代码声明了 EXECUTE_WRITES 却从未使用，属文档与实现脱节，此处补上。
    if case["method"] in WRITE_METHODS and not EXECUTE_WRITES:
        pytest.skip("写操作用例默认跳过；确需执行请设 EXECUTE_WRITES=1（会创建并尝试清理真实数据）")

    http = body = url = None
    # 主响应与响应头要在 try 之前占位：用例可能在主调用前就 skip（取值约定未解析等），
    # 而 finally 里会引用它们——只在主调用后赋值会抛 UnboundLocalError，把跳过记成 broken。
    main_headers: dict = {}
    main_body = main_http = None
    pre_total = pre_status = None
    try:
        # 写入类：先造一条**专属记录**，用例只作用于它
        if case["method"] in ("PUT", "PATCH", "DELETE") and "不提供路径参数" not in case["payload"]:
            fam = family_of(case["path"], OPS)
            created_id, cleanup, mk_err = _make(client, ctx["resolver"], ctx["admin"], ctx["user"], case,
                                                create_case=CREATE_TMPL.get(fam["root"]) if fam else None)
            if mk_err:
                pytest.skip(mk_err)
        patched = dict(case)
        if created_id is not None:
            patched["path"] = re.sub(r"\{[^}]+\}", str(created_id), case["path"])
        req, err = build_request(patched, ctx["resolver"], ctx["admin"], ctx["user"])
        raw_body = parse_body_text(case["payload"])
        clean = False
        if raw_body:
            try:
                json.loads(raw_body)
                clean = True
            except ValueError:
                clean = False
        prose = (not err) and cid.startswith("BUS-") and not clean and bool(raw_body)
        if cid.startswith("BUS-") and (err or prose):
            # BUS 用例的散文式取值回落：借用同端点契约用例的机器可执行请求体
            tmpl = CREATE_TMPL.get(case["path"])
            if tmpl:
                patched["payload"] = tmpl["payload"]
                req, err = build_request(patched, ctx["resolver"], ctx["admin"], ctx["user"])
        if err:
            pytest.skip(f"取值约定未解析：{err}")

        http, body, url = client.call(req["method"], req["path"], token=req["token"],
                                      body=req["body"], params=req["params"])
        # 必须在此刻捕获主响应与响应头：后面的幂等二次调用、回查、以及 finally 里的清理
        # 都会覆盖 body 与 client.last_headers（曾因此把「清理 DELETE 的响应」当成主响应校验，
        # 造成 69 条误报）。
        main_headers = dict(getattr(client, "last_headers", {}) or {})
        main_body, main_http = body, http
        second = None
        if case["kind"] == "幂等":
            http2, body2, _ = client.call(req["method"], req["path"], token=req["token"],
                                          body=req["body"], params=req["params"])
            second = (http2, body2)
        # 创建类：用例自身的响应即新建记录，据其 id 清理
        if case["method"] == "POST":
            cleanup = []
            for resp in (body, second[1] if second else None):
                data = resp.get("data") if isinstance(resp, dict) else None
                new_id = data.get("id") if isinstance(data, dict) else data
                if new_id is None:
                    continue
                dpath = case["path"] + "/{id}"
                if f"DELETE {dpath}" in OPS:
                    cleanup.append(("DELETE", dpath.replace("{id}", str(new_id))))
                elif case["path"] in ALT_CLEANUP:
                    act = ALT_CLEANUP[case["path"]]
                    cleanup.append((act[0], act[1].replace("{id}", str(new_id)), act[2]))
                elif case["path"] == "/api/after-sales":   # 售后单不可删，由管理员驳回关闭
                    cleanup.append((AFTER_SALE_CLEANUP[0],
                                    AFTER_SALE_CLEANUP[1].replace("{id}", str(new_id)),
                                    AFTER_SALE_CLEANUP[2]))
        ok, detail = check(case, http, body, second)
        # —— 数据层断言引擎 ——
        if ok:
            tmpl, actual = case["path"], req["path"]
            vars_ = {}
            import re as _re
            pat = "^" + _re.sub(r"\{\w+\}", "([^/]+)", _re.escape(tmpl).replace("\\{", "{").replace("\\}", "}")) + "$"
            mm = _re.match(_re.sub(r"\\", "", pat), actual) or _re.match(_re.sub(r"\\", "", pat), actual.lstrip("/"))
            if mm:
                for name, val in zip(_re.findall(r"\{(\w+)\}", tmpl), mm.groups()):
                    vars_[name] = val
            submitted = req["body"] if isinstance(req["body"], dict) else {}

            def _call(path, method="GET"):
                # 回查目标可能是管理端接口（如 /admin/stocks），按路径选对应角色 token
                tk = ctx["admin"] if path.startswith("/admin/") else (req["token"] or ctx["user"])
                st, bd, _ = client.call(method, path, token=tk)
                return st, bd

            second_total = None
            if "两次响应的 data.total 一致" in case["assertions"]:
                st2, b2, _ = client.call(case["method"], req["path"], token=req["token"],
                                         params={"page": "1", "size": "5"})
                if st2 == 200 and isinstance(b2, dict):
                    second_total = (b2.get("data") or {}).get("total")
            vars_["_primary_body"] = body
            vars_["_second_total"] = second_total
            ok, detail, unchecked = assert_engine.evaluate(case, _call, vars_, submitted,
                                                           pre_total, pre_status,
                                                           case["kind"] == "幂等")
            if unchecked:
                UNCHECKED.extend(f"{case['case_id']}｜{u}" for u in unchecked)
            # ---------- 契约校验层：schema 结构 + Long 序列化 + traceId ----------
            # 用主响应（main_body/main_http/main_headers），不是 finally 里被覆盖的变量
            schema_problems = SCHEMA.check(case["method"], case["path"], main_http, main_body,
                                           main_headers)
            if schema_problems:
                SCHEMA_VIOLATIONS.extend(f"{cid}｜{p['layer']}｜{p['message']}"
                                         for p in schema_problems)
                if allure:
                    with allure.step("契约校验（schema / Long / traceId）"):
                        allure.attach(json.dumps(schema_problems, ensure_ascii=False, indent=1),
                                      "契约校验违规", allure.attachment_type.JSON)
                ok = False
                detail = (detail + " ｜ 契约校验：" + "；".join(
                    f"[{p['layer']}] {p['message']}" for p in schema_problems))[:1200]
            elif allure:
                with allure.step(f"契约校验通过（schema + Long + traceId，{len(SCHEMA.long_fields)} 条 Long 规则）"):
                    pass

        if "/auth/logout" in case["path"]:  # 登出会作废共享会话，立即重登
            a, ar = login_admin(client)
            u, ur = login_customer(client)
            ctx["admin"], ctx["user"] = a or ctx["admin"], u or ctx["user"]
            ctx["rt"] = {"admin": ar or ctx["rt"]["admin"], "user": ur or ctx["rt"]["user"]}
            ctx["resolver"] = Resolver(client, ctx["admin"], ctx["user"], ctx["rt"])
    finally:
        targets = cleanup if isinstance(cleanup, list) else ([cleanup] if cleanup else [])
        for act in targets:
            if act and act[1]:
                st, body, _ = client.call(act[0], act[1],
                                          token=ctx["admin"] if act[1].startswith("/admin") else ctx["user"],
                                          body=(act[2] if len(act) > 2 else None))
                if not isinstance(body, dict) or body.get("code") != 0:
                    LEAKS.append(f"{case['case_id']} 清理 {act[0]} {act[1]} 失败："
                                 f"HTTP {st} code={body.get('code') if isinstance(body, dict) else '?'}")

        allure.attach(json.dumps({"url": url, "method": case["method"], "http": main_http,
                                  "body": main_body},
                                 ensure_ascii=False, indent=1)[:4000],
                      "请求与响应", allure.attachment_type.JSON)
        allure.attach(case["assertions"], "预期断言", allure.attachment_type.TEXT)
    assert ok, f"[{cid}] {detail}"
