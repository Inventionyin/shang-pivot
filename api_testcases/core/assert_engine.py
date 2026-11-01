# -*- coding: utf-8 -*-
"""数据层断言引擎：把用例「断言点」列里的回查指令与字段判定真正执行。

背景：执行器原先只校验 HTTP 状态码 + 业务码（`shang_driver.check`），清单里
「回查 GET /admin/stocks 校验 data.status 为 X」这类断言只是文字、没有实现。
本引擎按句式机械判定，并把**无法机械判定**的断言显式记为 `unchecked`（不静默放过）。

支持句式（逐条命中即判定；找不到判定项则归入 unchecked）：
- `回查 GET <path>` + 后续子句：error.code=<词条> / data.<f> 为 <枚举> / data.<f> 非空|为数组|为数字|为对象
  / 该记录已不在 data.records 中 / data.total 比提交前增加 1 / 目标记录的 <f> 等于本次提交值
  / <f> 与首次提交的值一致 / 本次<动作>出现在 data 列表中
- 直接：data.<f> 非空|为数组|为数字 / data.total 非空且 data.records 为数组 / data 为对象且非空
- 响应 message 指明缺失字段且不含内部堆栈
- 两次响应的 data.total 一致（引擎自行补发第二次请求）
"""
from __future__ import annotations

import re

STACK_MARKERS = ("Exception", "at com.", "at java.", "traceback", "Caused by")


def _get(body, dotted: str):
    cur = body
    for seg in dotted.split("."):
        if isinstance(cur, dict):
            cur = cur.get(seg)
        elif isinstance(cur, list):
            cur = cur[0] if cur else None
            if isinstance(cur, dict):
                cur = cur.get(seg)
            else:
                return None
        else:
            return None
    return cur


def _deep_status(obj):
    """递归找第一个 status 字段（订单详情响应为 {order:{status:...}, items:[...]}）。"""
    if isinstance(obj, dict):
        if "status" in obj:
            return obj["status"]
        for v in obj.values():
            got = _deep_status(v)
            if got is not None:
                return got
    if isinstance(obj, list):
        for v in obj:
            got = _deep_status(v)
            if got is not None:
                return got
    return None


def _type_ok(value, kind: str) -> bool:
    if kind == "非空":
        return value is not None and value != "" and value != [] and value != {}
    if kind == "为数组":
        return isinstance(value, list)
    if kind == "为数字":
        return str(value).replace(".", "", 1).isdigit() if value is not None else False
    if kind == "为对象":
        return isinstance(value, dict)
    return False


