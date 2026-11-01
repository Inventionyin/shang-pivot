#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""lint 规则自测回归：每条规则必须有"坏例必报、好例不报"的锁定样例。

适配尚品汇（Shang Pivot）约定：
- 路径前缀 `/admin`、`/api`；成功业务码 `0`（不是 200）；
- 业务失败族走 HTTP 200 + body 数值码（BizException 无 @ResponseStatus）。

用法：python3 scripts/lint_selftest.py
退出码：0=全部通过；1=存在规则失效/误报。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import case_lint as L  # noqa: E402


def rec(**kw) -> dict:
    base = {
        "line": 1, "case_id": "TEST_CASE-001", "priority": "P1", "kind": "契约",
        "title": "管理员登录_典型请求_登录成功",
        "precondition": "环境：baseUrl（BASE_URL）由 pytest 环境变量提供（conftest 自动注入）。",
        "method_url": "POST /api/auth/login",
        "headers": "Content-Type: application/json（不含 Authorization）",
        "payload": "phone 从 APP_TEST_ACCOUNT 取；code 从 APP_TEST_SMS_CODE 取；clientType=web",
        "steps": "", "expected_http": "200", "expected_code": "0",
        "assertions": "HTTP=200；code=0；data.accessToken 非空", "cleanup": "无需清理（只读）",
        "notes": "接口文档§3.1；需求：MALL-01",
    }
    base.update(kw)
    if not base["steps"]:
        base["steps"] = (f"请求：{base['method_url']}<br>请求头：{base['headers']}"
                         f"<br>参数：{base['payload']}<br>步骤：发送请求；记录响应；按预期断言。")
    return base


def good_rows() -> list[dict]:
    """完整覆盖 POST /api/auth/login 的正向/参数/幂等 三类（鉴权豁免），应零违规。"""
    return [
        rec(),
        rec(case_id="TEST_CASE-002", title="会员登录_缺少必填参数_提示参数错误",
            payload="删除必填 body 字段 code；其余字段正常提供：phone 从 APP_TEST_ACCOUNT 取，clientType=web",
            expected_http="400", expected_code="BAD_REQUEST",
            assertions="HTTP=400；error.code=BAD_REQUEST；响应 message 包含「验证码」"),
        rec(case_id="TEST_CASE-003", title="会员登录_重复提交_结果一致",
            assertions="HTTP=200；code=0；回查 POST /api/auth/login 校验两次响应的 data.accessToken 均非空"),
    ]


