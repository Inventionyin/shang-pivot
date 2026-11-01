#!/usr/bin/env python3
"""把测试报告 Markdown 转成打印版 HTML（封面/目录/图表/截图）。

用法：
  python3 scripts/build-test-report.py \
    --input docs/06-测试报告-尚品汇-20261027.md \
    --output artifacts/report/测试报告-尚品汇-20261027.html \
    [--toc-map /tmp/toc-map.json]
"""
from __future__ import annotations

import argparse
import base64
import html
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CSS = r"""
@page {
  size: A4;
  margin: 17mm 15mm 18mm 15mm;
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
  font-size: 10pt;
  line-height: 1.62;
  color: #1f2a37;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}
h1, h2, h3, h4 { color: #1f3a5f; font-weight: 700; }
h2 {
  font-size: 16pt;
  margin: 0 0 12pt;
  padding-bottom: 5pt;
  border-bottom: 2.2pt solid #1f3a5f;
  break-before: page;
  break-after: avoid;
}
h3 {
  font-size: 12pt;
  margin: 15pt 0 6pt;
  break-after: avoid;
}
h4 { font-size: 10.8pt; margin: 12pt 0 5pt; break-after: avoid; }
p { margin: 0 0 7pt; text-align: justify; }
ul, ol { margin: 0 0 8pt 18pt; padding: 0; }
li { margin: 0 0 3pt; }
strong { color: #14263d; }
blockquote {
  margin: 9pt 0;
  padding: 8pt 10pt;
  background: #f3f6fa;
  border-left: 3.5pt solid #2f6f9f;
  color: #34495e;
  font-size: 9.4pt;
}
blockquote p { margin: 0; }
code {
  font-family: "SFMono-Regular", Menlo, Consolas, monospace;
  font-size: 8.6pt;
  background: #f1f4f7;
  color: #b03a2e;
  padding: 1pt 3pt;
  border-radius: 2pt;
}
pre {
  background: #f6f8fa;
  border: 0.6pt solid #dbe2ea;
  border-radius: 4pt;
  padding: 9pt 11pt;
  margin: 7pt 0 10pt;
  white-space: pre-wrap;
  word-break: break-all;
  break-inside: avoid;
}
pre code { background: transparent; color: #243447; font-size: 8.3pt; padding: 0; }
table {
  width: 100%;
  border-collapse: collapse;
  margin: 6pt 0 11pt;
  font-size: 8.7pt;
  line-height: 1.45;
}
thead { display: table-header-group; }
thead th {
  background: #e8eef5;
  color: #1f3a5f;
  font-weight: 700;
  border: 0.6pt solid #c3cfdd;
  padding: 5pt 6pt;
  text-align: left;
  vertical-align: middle;
}
td {
  border: 0.6pt solid #d5dce5;
  padding: 4.5pt 6pt;
  vertical-align: top;
}
tbody tr:nth-child(even) { background: #f7f9fc; }
tr { break-inside: avoid; }
hr { border: none; border-top: 0.6pt solid #d5dce5; margin: 10pt 0; }
a { color: #245f8f; text-decoration: none; }

/* ---------- 封面 ---------- */
.cover {
  height: 258mm;
  display: flex;
  flex-direction: column;
  break-after: page;
  position: relative;
}
.cover-top {
  background: #1f3a5f;
  color: #ffffff;
  padding: 10pt 14pt;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 8.6pt;
  letter-spacing: 0.6pt;
}
.cover-top .docno { font-weight: 700; }
.cover-main {
  padding: 42pt 6pt 0;
  flex: 1;
}
.cover-kicker {
  color: #2f6f9f;
  font-size: 9pt;
  letter-spacing: 2.2pt;
  font-weight: 700;
  margin-bottom: 16pt;
}
.cover h1 {
  font-size: 32pt;
  line-height: 1.28;
  margin: 0 0 18pt;
  color: #14263d;
}
.cover-sub {
  color: #5b6b7d;
  font-size: 12pt;
  line-height: 1.6;
  max-width: 150mm;
}
.status-badge {
  display: inline-block;
  margin-top: 24pt;
  padding: 6pt 14pt;
  background: #e7f3ea;
  color: #1e6b32;
  border: 0.8pt solid #9fcaaa;
  border-radius: 3pt;
  font-size: 10.5pt;
  font-weight: 700;
  letter-spacing: 0.5pt;
}
.cover-facts {
  display: flex;
  gap: 30pt;
  margin-top: 34pt;
  padding-top: 12pt;
  border-top: 0.8pt solid #d5dce5;
}
.cover-facts div { display: flex; flex-direction: column; }
.cover-facts strong { font-size: 17pt; color: #1f3a5f; line-height: 1.1; }
.cover-facts span { font-size: 8.2pt; color: #7a8794; margin-top: 3pt; }
.cover-meta {
  margin: 0 6pt 18pt;
  border-top: 0.8pt solid #c3cfdd;
  border-bottom: 0.8pt solid #c3cfdd;
  padding: 12pt 0;
}
.cover-meta table { margin: 0; font-size: 9.4pt; }
.cover-meta td { border: none; padding: 3.4pt 8pt 3.4pt 0; background: transparent; }
.cover-meta td:first-child { color: #6b7a8c; width: 28mm; }
.cover-meta td:last-child { color: #14263d; font-weight: 600; }
.cover-foot {
  color: #7a8794;
  font-size: 8.4pt;
  display: flex;
  justify-content: space-between;
  padding: 0 6pt;
}
.cover-rule {
  height: 3pt;
  background: linear-gradient(90deg, #1f3a5f 0%, #2f6f9f 58%, #9fc2d8 100%);
  margin-top: 10pt;
}

/* ---------- 前置页 ---------- */
.frontmatter h2 { break-before: avoid; }
.toc { break-before: page; break-after: page; }
.toc h2 { break-before: avoid; }
.main > h2:first-of-type { break-before: avoid; }
.toc-row {
  display: flex;
  align-items: baseline;
  font-size: 10.5pt;
  margin: 8pt 0;
}
.toc-title { color: #1f2a37; }
.toc-dots {
  flex: 1;
  border-bottom: 0.7pt dotted #9aa7b6;
  margin: 0 7pt 3pt;
}
.toc-page { color: #5b6b7d; min-width: 8mm; text-align: right; }
.toc-note { margin-top: 14pt; color: #7a8794; font-size: 8.4pt; }

/* ---------- KPI 与图表 ---------- */
.kpi-grid {
  display: flex;
  gap: 8pt;
  margin: 4pt 0 14pt;
  break-inside: avoid;
}
.kpi {
  flex: 1;
  border: 0.7pt solid #d5dce5;
  border-top: 3pt solid #2f6f9f;
  background: #fbfcfe;
  padding: 10pt 8pt;
  text-align: center;
}
.kpi-value { font-size: 18pt; font-weight: 700; color: #1f3a5f; line-height: 1.1; }
.kpi-label { font-size: 8.4pt; color: #6b7a8c; margin-top: 3pt; }
.chart-grid {
  display: flex;
  gap: 12pt;
  margin: 4pt 0 14pt;
  break-inside: avoid;
}
.chart-card {
  flex: 1;
  border: 0.7pt solid #d5dce5;
  background: #ffffff;
  padding: 10pt 12pt;
}
.chart-card h4 { margin: 0 0 8pt; color: #1f3a5f; }
.bar-row { display: flex; align-items: center; gap: 6pt; margin: 5pt 0; font-size: 8.2pt; }
.bar-label { width: 30mm; color: #4a5a6b; }
.bar-track { flex: 1; height: 7pt; background: #e9eef4; border-radius: 3pt; overflow: hidden; }
.bar-fill { height: 100%; background: #2f6f9f; border-radius: 3pt; }
.bar-value { width: 14mm; text-align: right; color: #1f3a5f; font-weight: 600; }
.donut-wrap { display: flex; align-items: center; gap: 12pt; }
.donut {
  width: 31mm; height: 31mm; border-radius: 50%;
  background: conic-gradient(#2e7d32 calc(var(--p) * 1%), #e5eaf0 0);
  display: flex; align-items: center; justify-content: center;
}
.donut-inner {
  width: 23mm; height: 23mm; border-radius: 50%; background: #ffffff;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.donut-inner strong { font-size: 13pt; color: #1f3a5f; line-height: 1.1; }
.donut-inner span { font-size: 7.6pt; color: #7a8794; }
.legend { font-size: 8.2pt; color: #4a5a6b; }
.legend div { margin: 3pt 0; }
.dot { display: inline-block; width: 6pt; height: 6pt; border-radius: 50%; margin-right: 5pt; }

/* ---------- 图片 ---------- */
figure { margin: 8pt 0 12pt; break-inside: avoid; text-align: center; }
figure img {
  max-width: 100%;
  border: 0.7pt solid #cfd8e3;
  box-shadow: 0 1pt 4pt rgba(31, 58, 95, 0.12);
}
figcaption { margin-top: 5pt; color: #6b7a8c; font-size: 8.4pt; }
.figure-grid { display: flex; gap: 10pt; align-items: flex-start; }
.figure-grid figure { flex: 1; }
.figure-grid img { width: 100%; }

/* ---------- 结尾 ---------- */
.end-note {
  margin-top: 16pt;
  padding-top: 8pt;
  border-top: 0.7pt solid #c3cfdd;
  color: #6b7a8c;
  font-size: 8.6pt;
  text-align: center;
}
"""


