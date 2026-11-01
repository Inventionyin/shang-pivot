#!/usr/bin/env python3
"""从接口视图数据生成缺陷单：失败用例 → 本地缺陷库 + 报告「缺陷」页。

数据：ext/data/api-matrix.json（含请求/响应/backendLog）、artifacts/run-manifest.json。
定位：后端日志中首个 com.shangpivot 堆栈帧 → backend/**/src/main/java/.../Class.java:行，
      附 ±5 行代码片段；本地优先，commit 不一致时标注。
输出：
  defects/<run_id>/<case_id>.md       仓库内缺陷库
  <report>/defects/<case_id>.md       报告内可访问副本
  <report>/ext/data/defects.json      缺陷页数据
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import subprocess
from datetime import datetime
from urllib.parse import urlparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
FRAME = re.compile(
    r"at (com\.shangpivot\.[\w.$]+)\(([\w$]+\.java):(\d+)\)"
)


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


def git_output(args: list[str], timeout: int = 10) -> str:
    try:
        return subprocess.run(
            args, cwd=REPO, capture_output=True, text=True, timeout=timeout
        ).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def git_branch() -> str:
    return git_output(["git", "rev-parse", "--abbrev-ref", "HEAD"]) or "-"


def repo_web_url() -> str:
    remote = git_output(["git", "config", "--get", "remote.origin.url"])
    if not remote:
        return ""
    if remote.startswith("git@") and ":" in remote:
        host, path = remote[4:].split(":", 1)
    elif remote.startswith("ssh://"):
        parsed = urlparse(remote)
        host, path = parsed.hostname or "", parsed.path.lstrip("/")
    else:
        parsed = urlparse(remote)
        host, path = parsed.hostname or "", parsed.path.lstrip("/")
    path = path.removesuffix(".git").rstrip("/")
    return f"https://{host}/{path}" if host and path else ""


def commit_web_url(repo: str, sha: str) -> str:
    if not repo or not sha:
        return ""
    return f"{repo}/-/commit/{sha}" if "gitlab" in repo else f"{repo}/commit/{sha}"


def file_web_url(repo: str, sha: str, path: str, line: int) -> str:
    if not repo or not sha or not path:
        return ""
    if "gitlab" in repo:
        return f"{repo}/-/blob/{sha}/{path}#L{line}"
    return f"{repo}/blob/{sha}/{path}#L{line}"


def git_blame(path: str, line: int) -> dict:
    """获取该行最后一次修改的 commit/作者；失败时退回文件最后一次提交。"""
    rel = path
    if rel.startswith(str(REPO) + "/"):
        rel = str(Path(rel).relative_to(REPO))
    blame = git_output([
        "git", "blame", "--line-porcelain", "-L", f"{line},{line}", "--", rel
    ])
    result = {}
    if blame:
        lines = blame.splitlines()
        if lines and re.fullmatch(r"[0-9a-f]{40} .*", lines[0]):
            result["commit"] = lines[0].split()[0]
        for item in lines:
            if item.startswith("author "):
                result["author"] = item[len("author "):]
            elif item.startswith("author-mail "):
                result["authorEmail"] = item[len("author-mail "):].strip("<>")
            elif item.startswith("author-time "):
                try:
                    result["authorDate"] = datetime.fromtimestamp(
                        int(item[len("author-time "):])
                    ).strftime("%Y-%m-%d %H:%M")
                except ValueError:
                    pass
    if not result:
        info = git_output([
            "git", "log", "-1", "--format=%H|%an|%ae|%aI", "--", rel
        ])
        if info:
            sha, author, email, date = (info.split("|") + ["", "", "", ""])[:4]
            result = {
                "commit": sha,
                "author": author,
                "authorEmail": email,
                "authorDate": date[:16].replace("T", " "),
            }
    return result


def remote_branch_of(sha: str) -> str:
    if not sha:
        return ""
    output = git_output(["git", "branch", "-r", "--contains", sha])
    for line in output.splitlines():
        name = line.strip()
        if "->" in name:
            continue
        if name.startswith("origin/"):
            return name
    return ""


def enrich_code(code: dict | None, run_commit: str, branch: str) -> dict | None:
    if not code:
        return code
    location = code.get("location", "")
    file_part, _, line_text = location.partition(":")
    try:
        line = int(line_text or 0)
    except ValueError:
        line = 0
    blame = git_blame(file_part, line) if file_part.endswith(".java") else {}
    repo = repo_web_url()
    blame_commit = blame.get("commit") or run_commit
    code.update({
        "branch": branch,
        "remoteBranch": remote_branch_of(blame.get("commit") or run_commit),
        "runCommit": run_commit,
        "blameCommit": blame.get("commit", ""),
        "author": blame.get("author", ""),
        "authorEmail": blame.get("authorEmail", ""),
        "authorDate": blame.get("authorDate", ""),
        "repoUrl": repo,
        "commitUrl": commit_web_url(repo, run_commit),
        "blameCommitUrl": commit_web_url(repo, blame_commit),
        "fileUrl": file_web_url(repo, blame_commit, file_part, line),
    })
    return code


def git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
            capture_output=True, text=True, timeout=5,
        ).stdout.strip() or "-"
    except Exception:  # noqa: BLE001
        return "-"


IGNORED_FRAMES = (".filter.", ".exception.", "GlobalExceptionHandler",
                  "JwtAuthFilter", "AccessLogFilter", "TraceIdFilter")


def frame_label(match) -> str:
    full = match.group(1)
    owner, _, method = full.rpartition(".")
    simple = owner.rsplit(".", 1)[-1]
    return f"{simple}#{method}" if method else simple


def locate_code(log_text: str) -> dict | None:
    matches = list(FRAME.finditer(log_text or ""))
    if not matches:
        return None
    app_frames = [
        item for item in matches
        if not any(marker in item.group(1) for marker in IGNORED_FRAMES)
    ]
    if not app_frames:
        return None
    # 堆栈自上而下：第一个应用帧是抛出点（最底层），反向得到入口→最底层调用链
    match = app_frames[0]
    chain = []
    for frame in reversed(app_frames):
        label = frame_label(frame)
        if label not in chain:
            chain.append(label)
    _, file_name, line_no = match.groups()
    hits = list((REPO / "backend").glob(f"**/src/main/java/**/{file_name}"))
    if not hits:
        return {"location": f"{file_name}:{line_no}", "snippet": "（源码文件未找到）"}
    path = hits[0]
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    line = int(line_no)
    start = max(0, line - 6)
    end = min(len(lines), line + 5)
    snippet = "\n".join(
        f"{idx + 1:>5} | {lines[idx]}" for idx in range(start, end)
    )
    return {
        "location": f"{path.relative_to(REPO)}:{line}",
        "snippet": snippet,
        "frame": match.group(0).strip(),
        "chain": chain,
    }


def _call_graph_index() -> dict:
    files = sorted((REPO / "api_testcases" / "baselines").glob("*/call-graph.json"))
    if not files:
        return {}
    try:
        graph = json.loads(files[-1].read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    return {item.get("endpoint"): item for item in graph.get("endpoints") or []}


def resolve_endpoint(method: str, path: str) -> tuple[str, dict | None]:
    """把真实路径（/admin/after-sales/abc/audit）归一到端点模板。"""
    graph = _call_graph_index()
    meta = graph.get(f"{method} {path}")
    if meta:
        return meta.get("endpoint", f"{method} {path}"), meta
    for key, item in graph.items():
        if item.get("http") != method:
            continue
        template = item.get("path") or ""
        pattern = "^" + re.sub(r"\{[^}]+\}", "[^/]+", template) + "$"
        if re.match(pattern, path):
            return key, item
    return path, None


def locate_by_endpoint(method: str, path: str) -> dict | None:
    """无应用堆栈时退化为「端点 → Controller#方法」定位。"""
    resolved, meta = resolve_endpoint(method, path)
    if meta is None:
        meta = _call_graph_index().get(f"{method} {path}")
    path = resolved.split(" ", 1)[-1]
    if not meta:
        return None
    controller = meta.get("controller")
    method_name = meta.get("method")
    if not controller or not method_name:
        return None
    hits = list((REPO / "backend").glob(f"**/src/main/java/**/{controller}.java"))
    if not hits:
        return {"location": f"{controller}#{method_name}", "snippet": "（源码文件未找到）",
                "source": "endpoint"}
    source = hits[0]
    lines = source.read_text(encoding="utf-8", errors="replace").splitlines()
    pattern = re.compile(rf"\b{re.escape(method_name)}\s*\(")
    line_no = next((idx + 1 for idx, line in enumerate(lines)
                    if pattern.search(line) and not line.strip().startswith("//")), 0)
    if not line_no:
        return {"location": f"{source.relative_to(REPO)}", "snippet": "", "source": "endpoint"}
    start = max(0, line_no - 6)
    end = min(len(lines), line_no + 5)
    snippet = "\n".join(f"{idx + 1:>5} | {lines[idx]}" for idx in range(start, end))
    chain = [f"{controller}#{method_name}"]
    return {
        "location": f"{source.relative_to(REPO)}:{line_no}",
        "snippet": snippet,
        "source": "endpoint",
        "frame": f"{controller}#{method_name}（无应用堆栈，按端点映射）",
        "chain": chain,
    }


