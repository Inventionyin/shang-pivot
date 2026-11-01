#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""投产判据器（发布门禁 C1–C8）。

把"能不能投产"从人工判断变成机器可判。判据与登记表：

  C1 lint 离线 fail 0
  C2 三件套对账 PASS（reconcile.py）
  C3 执行失败 ⊆ 已登记（artifacts/known-failures.json）
  C4 xfail ⊆ 已登记（本版无 pytest xfail 机制，按"登记表覆盖失败"等价判定，见 C3）
  C5 P0 用例跳过数 = 0
  C6 评审 P0 覆盖 100%（artifacts/review/p0-review.json）
  C7 缺陷全关闭（禅道 product 2 的 Bug 状态均为 closed/resolved）
  C8 结果新鲜度 ≤ 48h（automation_results.json 与本次运行绑定）

用法：python3 scripts/release_gate.py [--json]
退出码：0=全绿；1=存在未通过项
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT))

import case_lint as L  # noqa: E402

FRESH_HOURS = 48


def _run(cmd: list[str]) -> tuple[int, str]:
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=str(ROOT))
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    checks: list[dict] = []

    # C1 lint
    rc, out = _run([sys.executable, str(ROOT / "scripts" / "case_lint.py"),
                    "--inventory", str(ROOT / "test_cases_inventory.md")])
    m = re.search(r"用例 (\d+) 条 \| fail (\d+)", out)
    checks.append({"id": "C1", "name": "lint 离线 fail 0", "pass": rc == 0 and m and m.group(2) == "0",
                   "detail": (m.group(0) if m else out.strip()[-80:])})

    # C2 对账
    rc2, out2 = _run([sys.executable, str(ROOT / "scripts" / "reconcile.py"), "--json"])
    try:
        rec = json.loads(out2.strip().splitlines()[-1])
        c2 = rec.get("verdict") == "PASS"
        det2 = (f"接口差集 {len(rec['interface']['unregistered_gap'])} / "
                f"需求缺口 {len(rec['requirement']['unregistered_gap'])} / "
                f"词典未登记 {len(rec['dict']['unregistered_gap'])}")
    except Exception:
        c2, det2 = False, out2.strip()[-80:]
    checks.append({"id": "C2", "name": "三件套对账 PASS", "pass": c2, "detail": det2})

    # C3 失败 ⊆ 登记（多 nodeid 防护：同一用例任一失败即判失败，不取"最后写入为准"）
    res_path = ROOT / "artifacts" / "automation_results.json"
    reg_path = ROOT / "artifacts" / "known-failures.json"
    failed: set[str] = set()
    skipped_p0 = 0
    outcome_by_case: dict[str, str] = {}
    empty_results = False
    if res_path.exists():
        results = json.loads(res_path.read_text(encoding="utf-8")).get("results", [])
        # 假绿互检：结果为空 = 回写链断（pytest 结果没映射回稳定ID）。
        # 此时"失败集为空"会让 C3 自动通过 —— 必须先判红。
        empty_results = not results
        p0 = {r["case_id"] for r in L.parse_inventory(ROOT / "test_cases_inventory.md")
              if r["priority"] == "P0"}
        for r in results:
            for cid in r.get("case_ids", []):
                oc = r.get("outcome", "")
                # 失败优先：一旦失败，后续同名通过结果不得覆盖
                if outcome_by_case.get(cid) != "failed":
                    outcome_by_case[cid] = oc
        failed = {c for c, oc in outcome_by_case.items() if oc == "failed"}
        skipped_p0 = len([c for c, oc in outcome_by_case.items() if oc == "skipped" and c in p0])
    registered = set(json.loads(reg_path.read_text(encoding="utf-8"))["registered"].keys()) \
        if reg_path.exists() else set()
    # 防"空登记"绕过（假绿互检 ⑥）：每条登记必须带缺陷/观察项 ID + 理由，否则等同未登记
    reg_body = json.loads(reg_path.read_text(encoding="utf-8")).get("registered", {}) if reg_path.exists() else {}
    empty_reg = sorted(k for k, v in reg_body.items() if not v.get("defect") or not v.get("reason"))
    unregistered = sorted(failed - registered)
    # 反向：登记了但本轮不再失败 → 登记表已过期（缺陷已修或前轮为环境性失败），须退休，不得挂账
    stale_reg = sorted(registered - failed)
    c3 = not unregistered and not empty_reg and not empty_results and not stale_reg
    checks.append({"id": "C3", "name": "失败 ⊆ 登记 ⊆ 失败（双向核对）", "pass": c3,
                   "detail": ("结果文件为空 → 回写链断，失败集不可信；" if empty_results else "")
                             + f"失败 {len(failed)} / 已登记 {len(registered)} / 未登记 {len(unregistered)}"
                             + (f"：{unregistered[:5]}" if unregistered else "")
                             + (f" / 空登记 {len(empty_reg)}" if empty_reg else "")
                             + (f" / 登记已失效（本轮通过）{len(stale_reg)}：{stale_reg[:5]}" if stale_reg else "")})

    # C4 xfail ⊆ 登记（本版无 xfail 机制：以登记表覆盖等价判定）
    checks.append({"id": "C4", "name": "xfail ⊆ 已登记", "pass": c3,
                   "detail": "本版无 pytest xfail 机制；等价判定为 C3（失败须登记）"})

    # C5 P0 跳过 = 0
    checks.append({"id": "C5", "name": "P0 跳过数 = 0", "pass": skipped_p0 == 0,
                   "detail": f"P0 跳过 {skipped_p0} 条"})

    # C6 评审 P0 覆盖 100%
    rev_path = ROOT / "artifacts" / "review" / "p0-review.json"
    if rev_path.exists():
        rev = json.loads(rev_path.read_text(encoding="utf-8"))
        tot, done = rev.get("p0_total", 0), rev.get("p0_reviewed", 0)
        c6 = tot > 0 and done >= tot
        det6 = f"P0 评审 {done}/{tot}"
    else:
        c6, det6 = False, "缺评审台账 artifacts/review/p0-review.json（P0 未全评）"
    checks.append({"id": "C6", "name": "评审 P0 覆盖 100%", "pass": c6, "detail": det6})

    # C7 缺陷全关闭（禅道 product 2）
    open_bugs = None
    try:
        import requests
        base = os.getenv("ZENTAO_BASE_URL", "http://111.170.33.2:60082")
        pw_file = Path("/Users/imac/Desktop/电商自动化测试/禅道密码")
        pwd = re.search(r'"password":"([^"]+)"', pw_file.read_text(encoding="utf-8")).group(1)
        s = requests.Session(); s.trust_env = False
        tok = s.post(base + "/api.php/v1/tokens", json={"account": "admin", "password": pwd},
                     timeout=20).json()["token"]
        bugs = s.get(f"{base}/api.php/v1/products/2/bugs", headers={"Token": tok}, timeout=30).json()
        bugs = bugs.get("bugs", bugs) if isinstance(bugs, dict) else bugs
        open_bugs = [b for b in bugs if str(b.get("status")) not in ("closed", "resolved")]
    except Exception as e:
        open_bugs = None
        det7 = f"禅道不可达或读取失败：{type(e).__name__}"
    if open_bugs is not None:
        det7 = f"未关闭缺陷 {len(open_bugs)} 条" + (f"：{[b.get('id') for b in open_bugs][:6]}" if open_bugs else "")
    checks.append({"id": "C7", "name": "缺陷全关闭", "pass": bool(open_bugs == []), "detail": det7})

    # C8 结果新鲜度 + 与本次运行绑定（防假绿互检 ①②：`touch` 刷时间 / 手改结果文件）
    if res_path.exists():
        raw = json.loads(res_path.read_text(encoding="utf-8"))
        problems = []
        # ① 绑定字段齐备（旧版结果文件无 run_id → 视为未绑定，不通过）
        run_id, finished = raw.get("run_id", ""), raw.get("finished_at", "")
        if not run_id:
            problems.append("结果文件缺 run_id（无法证明是本轮产出）")
        if not finished:
            problems.append("结果文件缺 finished_at")
        # ② 与 junit 同批：记录摘要必须与当前 junit 一致（改任一侧即失配）
        junit = Path(raw.get("junit_xml", "")) if raw.get("junit_xml") else None
        if junit and junit.exists():
            import hashlib
            actual = hashlib.sha256(junit.read_bytes()).hexdigest()
            if actual != raw.get("junit_sha256", ""):
                problems.append("结果文件与 junit.xml 摘要不一致（非同批产出）")
            junit_age = (time.time() - junit.stat().st_mtime) / 3600
            if junit_age > FRESH_HOURS:
                problems.append(f"junit.xml 龄 {junit_age:.1f}h 超阈值")
        elif junit:
            problems.append(f"junit.xml 不存在：{junit}")
        # ③ 新鲜度按 finished_at（不是文件 mtime），mtime 可被 touch 伪造
        try:
            age_h = (time.time() - datetime.datetime.fromisoformat(finished).timestamp()) / 3600
        except Exception:
            age_h = 9e9
        if age_h > FRESH_HOURS:
            problems.append(f"结果龄 {age_h:.1f}h 超阈值 {FRESH_HOURS}h")
        c8 = not problems
        det8 = (f"run_id={run_id or '—'} 龄 {age_h:.1f}h（阈值 {FRESH_HOURS}h）"
                + ("；" + "；".join(problems) if problems else "；与 junit 同批"))
    else:
        c8, det8 = False, "缺 automation_results.json"
    checks.append({"id": "C8", "name": f"结果新鲜度 ≤ {FRESH_HOURS}h 且与本次运行绑定", "pass": c8, "detail": det8})

    passed = sum(1 for c in checks if c["pass"])
    verdict = "PASS" if passed == len(checks) else "FAIL"
    result = {"verdict": verdict, "passed": passed, "total": len(checks), "checks": checks}
    out_dir = ROOT / "artifacts" / "gate"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "release-gate.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    lines = [f"# 投产放行判据（{verdict} {passed}/{len(checks)}）", "",
             f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}", "",
             "| 判据 | 结论 | 依据 |", "|---|---|---|"]
    lines += [f"| {c['id']} {c['name']} | {'✅' if c['pass'] else '❌'} | {c['detail']} |" for c in checks]
    (out_dir / "release-gate.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        for c in checks:
            print(f"  {'✅' if c['pass'] else '❌'} {c['id']} {c['name']} — {c['detail']}")
        print(f"\n投产放行：{verdict}（{passed}/{len(checks)}）")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
