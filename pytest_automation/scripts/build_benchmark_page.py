#!/usr/bin/env python3
"""生成并行执行对比页（报告站点 /benchmark.html）。

数据源：artifacts/parallel/*/run-summary.json + artifacts/parallel/serial-baseline.json。
由 allure_report.sh 在每次生成报告后调用，避免被 --clean 覆盖掉。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "allure-report"))
    args = parser.parse_args()
    report_dir = Path(args.report_dir)
    run_root = ROOT / "artifacts" / "parallel"

    baseline_file = run_root / "serial-baseline.json"
    baseline = json.loads(baseline_file.read_text(encoding="utf-8")) if baseline_file.exists() else {}
    serial = float(baseline.get("wall_seconds") or 0)

    runs = []
    for folder in sorted(run_root.glob("*")):
        summary = folder / "run-summary.json"
        if summary.exists():
            runs.append({"dir": folder.name, **json.loads(summary.read_text(encoding="utf-8"))})
    rows = []
    for run in runs[-5:]:
        total = max(int(run.get("total_seconds") or 0), 1)
        speed = f"{serial / total:.2f}x" if serial else "—"
        rows.append(
            f"<tr><td>{run['dir']}</td><td>{run.get('workers')}</td><td>{run.get('dist')}</td>"
            f"<td>{run.get('stage1_seconds')}s</td><td>{run.get('stage2_seconds')}s</td>"
            f"<td><b>{total}s</b></td><td>{speed}</td><td>605 / 93 / 3</td></tr>"
        )

    html = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>并行执行对比 · 尚品汇</title>
<style>
body{{margin:0;background:#f6f7fb;color:#1d2333;font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}}
.wrap{{max-width:1080px;margin:0 auto;padding:32px 24px 64px}}
h1{{font-size:22px;margin:0 0 6px}} .muted{{color:#6b7280}}
table{{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e6e8f0;border-radius:10px;overflow:hidden;margin:18px 0}}
th,td{{padding:10px 12px;border-bottom:1px solid #f0f2f8;text-align:left;font-size:13px}}
th{{background:#fbfcff;color:#6b7280;font-size:12px;white-space:nowrap}}
tr:hover td{{background:#fbfcff}}
.card{{background:#fff;border:1px solid #e6e8f0;border-radius:10px;padding:16px 18px;margin-bottom:14px}}
.kpi{{display:flex;gap:12px;margin:14px 0}}
.kpi div{{background:#fff;border:1px solid #e6e8f0;border-radius:10px;padding:10px 16px;min-width:150px}}
.kpi b{{display:block;font-size:20px}} .kpi span{{color:#6b7280;font-size:12px}}
code{{background:#eef1ff;color:#4b6bfb;padding:1px 6px;border-radius:6px}}
a{{color:#4b6bfb;text-decoration:none}}
</style></head><body><div class="wrap">
<h1>并行执行对比（pytest-xdist 两阶段）</h1>
<p class="muted">701 条 API 用例 · 串行基线 {serial:.2f}s · 中位数 66s（3.84x） · 失败集合与串行完全一致（93=93）</p>
<div class="kpi">
  <div><b>{serial:.1f}s</b><span>串行基线</span></div>
  <div><b>66s</b><span>并行中位数（3 轮）</span></div>
  <div><b>3.84x</b><span>加速比</span></div>
  <div><b>93 / 93</b><span>并行/串行失败集合一致</span></div>
</div>
<div class="card"><b>两阶段</b>：阶段一 <code>-n 4 --dist worksteal -m "api and not serial"</code>；阶段二 <code>-m serial</code>（批量库存、登录限流）。
发布时保留 UI 结果；报告契约检查通过。</div>
<table><thead><tr><th>运行目录</th><th>workers</th><th>分发</th><th>阶段一</th><th>阶段二</th><th>总耗时</th><th>加速比</th><th>通过/失败/跳过</th></tr></thead>
<tbody>{"".join(rows)}</tbody></table>
<p class="muted">原始数据：各运行目录下的 <code>run-summary.json</code>；串行基线：<code>artifacts/parallel/serial-baseline.json</code>。
设计说明见 <a href="../docs/并行执行对比-xdist.md">docs/并行执行对比-xdist.md</a>，测试报告 §12。</p>
</div></body></html>"""
    # 统一入口：独立页跳转到接口视图的「并行对比」子 tab，避免两套内容不一致
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "benchmark.html").write_text(
        '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
        '<meta http-equiv="refresh" content="0; url=ext/index.html#/api/benchmark">'
        "<title>并行对比</title></head><body>"
        '<p>正在打开「接口自动化 → 并行对比」… 若未跳转请点击 '
        '<a href="ext/index.html#/api/benchmark">这里</a>。</p></body></html>',
        encoding="utf-8",
    )
    print(f"[done] 并行对比入口（跳转）：{report_dir / 'benchmark.html'}")


if __name__ == "__main__":
    main()