def run_marked(md_path: Path) -> str:
    with tempfile.NamedTemporaryFile(suffix=".html", delete=False) as tmp:
        out = Path(tmp.name)
    npx = shutil.which("npx") or str(Path.home() / ".local/bin/npx")
    subprocess.run(
        [npx, "--yes", "marked", "-i", str(md_path), "-o", str(out)],
        check=True, cwd=str(ROOT), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    text = out.read_text(encoding="utf-8")
    out.unlink(missing_ok=True)
    return text


def b64(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


def build(md_path: Path, output: Path, toc_map: dict[str, int] | None) -> None:
    body = run_marked(md_path)
    body = re.sub(r"^.*?(?=<h2>文档控制</h2>)", "", body, flags=re.S)

    titles: list[tuple[str, str]] = []

    def add_id(match: re.Match) -> str:
        inner = match.group(1)
        text = html.unescape(re.sub(r"<[^>]+>", "", inner)).strip()
        sec = f"sec-{len(titles) + 1}"
        titles.append((sec, text))
        return f'<h2 id="{sec}">{inner}</h2>'

    body = re.sub(r"<h2>(.*?)</h2>", add_id, body, flags=re.S)

    toc_rows = []
    for sec, title in titles:
        page = str(toc_map.get(sec, "—")) if toc_map else "—"
        toc_rows.append(
            f'<div class="toc-row"><span class="toc-title">{html.escape(title)}</span>'
            f'<span class="toc-dots"></span><span class="toc-page">{page}</span></div>'
        )
    toc_html = "\n".join(toc_rows)

    overview = """
<div class="kpi-grid">
  <div class="kpi"><div class="kpi-value">33/33</div><div class="kpi-label">用例通过</div></div>
  <div class="kpi"><div class="kpi-value">100%</div><div class="kpi-label">通过率</div></div>
  <div class="kpi"><div class="kpi-value">0</div><div class="kpi-label">产品缺陷</div></div>
  <div class="kpi"><div class="kpi-value">47.14s</div><div class="kpi-label">全量耗时</div></div>
</div>
<div class="chart-grid">
  <div class="chart-card">
    <h4>层级分布</h4>
    <div class="bar-row"><span class="bar-label">API</span><div class="bar-track"><div class="bar-fill" style="width:63.6%"></div></div><span class="bar-value">21</span></div>
    <div class="bar-row"><span class="bar-label">UI</span><div class="bar-track"><div class="bar-fill" style="width:36.4%"></div></div><span class="bar-value">12</span></div>
    <div class="bar-row"><span class="bar-label">E2E</span><div class="bar-track"><div class="bar-fill" style="width:3.0%"></div></div><span class="bar-value">1</span></div>
    <div class="legend" style="margin-top:8pt">通过率 100%，无阻塞、无跳过、无重试。</div>
  </div>
  <div class="chart-card">
    <h4>执行结果</h4>
    <div class="donut-wrap">
      <div class="donut" style="--p:100"><div class="donut-inner"><strong>100%</strong><span>33 / 33</span></div></div>
      <div class="legend">
        <div><span class="dot" style="background:#2e7d32"></span>通过 33</div>
        <div><span class="dot" style="background:#c0392b"></span>失败 0</div>
        <div><span class="dot" style="background:#b7791f"></span>阻塞 0</div>
      </div>
    </div>
  </div>
</div>
<div class="chart-card" style="margin-bottom:14pt">
  <h4>最慢场景（毫秒）</h4>
  <div class="bar-row"><span class="bar-label">验证码错误提示</span><div class="bar-track"><div class="bar-fill" style="width:100%"></div></div><span class="bar-value">7458</span></div>
  <div class="bar-row"><span class="bar-label">未勾选结算拦截</span><div class="bar-track"><div class="bar-fill" style="width:73.9%"></div></div><span class="bar-value">5512</span></div>
  <div class="bar-row"><span class="bar-label">完整购买旅程</span><div class="bar-track"><div class="bar-fill" style="width:68.3%"></div></div><span class="bar-value">5094</span></div>
  <div class="bar-row"><span class="bar-label">进入商品详情</span><div class="bar-track"><div class="bar-fill" style="width:66.2%"></div></div><span class="bar-value">4935</span></div>
  <div class="bar-row"><span class="bar-label">搜索空态</span><div class="bar-track"><div class="bar-fill" style="width:64.8%"></div></div><span class="bar-value">4829</span></div>
</div>
"""
    body = body.replace('<h2 id="sec-2">1. 执行摘要</h2>',
                        '<h2 id="sec-2">1. 执行摘要</h2>\n' + overview, 1)

    img_report = ROOT / "artifacts/report-screenshots/01-allure-overview.png"
    img_e2e = ROOT / "artifacts/run-20261027-100606/screenshots/完整购买旅程：加购-结算-支付-订单.png"
    figures = ""
    if img_report.exists() and img_e2e.exists():
        figures = f"""
<div class="figure-grid">
  <figure>
    <img src="data:image/png;base64,{b64(img_report)}" alt="Allure 报告概览">
    <figcaption>图 1　Allure 报告概览：33 个用例、100% 通过</figcaption>
  </figure>
  <figure>
    <img src="data:image/png;base64,{b64(img_e2e)}" alt="完整购买旅程页面">
    <figcaption>图 2　完整购买旅程：支付完成后的订单页面</figcaption>
  </figure>
</div>
"""
    body = body.replace('<p><strong>报告结束</strong></p>', figures + '<p><strong>报告结束</strong></p>', 1)
    # 结尾免责声明已在执行摘要说明，移除避免单独占一页
    body = re.sub(r'<blockquote>\s*<p>本报告基于.*?</blockquote>', '', body, flags=re.S)

    cover = """
<section class="cover">
  <div class="cover-top">
    <span>INTERNAL · 内部资料</span>
    <span class="docno">SP-E2E-TR-20261027-01</span>
  </div>
  <div class="cover-main">
    <div class="cover-kicker">AUTOMATED FUNCTIONAL TEST REPORT</div>
    <h1>尚品汇电商平台<br>自动化测试报告</h1>
    <div class="cover-sub">C 端交易主链路 · 管理端核心页面<br>API 契约、业务规则与端到端验证</div>
    <div class="status-badge">PASS · 有条件通过</div>
    <div class="cover-facts">
      <div><strong>33</strong><span>自动化场景</span></div>
      <div><strong>21 / 12</strong><span>API / UI</span></div>
      <div><strong>100%</strong><span>通过率</span></div>
      <div><strong>0</strong><span>产品缺陷</span></div>
    </div>
  </div>
  <div class="cover-meta">
    <table>
      <tr><td>项目名称</td><td>尚品汇电商平台（Shang Pivot）</td></tr>
      <tr><td>被测版本</td><td>后端 0.0.1-SNAPSHOT · 商城 / 管理端本地构建</td></tr>
      <tr><td>测试类型</td><td>自动化功能测试（API + UI + E2E）</td></tr>
      <tr><td>测试环境</td><td>本地开发环境（macOS / JDK 21 / PostgreSQL 16 / Redis 7）</td></tr>
      <tr><td>测试周期</td><td>2026-10-27</td></tr>
      <tr><td>报告日期</td><td>2026-10-27</td></tr>
      <tr><td>编制 / 审核</td><td>自动化测试组 / 待审核</td></tr>
    </table>
  </div>
  <div class="cover-foot">
    <span>自动化测试组</span>
    <span>共 33 个自动化场景 · 通过率 100%</span>
  </div>
  <div class="cover-rule"></div>
</section>
"""

    toc = f"""
<section class="toc">
  <h2>目录</h2>
  {toc_html}
  <div class="toc-note">页码为 PDF 物理页，包含封面与前置页。</div>
</section>
"""

    split_at = body.index('<h2 id="sec-2">1. 执行摘要</h2>')
    front, main = body[:split_at], body[split_at:]

    doc = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>尚品汇电商平台自动化测试报告 · SP-E2E-TR-20261027-01</title>
<style>{CSS}</style>
</head>
<body>
{cover}
<section class="frontmatter">
{front}
</section>
{toc}
<section class="main">
{main}
</section>
</body>
</html>
"""
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(doc, encoding="utf-8")
    titles_json = output.with_suffix(".titles.json")
    titles_json.write_text(
        json.dumps([{"sec": sec, "title": title} for sec, title in titles], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"HTML written: {output}")
    print(f"titles written: {titles_json}")
    print(f"sections: {len(titles)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--toc-map")
    args = parser.parse_args()
    toc_map = None
    if args.toc_map:
        toc_map = {k: int(v) for k, v in json.loads(Path(args.toc_map).read_text()).items()}
    build(Path(args.input).resolve(), Path(args.output).resolve(), toc_map)


if __name__ == "__main__":
    main()
