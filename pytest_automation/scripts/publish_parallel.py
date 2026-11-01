#!/usr/bin/env python3
"""把并行两阶段产物发布到报告，同时保留已有的 UI 结果。

并行运行只覆盖 API 层，直接整体替换 artifacts/allure 会丢掉 47 条 UI 用例及
其截图/trace。本脚本做三件事：
  1. 以并行 run 目录的 Allure 结果为底，追加保留旧的 UI 结果（含附件/容器）；
  2. 合并映射：并行 mapping + 旧 mapping 中的 UI 条目（schema 不变）；
  3. 写回 artifacts/（allure、case_endpoint_map.json、results.json、db diff）。

随后照常执行 scripts/allure_report.sh 即可，接口视图逻辑无需改动。
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

UI_PREFIXES = ("tests.ui.", "tests/ui/")
UI_HINTS = ("test_ui_purchase_flow",)


def is_ui(nodeid: str) -> bool:
    return nodeid.startswith(UI_PREFIXES) or any(hint in nodeid for hint in UI_HINTS)


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return default


def result_nodeid(data: dict) -> str:
    labels = {l.get("name"): l.get("value") for l in (data.get("labels") or [])}
    return labels.get("pytest_nodeid") or data.get("fullName") or ""


def copy_ui_results(source_dir: Path, target_dir: Path) -> tuple[int, set[str]]:
    """把旧报告里的 UI 结果、附件与相关容器复制到新结果目录。"""
    kept_uuids: set[str] = set()
    copied = 0
    for file in source_dir.glob("*-result.json"):
        data = load_json(file, {})
        if not is_ui(result_nodeid(data)):
            continue
        shutil.copyfile(file, target_dir / file.name)
        kept_uuids.add(data.get("uuid", ""))
        copied += 1
        for attachment in data.get("attachments") or []:
            src = attachment.get("source")
            if src and (source_dir / src).exists():
                shutil.copyfile(source_dir / src, target_dir / src)
    for file in source_dir.glob("*-container.json"):
        data = load_json(file, {})
        children = set(data.get("children") or [])
        if children & kept_uuids:
            shutil.copyfile(file, target_dir / file.name)
            for attachment in data.get("attachments") or []:
                src = attachment.get("source")
                if src and (source_dir / src).exists():
                    shutil.copyfile(source_dir / src, target_dir / src)
    return copied, kept_uuids


def merge_mapping(run_mapping: dict, old_mapping: dict) -> dict:
    tests = dict(run_mapping.get("tests") or {})
    details = dict(run_mapping.get("details") or {})
    samples = dict(run_mapping.get("samples") or {})
    for nodeid, endpoints in (old_mapping.get("tests") or {}).items():
        if not is_ui(nodeid):
            continue
        merged = set(tests.get(nodeid, [])) | set(endpoints)
        tests[nodeid] = sorted(merged)
    for nodeid, detail in (old_mapping.get("details") or {}).items():
        if is_ui(nodeid):
            details.setdefault(nodeid, detail)
    for key, sample in (old_mapping.get("samples") or {}).items():
        samples.setdefault(key, sample)
    return {"tests": tests, "details": details, "samples": samples}


def merge_results(run_results: dict, old_results: dict) -> dict:
    results = list(run_results.get("results") or [])
    results.extend(
        item for item in (old_results.get("results") or [])
        if is_ui(item.get("nodeid", ""))
    )
    payload = dict(run_results)
    payload["worker"] = "merged"
    payload["results"] = results
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--artifacts", default="artifacts")
    args = parser.parse_args()
    run_dir = Path(args.run_dir)
    artifacts = Path(args.artifacts)
    allure_dir = artifacts / "allure"

    old_mapping = load_json(artifacts / "case_endpoint_map.json", {})
    old_results = load_json(artifacts / "results.json", {})
    run_mapping = load_json(run_dir / "case_endpoint_map.json", {})
    run_results = load_json(run_dir / "results.json", {})

    # 1) 重建 allure：旧结果先移到临时目录，放并行结果后把 UI 结果/附件/容器追回来
    backup = artifacts / ".allure_old"
    if backup.exists():
        shutil.rmtree(backup)
    if allure_dir.exists():
        allure_dir.rename(backup)
    shutil.copytree(run_dir / "allure", allure_dir)
    ui_count = 0
    if backup.exists():
        ui_count, _ = copy_ui_results(backup, allure_dir)
        shutil.rmtree(backup)

    (artifacts / "case_endpoint_map.json").write_text(
        json.dumps(merge_mapping(run_mapping, old_mapping), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (artifacts / "results.json").write_text(
        json.dumps(merge_results(run_results, old_results), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    diff = run_dir / "db-baseline-diff.json"
    if diff.exists():
        shutil.copyfile(diff, artifacts / "db-baseline-diff.json")
    manifest = run_dir / "run-manifest.json"
    if manifest.exists():
        shutil.copyfile(manifest, artifacts / "run-manifest.json")
    print(f"[done] 已发布：并行 API 结果 + 保留 UI 结果 {ui_count} 条")


if __name__ == "__main__":
    main()