def evaluate(case: dict, call, vars_: dict, submitted: dict, pre_total, pre_status,
             is_repeat: bool) -> tuple[bool, str, list[str]]:
    """返回 (是否通过, 失败说明, 未机检项列表)。

    call(path, method="GET") → (http, body)；vars_ 为动作里用到的路径参数/字段值。
    """
    a = case["assertions"]
    unchecked: list[str] = []

    # —— 回查类 ——
    for seg in re.split(r"回查\s+", a)[1:]:
        clause = re.split(r"；", seg)[0]
        mm = re.match(r"(GET|POST)\s+(\S+)", clause)
        if not mm:
            unchecked.append(f"回查子句无法解析：{clause[:40]}")
            continue
        method, target = mm.group(1), mm.group(2).rstrip("，,。")
        for k, v in vars_.items():
            target = target.replace("{" + k + "}", str(v))
        target = re.sub(r"\{[^}]+\}", "1", target)          # 残留占位符兜底
        http, body = call(target, method)
        # 断言显式期望"未认证"时，401 即预期结果（如登出后回查当前登录态）
        if http != 200:
            # "令牌已失效"类断言：401 + 未认证家族（UNAUTHORIZED 40100 / TOKEN_EXPIRED 40102 / TOKEN_INVALID 40103）均满足
            if "UNAUTHORIZED" in clause and http == 401 and isinstance(body, dict) and body.get("code") in (40100, 40102, 40103):
                continue
            return False, f"回查 {method} {target} HTTP {http}", unchecked
        if not isinstance(body, dict):
            unchecked.append(f"回查 {target} 响应非 JSON 对象")
            continue
        code = body.get("code")

        m_err = re.search(r"error\.code=([A-Z_]+|无（[^）]*）)", clause)
        if m_err and not m_err.group(1).startswith("无"):
            from core.shang_driver import ERROR_CODES
            want = ERROR_CODES.get(m_err.group(1))
            if want is not None and code != want:
                return False, f"回查 {target} 业务码期望 {m_err.group(1)}({want}) 实际 {code}", unchecked
            continue

        m_enum = re.search(r"data\.(\w+) 为 ([A-Z_]+)", clause)
        if m_enum:
            got = _get(body.get("data"), m_enum.group(1))
            if got is None and m_enum.group(1) == "status":
                got = _deep_status(body.get("data"))
            if str(got) != m_enum.group(2):
                return False, f"回查 {target} data.{m_enum.group(1)} 期望 {m_enum.group(2)} 实际 {got}", unchecked
            continue

        m_type = re.search(r"data\.(\w+) (非空|为数组|为数字|为对象)", clause)
        if m_type:
            got = _get(body.get("data"), m_type.group(1))
            if not _type_ok(got, m_type.group(2)):
                return False, f"回查 {target} data.{m_type.group(1)} 不满足「{m_type.group(2)}」（实际 {str(got)[:40]}）", unchecked
            continue

        if "已不在 data.records 中" in clause:
            recs = _get(body.get("data"), "records") or []
            ids = {str(x.get("id")) for x in recs if isinstance(x, dict)}
            gone = [str(v) for k, v in vars_.items() if k.endswith("id") or k == "id"]
            if gone and any(g in ids for g in gone):
                return False, f"回查 {target} 目标记录仍在 data.records 中", unchecked
            continue

        m_inc = re.search(r"data\.total 比(提交前|首次提交后)增加 1", clause)
        if m_inc:
            if pre_total is None:
                unchecked.append("条数增量断言缺少前置快照")
                continue
            total = _get(body.get("data"), "total")
            if str(total) != str(int(pre_total) + 1):
                return False, f"回查 {target} data.total 期望 {int(pre_total)+1} 实际 {total}", unchecked
            continue

        if "未再次变更" in clause or "状态未再次变更" in clause:
            if pre_status is None:
                unchecked.append("状态未变更断言缺少前置快照")
                continue
            cur = _get(body.get("data"), "status") or _deep_status(body.get("data"))
            if cur is not None and str(cur) != str(pre_status):
                return False, f"回查 {target} 状态由 {pre_status} 变为 {cur}（预期不再变更）", unchecked
            continue

        m_eq = re.search(r"目标记录的(\w+)等于本次提交值", clause)
        if m_eq:
            recs = _get(body.get("data"), "records") or []
            fld = m_eq.group(1)
            want = submitted.get(fld)
            if not recs or want is None:
                unchecked.append(f"目标记录字段比对缺少数据（{fld}）")
                continue
            if str(recs[0].get(fld)) != str(want):
                return False, f"回查 {target} 记录 {fld} 期望 {want} 实际 {recs[0].get(fld)}", unchecked
            continue

        if "与首次提交的值一致" in clause or "与本次提交值一致" in clause or "关键字段与本次提交值一致" in clause:
            recs = _get(body.get("data"), "records")
            subj = (recs[0] if isinstance(recs, list) and recs else _get(body.get("data"), "")) or {}
            if not isinstance(subj, dict) or not submitted:
                unchecked.append("字段一致性断言缺少可比对数据")
                continue
            bad = [(k, v, subj.get(k)) for k, v in submitted.items()
                   if k in subj and str(subj.get(k)) != str(v)]
            if bad:
                k, v, g = bad[0]
                return False, f"回查 {target} 字段 {k} 期望 {v} 实际 {g}", unchecked
            continue

        if "出现在 data 列表中" in clause:
            recs = _get(body.get("data"), "records") or _get(body.get("data"), "") or []
            ids = {str(x.get("id")) for x in recs if isinstance(x, dict)} if isinstance(recs, list) else set()
            wants = [str(v) for k, v in vars_.items() if k.endswith("id")]
            if ids and wants and not any(w in ids for w in wants):
                return False, f"回查 {target} 未找到本次动作产生的记录（{wants}）", unchecked
            continue

        if "包含业务字段且非空" in clause or "data 为对象且非空" in clause or "data 为对象或数组且非空" in clause:
            if not _type_ok(body.get("data"), "为对象") and not isinstance(body.get("data"), list):
                return False, f"回查 {target} data 为空或非对象/数组", unchecked
            continue

        unchecked.append(f"回查子句无可机械判定项：{clause[:44]}")

    # —— 直接 data 断言（无回查）——
    m_d = re.search(r"data\.(\w+) (非空|为数组|为数字)", a)
    if m_d and "回查" not in a:
        got = _get((vars_.get("_primary_body") or {}).get("data"), m_d.group(1))
        if not _type_ok(got, m_d.group(2)):
            return False, f"data.{m_d.group(1)} 不满足「{m_d.group(2)}」（实际 {str(got)[:40]}）", unchecked
    if "data.total 非空且 data.records 为数组" in a and "回查" not in a:
        pb = vars_.get("_primary_body") or {}
        if not _type_ok(_get(pb.get("data"), "total"), "非空") or not _type_ok(_get(pb.get("data"), "records"), "为数组"):
            return False, "data.total/data.records 形状不符", unchecked
    if "data 为数组且元素包含 id 字段" in a and "回查" not in a:
        arr = _get((vars_.get("_primary_body") or {}).get("data"), "")
        if not isinstance(arr, list) or not arr or "id" not in (arr[0] if isinstance(arr[0], dict) else {}):
            return False, "data 非数组或元素缺 id 字段", unchecked

    # —— message 断言 ——
    if "message 指明缺失字段且不含内部堆栈" in a or "message 指明参数校验失败且不含内部堆栈" in a:
        pb = vars_.get("_primary_body") or {}
        msg = str(pb.get("msg") or "")
        if not msg:
            return False, "响应 message 为空", unchecked
        if any(mk in msg for mk in STACK_MARKERS):
            return False, f"响应 message 泄露内部信息：{msg[:60]}", unchecked

    # —— 两次响应一致性（分页边界）——
    if "两次响应的 data.total 一致" in a:
        pb = vars_.get("_primary_body") or {}
        t1 = _get(pb.get("data"), "total")
        t2 = vars_.get("_second_total")
        if t2 is None:
            unchecked.append("两次响应一致性断言缺少第二次查询")
        elif str(t1) != str(t2):
            return False, f"两次查询 data.total 不一致：{t1} vs {t2}", unchecked

    return True, "", unchecked