def bad(rule: str):
    cases = {
        "R01": lambda: [rec(title="两段标题")],
        "R02": lambda: good_rows() + [rec(case_id="TEST_CASE-004", title="管理员登录_典型请求_登录成功")],
        "R03": lambda: [rec(title="会员登录_典型请求_HTTP 400")],
        "R04": lambda: [rec(case_id="badid")],
        "R05": lambda: good_rows() + [rec()],
        "R06": lambda: [rec(expected_code="待确认")],
        "R07": lambda: [rec(cleanup="")],
        "R08": lambda: [rec(cleanup="无")],
        "R09": lambda: [rec(payload="account=<test>")],
        "R10": lambda: [rec(payload="addressId: 1")],
        "R11": lambda: [rec(steps="请求：POST /api/auth/login<br>步骤：访问 127.0.0.1 测试")],
        "R12": lambda: [rec(precondition="已有测试账号")],
        "R13": lambda: [rec(method_url="FETCH /api/auth/login")],
        "R15": lambda: [good_rows()[0]],  # 只剩正向 → 缺 参数/幂等 维度
        "R16": lambda: [rec(priority="P0"), rec(case_id="TEST_CASE-002", priority="P0")],
        "R17": lambda: [rec(priority="P9")],
        "R18": lambda: [rec(assertions="成功")],
        "R21": lambda: [rec(payload="无请求体")],
        "R22": lambda: [rec(steps=rec()["steps"] + "先调用对应列表接口动态获取路径参数")],
        "R23": lambda: [rec(method_url="PUT /admin/users/{id}/status",
                            headers="Content-Type: application/json；Authorization: Bearer <普通用户 token>",
                            payload='{"status":"ENABLED"}；路径参数 id：运行时先调用 GET /admin/users 取一条记录的 id',
                            title="管理员修改用户状态_典型请求_修改成功",
                            expected_code="0", assertions="HTTP=200；code=0；data.id 等于请求的用户 id")],
        "R24": lambda: [rec(steps="参数：已被手工改漂移")],
        "R25": lambda: [rec(title="会员登录_缺少必填参数_提示参数错误",
                            payload="路径参数 id：运行时先调用 GET /api/auth 取一条记录的 id")],
        "R26": lambda: [rec(method_url="GET /api/me", payload="无请求体；路径参数 id：运行时先调用 GET /api/me 取一条记录的 id")],
        "R27": lambda: [rec(expected_code="INVENTED_ENUM")],
        "R29": lambda: [rec(assertions="HTTP=200；code=0；断言接口返回成功")],  # 无数据层锚点
        "R30": lambda: [rec(expected_code="资源不存在（拒绝重复删除）")],  # 业务码不可判定
        # v1.1 质量层：断言/清理/请求体必须与源码事实对齐
        "R31": lambda: [rec(assertions="HTTP=200；code=0；回查 GET /no/such/endpoint/{id} 校验 data 非空")],
        "R32": lambda: [rec(cleanup="用例结束后调用 DELETE /no/such/endpoint/{id} 移除本次创建的专属测试数据")],
        "R35": lambda: [rec(case_id="TEST_CASE-005", kind="幂等", title="会员登录_重复提交_结果一致",
                            notes="接口文档§2", assertions="HTTP=200；code=0；data.token 非空")],
        # 依赖离线文档的两条：上下文由 ctx() 注入（R14 受控接口清单 / R28 受控需求编号）
        "R14": lambda: [rec()],
        "R28": lambda: [rec()],
        "R34": lambda: [rec(method_url="POST /admin/auth/login",
                            headers="Content-Type: application/json（不含 Authorization）",
                            title="管理员登录_典型请求_登录成功",
                            payload='请求体：{"@username": "自动测试", "password": "x"}',
                            assertions="HTTP=200；code=0；data.accessToken 非空")],
        "R33": lambda: [rec(method_url="POST /admin/auth/login",
                            headers="Content-Type: application/json（不含 Authorization）",
                            title="管理员登录_典型请求_登录成功",
                            payload='请求体：{"username": "自动测试管理员"}',
                            assertions="HTTP=200；code=0；data.accessToken 非空")],
    }
    return cases[rule]()


ONLINE_ONLY = ("R19", "R20")  # 仅这两条必须联网；R14/R28 依赖离线文档，已纳入自测

def main() -> int:
    failures = []
    domain_dict = L.parse_domain_dict(L.DEFAULT_DICT)
    # 1) 好例必须零违规
    good = L.run_rules(good_rows(), None, domain_dict)
    if good:
        failures.append(f"好例误报 {len(good)} 条: {[(v.rule, v.message[:30]) for v in good]}")
    else:
        print("✔ 好例零违规")
    # 2) 每条规则坏例必报
    checked = 0
    for rule in [f"R{n:02d}" for n in range(1, 36)]:
        if rule in ONLINE_ONLY:
            print(f"⊘ {rule} 在线规则，跳过（由 --online 模式覆盖）")
            continue
        rows = bad(rule)
        catalog = {"GET /api/home"} if rule == "R14" else None
        req_ids = {"ZZZ-99"} if rule == "R28" else None
        fired = {v.rule for v in L.run_rules(rows, catalog, domain_dict, req_ids)}
        checked += 1
        if rule in fired:
            print(f"✔ {rule} 坏例命中")
        else:
            print(f"✘ {rule} 坏例未命中（规则失效！）")
            failures.append(f"{rule} 未命中坏例")
    print(f"\n自测：{checked} 条离线规则 | 失败 {len(failures)}")
    for f in failures:
        print("  -", f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
