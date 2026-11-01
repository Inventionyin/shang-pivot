"""失败诊断：按 traceId 抓取后端日志片段，挂到 Allure。

对应测试方案 §6「失败诊断」：响应里有 traceId 却不去用，等于浪费源码埋点。
后端日志路径默认 ../backend/logs/shang-pivot.log，可用 BACKEND_LOG 覆盖。
"""
from __future__ import annotations

import os
from pathlib import Path

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None

_MAX_LINES = 160
# 后端日志可能到百 MB 级；traceId 刚写入，只需读尾部，避免每个失败用例全文扫描
_TAIL_BYTES = 8 * 1024 * 1024


def _log_files() -> list[Path]:
    configured = os.getenv("BACKEND_LOG", "")
    if configured:
        path = Path(configured)
        return [path] if path.exists() else []
    here = Path(__file__).resolve().parents[1]
    repo = Path(__file__).resolve().parents[2] / "backend" / "logs"
    files: list[Path] = []
    for log_dir in (repo, here / "logs", Path.cwd() / "logs"):
        if log_dir.exists():
            files.extend(
                item for item in list(log_dir.glob("shang-pivot*.log")) + list(log_dir.glob("backend*.out"))
                if "access" not in item.name
            )
    # 后端可能从不同工作目录启动（logback 相对路径），按修改时间新→旧查找
    return sorted(set(files), key=lambda item: item.stat().st_mtime, reverse=True)


def _tail_lines(path: Path, max_bytes: int = _TAIL_BYTES) -> list[str]:
    with open(path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(max(0, size - max_bytes))
        data = handle.read()
    # 从中间截断时丢掉不完整的第一行
    text = data.decode("utf-8", errors="replace")
    lines = text.splitlines()
    if size > max_bytes and lines:
        lines = lines[1:]
    return lines


def trace_excerpt(trace_id: str) -> str:
    """返回包含该 traceId 的日志行（含紧随其后的堆栈行）。"""
    files = _log_files()
    if not files or not trace_id:
        return ""
    for path in files[:5]:
        lines: list[str] = []
        try:
            for line in _tail_lines(path):
                if trace_id in line:
                    lines.append(line.rstrip())
                    # 堆栈通常紧跟其后（以空白开头 / at 开头）
                    position = 0
                elif lines and position < _MAX_LINES:
                    lines.append(line.rstrip())
                    position += 1
                elif lines:
                    break
        except OSError:
            continue
        if lines:
            return "\n".join(lines)
    return ""


def attach(trace_id: str, name: str = "后端日志（按 traceId）") -> str:
    """把日志片段挂到 Allure 并返回片段文本（便于本地调试时直接看）。"""
    text = trace_excerpt(trace_id)
    if not text:
        text = f"未找到 traceId={trace_id} 的日志（BACKEND_LOG 未配置或日志已轮转）"
    if allure is not None:
        allure.attach(text, name=name, attachment_type=allure.attachment_type.TEXT)
    return text