def clean_title(name: str) -> str:
    title = re.sub(r"^(?:API|UI|E2E)-[A-Z]+-\d+\s*", "", name or "").strip()
    title = re.split(r"｜", title)[0].strip()
    return title or (name or "")


def clean_summary(message: str, context: str = "") -> str:
    text = (message or "").splitlines()[0] if message else ""
    text = re.sub(r"^AssertionError:\s*", "", text).strip()
    http = re.search(r"HTTP (\d{3})", text)
    biz = re.search(r"业务码期望 (\d+) 实际 (\d+)", text)
    if biz:
        return f"业务码 {biz.group(2)}（期望 {biz.group(1)}）"
    if http:
        reason = f"HTTP {http.group(1)} 系统异常" if http.group(1) == "500" else f"HTTP {http.group(1)}"
        label = (context.split("-", 1)[-1] if "-" in context else context).strip()
        return f"{label} · {reason}" if label and label != reason else reason
    if "50000" in text or "系统异常" in text:
        return "HTTP 500 系统异常"
    return text[:120]


def class_name(location: str) -> str:
    file_part = (location or "").split(":", 1)[0]
    return Path(file_part).stem if file_part else ""


def render_defect_html(*, identifier: str, run_id: str, commit: str, title: str,
                       endpoint: str, dimension: str, priority: str, verify: str,
                       summary: str, request, response, backend_log: str,
                       code: dict | None) -> str:
    def esc(value) -> str:
        return html.escape(str(value if value is not None else ""))

    payload = json.dumps({"request": request, "response": response},
                         ensure_ascii=False, indent=2)[:6000]
    code_text = (code or {}).get("snippet", "")
    location = (code or {}).get("location", "未定位（非 500 或日志缺失）")
    chain = (code or {}).get("chain") or []
    repo_url = (code or {}).get("repoUrl", "")
    branch = (code or {}).get("branch", "")
    remote_branch = (code or {}).get("remoteBranch", "")
    run_commit = (code or {}).get("runCommit", "")
    blame_commit = (code or {}).get("blameCommit", "")
    author = (code or {}).get("author", "")
    author_email = (code or {}).get("authorEmail", "")
    author_date = (code or {}).get("authorDate", "")
    commit_url = (code or {}).get("commitUrl", "")
    blame_url = (code or {}).get("blameCommitUrl", "")
    file_url = (code or {}).get("fileUrl", "")
    repo_html = f'<a href="{esc(repo_url)}" target="_blank" rel="noopener">{esc(repo_url)}</a>' if repo_url else "未配置 remote.origin.url"
    commit_html = f'<a href="{esc(commit_url)}" target="_blank" rel="noopener">{esc(run_commit)}</a>' if commit_url else esc(run_commit)
    blame_html = f'<a href="{esc(blame_url)}" target="_blank" rel="noopener">{esc(blame_commit)}</a>' if blame_url else esc(blame_commit or "—")
    file_html = f'<a href="{esc(file_url)}" target="_blank" rel="noopener">{esc(location)}</a>' if file_url else esc(location)
    if len(chain) > 1:
        chain_block = (
            "<h2>代码调用链路</h2>"
            f'<div class="chain">{esc(" → ".join(chain))}</div>'
        )
    else:
        chain_block = (
            "<h2>代码调用链路</h2>"
            f'<div class="chain">{esc(chain[0] if chain else "入口方法")}'
            "（该缺陷发生在入口/参数解析阶段，未进入更底层业务代码）</div>"
        )
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>缺陷 {esc(identifier)}</title>
<style>
  :root {{ color-scheme: light; }}
  body {{ margin: 0; background: #f6f8fc; color: #1f2937;
    font: 14px/1.7 -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; }}
  main {{ max-width: 1080px; margin: 0 auto; padding: 32px 28px 64px; }}
  h1 {{ font-size: 24px; margin: 0 0 6px; }}
  h2 {{ font-size: 16px; margin: 28px 0 10px; padding-left: 10px; border-left: 3px solid #3563e9; }}
  .meta {{ color: #5b6577; margin: 4px 0; }}
  .badge {{ display: inline-block; padding: 2px 10px; border-radius: 999px;
    background: #fff1f0; color: #c0392b; font-weight: 600; margin-right: 8px; }}
  .card {{ background: #fff; border: 1px solid #e3e8f2; border-radius: 12px; padding: 18px 20px; }}
  pre {{ margin: 0; padding: 14px 16px; background: #f7f9fc; border: 1px solid #e3e8f2;
    border-radius: 10px; overflow: auto; white-space: pre-wrap; word-break: break-word;
    font: 12px/1.6 "SFMono-Regular", Consolas, Menlo, monospace; color: #26324a; }}
  a {{ color: #3563e9; text-decoration: none; }}
  a:hover {{ text-decoration: underline; }}
  .back {{ display: inline-block; margin-bottom: 18px; }}
  .source p {{ margin: 6px 0; overflow-wrap: anywhere; }}
  .chain {{ padding: 10px 14px; background: #fff; border: 1px dashed #c9d4e8;
    border-radius: 10px; font: 12px/1.7 var(--mono, monospace); color: #26324a; }}
</style>
</head>
<body>
<main>
  <a class="back" href="../ext/index.html#/api/defects">← 返回缺陷列表</a>
  <h1>{esc(title)}</h1>
  <div class="meta"><span class="badge">{esc(priority or 'P2')}</span> {esc(dimension or '未分类')}
    &nbsp;·&nbsp; ID：{esc(identifier)}</div>
  <div class="meta">运行：{esc(run_id)}（git {esc(commit)}）</div>
  <h2>用例与断言</h2>
  <div class="card">
    <p><b>用例：</b>{esc(title)}</p>
    <p><b>接口：</b><code>{esc(endpoint)}</code></p>
    <p><b>断言意图：</b>{esc(verify or '—')}</p>
    <p><b>失败摘要：</b><span style="color:#c0392b">{esc(summary)}</span></p>
  </div>
  <h2>请求 / 响应</h2>
  <pre>{esc(payload)}</pre>
  <h2>后端日志（traceId）</h2>
  <pre>{esc(backend_log or '（无 500 堆栈可定位）')}</pre>
  <h2>代码来源</h2>
  <div class="card source">
    <p><b>仓库：</b>{repo_html}</p>
    <p><b>分支：</b><code>{esc(branch or "—")}</code>{f'（远端 {esc(remote_branch)}）' if remote_branch else '（本地分支，未在远端）'}</p>
    <p><b>运行 Commit：</b>{commit_html}</p>
    <p><b>该行最后修改：</b>{blame_html}
      {f' · {esc(author)} &lt;{esc(author_email)}&gt;' if author else ''}
      {f' · {esc(author_date)}' if author_date else ''}</p>
    <p><b>文件：</b>{file_html}</p>
  </div>
  {chain_block}
  <h2>具体代码</h2>
  <p><code>{file_html}</code></p>
  <pre>{esc(code_text)}</pre>
</main>
</body>
</html>
"""


def defect_id(case_id: str, message: str) -> str:
    digest = hashlib.sha1(f"{case_id}|{message[:120]}".encode()).hexdigest()[:8]
    return f"{case_id}-{digest}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "allure-report"))
    parser.add_argument("--zentao-dry-run", action="store_true")
    args = parser.parse_args()
    report_dir = Path(args.report_dir)

    matrix = load(report_dir / "ext" / "data" / "api-matrix.json", {})
    manifest = load(ROOT / "artifacts" / "run-manifest.json", {})
    run_id = manifest.get("run_id") or datetime.now().strftime("%Y%m%d-%H%M%S")
    commit = (manifest.get("git") or {}).get("commit") or git_commit()
    branch = (manifest.get("git") or {}).get("branch") or git_branch()

    out_repo = ROOT / "defects" / run_id
    out_report = report_dir / "defects"
    out_repo.mkdir(parents=True, exist_ok=True)
    out_report.mkdir(parents=True, exist_ok=True)

    seen: set[str] = set()
    defects = []
    for interface in matrix.get("interfaces") or []:
        for point in interface.get("testPoints") or []:
            if point.get("status") not in ("failed", "broken"):
                continue
            signature = point.get("nodeid") or ""
            if signature in seen:
                continue
            seen.add(signature)
            message = point.get("message") or ""
            case_id = point.get("caseId") or point.get("nodeid", "case")
            failure_method = point.get("failureMethod") or interface.get("method")
            failure_path = point.get("failurePath") or interface.get("path")
            resolved_path, _ = resolve_endpoint(failure_method, failure_path)
            if resolved_path:
                failure_path = resolved_path.split(" ", 1)[-1]
            failure_response = point.get("failureResponse") or {}
            failure_request = point.get("failureRequest") or point.get("request")
            code = locate_code(point.get("backendLog") or "")
            if code is None:
                code = locate_by_endpoint(failure_method, failure_path)
            code = enrich_code(code, commit, branch)
            identifier = defect_id(case_id, message)
            safe_id = re.sub(r"[^0-9A-Za-z_.-]+", "_", identifier)[:90]
            filename = f"{safe_id}.md"
            html_name = f"{safe_id}.html"
            link = f"../defects/{html_name}"
            title = clean_title(point.get("name", ""))
            summary = clean_summary(message, title)
            body = "\n".join(
                [
                    f"# 缺陷 {identifier}",
                    "",
                    f"- 运行：{run_id}（git {commit}）",
                    f"- 用例：{title}",
                    f"- 接口：{failure_path}",
                    f"- 分类：{point.get('dimension')}｜优先级：{point.get('priority')}",
                    f"- 断言意图：{point.get('verify') or '—'}",
                    f"- 失败摘要：{summary}",
                    "",
                    "## 请求 / 响应",
                    "",
                    "```json",
                    json.dumps(
                        {"request": failure_request, "response": failure_response or point.get("response")},
                        ensure_ascii=False, indent=2,
                    )[:4000],
                    "```",
                    "",
                    "## 后端日志（traceId）",
                    "",
                    "```",
                    (point.get("backendLog") or "（无 500 堆栈可定位）")[:6000],
                    "```",
                    "",
                    "## 代码位置",
                    "",
                    f"- {code['location'] if code else '未定位（非 500 或日志缺失）'}"
                    + (f"（{code.get('source')}）" if code else ""),
                    "",
                    "```java",
                    (code or {}).get("snippet", ""),
                    "```",
                    "",
                ]
            )
            (out_repo / filename).write_text(body, encoding="utf-8")
            (out_report / filename).write_text(body, encoding="utf-8")
            (out_report / html_name).write_text(
                render_defect_html(
                    identifier=identifier,
                    run_id=run_id,
                    commit=commit,
                    title=title,
                    endpoint=failure_path,
                    dimension=point.get("dimension") or "",
                    priority=point.get("priority") or "",
                    verify=point.get("verify") or "",
                    summary=summary,
                    request=failure_request,
                    response=failure_response or point.get("response"),
                    backend_log=point.get("backendLog") or "",
                    code=code,
                ),
                encoding="utf-8",
            )
            if code:
                code = dict(code)
                code["className"] = class_name(code.get("location", ""))
            defects.append(
                {
                    "id": identifier,
                    "caseId": case_id,
                    "name": title,
                    "endpoint": failure_path,
                    "method": failure_method,
                    "dimension": point.get("dimension"),
                    "priority": point.get("priority"),
                    "summary": summary,
                    "code": code,
                    "reportLink": link,
                }
            )

    payload = {
        "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "runId": run_id,
        "git": commit,
        "total": len(defects),
        "located": sum(1 for item in defects if item.get("code")),
        "defects": defects,
    }
    (report_dir / "ext" / "data" / "defects.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    (out_repo / "index.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    if args.zentao_dry_run:
        (out_repo / "zentao_payload.json").write_text(
            json.dumps(
                [
                    {"title": f"[{item['caseId']}] {item['summary']}", "steps": item["endpoint"],
                     "actual": item["summary"], "code": (item.get("code") or {}).get("location")}
                    for item in defects
                ],
                ensure_ascii=False, indent=2,
            ),
            encoding="utf-8",
        )
    print(
        f"[done] 缺陷单 {len(defects)} 条（已定位代码 {payload['located']}）"
        f" -> {out_repo} / 报告内 {out_report}"
    )


if __name__ == "__main__":
    main()
