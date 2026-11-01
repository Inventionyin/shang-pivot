#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""冻结一个版本基线快照：复制机读契约产物 + 生成指纹清单。

为什么需要它：跨版本验证的前提是「旧版本有一个不可变、可复算的引用」。没有快照，
下次升级时就没有东西可以交给 `baseline_diff.py` 做对比，所谓「对不同版本验证」就是空话。

本脚本只做两件事（不做生成，避免职责混淆）：
  1. 把已生成的机读产物（`artifacts/schema-model.json`、`artifacts/endpoint-model.json`）
     复制进 `baselines/<label>/`；
  2. 计算版本指纹（git commit + 关键文件 sha256 + 迁移范围）写入 `manifest.json` 与 `manifest.md`。

前置（由本脚本检查，缺失则报错退出）：
  python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \\
      --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md
  python3 scripts/build_db_dict.py --fingerprint "<label>"

用法：
  python3 scripts/snapshot_baseline.py --label 810986c
  python3 scripts/snapshot_baseline.py --label v2.0.0 --note "V2 购物网站交付"
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent

# 纳入指纹的「契约面」文件。改这份清单 = 改版本指纹口径，需同步 docs/06-baseline/00-版本基线.md。
FINGERPRINT_FILES = [
    "backend/pom.xml",
    "backend/shang-server/pom.xml",
    "backend/shang-identity/pom.xml",
    "backend/shang-commerce/pom.xml",
    "backend/shang-common/pom.xml",
    "backend/shang-server/src/main/resources/application.yaml",
    "backend/docker-compose.yml",
    "shang-admin-web/package.json",
    "shang-mall/package.json",
    "ui_testcases/pom.xml",
    "api_testcases/requirements.txt",
    "docs/openapi-v3-api-docs.json",
]
ARTIFACTS = ["schema-model.json", "endpoint-model.json", "field-map.json", "call-graph.json"]


def sha256_16(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def git(*args: str) -> str:
    try:
        return subprocess.run(["git", "-C", str(REPO), *args], check=True,
                              capture_output=True, text=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def migration_fingerprint() -> dict:
    md = REPO / "backend/shang-server/src/main/resources/db/migration"
    if not md.is_dir():
        return {}
    files = sorted(md.glob("V*.sql"), key=lambda p: int(re.match(r"V(\d+)", p.name).group(1)))
    digest = hashlib.sha256()
    for p in files:
        digest.update(p.name.encode())
        digest.update(p.read_bytes())
    versions = [int(re.match(r"V(\d+)", p.name).group(1)) for p in files]
    return {
        "migration_count": len(files),
        "migration_first": files[0].name if files else None,
        "migration_last": files[-1].name if files else None,
        "migration_range": f"V1–V{max(versions)}" if versions else None,
        "migration_sha256": digest.hexdigest(),
    }


def java_source_fingerprint() -> dict:
    files = [p for p in sorted((REPO / "backend").rglob("*.java")) if "target" not in p.parts]
    digest = hashlib.sha256()
    for p in files:
        digest.update(str(p.relative_to(REPO)).encode())
        digest.update(p.read_bytes())
    return {"java_file_count": len(files), "java_sha256": digest.hexdigest()}


def build_manifest(label: str, note: str, previous: dict | None = None) -> dict:
    files = {}
    for rel in FINGERPRINT_FILES:
        p = REPO / rel
        files[rel] = sha256_16(p) if p.exists() else "MISSING"
    for rel in ("docs/测试资料/接口文档.md",):
        p = REPO / rel
        if p.exists():
            files[rel] = sha256_16(p)
    for rel in ARTIFACTS:
        p = ROOT / "artifacts" / rel
        files[f"artifacts/{rel}"] = sha256_16(p) if p.exists() else "MISSING"

    schema = json.loads((ROOT / "artifacts" / "schema-model.json").read_text(encoding="utf-8"))
    endpoints = json.loads((ROOT / "artifacts" / "endpoint-model.json").read_text(encoding="utf-8"))

    # 冻结时间保持粘性：同一 label 重复运行必须产出逐字节相同的 manifest，
    # 否则 §2 的「复算后 git diff 为空」新鲜度判定会每次误报指纹变更。
    created_at = (previous or {}).get("created_at") or datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    return {
        "label": label,
        "note": note or (previous or {}).get("note", ""),
        "created_at": created_at,
        "git_commit": git("rev-parse", "HEAD") or "unknown",
        "git_commit_short": git("rev-parse", "--short", "HEAD") or "unknown",
        "git_branch": git("rev-parse", "--abbrev-ref", "HEAD") or "unknown",
        "git_tags": [t for t in (git("tag", "--points-at", "HEAD") or "").split("\n") if t],
        "git_commit_count": int(git("rev-list", "--count", "HEAD") or 0) or None,
        "schema": {
            "table_count": len(schema.get("tables", {})),
            "column_count": sum(len(t["columns"]) for t in schema.get("tables", {}).values()),
            "index_count": sum(len(t["indexes"]) for t in schema.get("tables", {}).values()),
            "generated_at": schema.get("generated_at"),
            **migration_fingerprint(),
        },
        "api": {
            "endpoint_count": len(endpoints.get("endpoints", [])),
            "write_endpoint_count": sum(1 for e in endpoints.get("endpoints", [])
                                        if e.get("method") in ("POST", "PUT", "DELETE", "PATCH")),
        },
        "fingerprints": files,
        **java_source_fingerprint(),
    }


def render_md(m: dict) -> str:
    lines = [f"# 基线快照 manifest — `{m['label']}`", ""]
    if m.get("note"):
        lines += [f"> {m['note']}", ""]
    lines += [
        f"- 冻结时间：{m['created_at']}",
        f"- Git：`{m['git_commit_short']}`（{m['git_branch']}，提交数 {m['git_commit_count']}，"
        f"tag {m['git_tags'] or '无'}）",
        f"- 迁移范围：`{m['schema'].get('migration_range')}`（{m['schema'].get('migration_count')} 个文件）",
        f"- 契约面：{m['schema']['table_count']} 表 / {m['schema']['column_count']} 列 / "
        f"{m['api']['endpoint_count']} 端点",
        "",
        "## 指纹表",
        "",
        "| 指纹 | 对象 |",
        "|---|---|",
        f"| `{m['git_commit_short']}` | Git 提交 |",
        f"| `{(m['schema'].get('migration_sha256') or '')[:16]}` | `db/migration/**` 聚合 |",
        f"| `{(m.get('java_sha256') or '')[:16]}` | `backend/**/*.java` 聚合（{m.get('java_file_count')} 文件） |",
    ]
    for rel, h in m["fingerprints"].items():
        lines.append(f"| `{h}` | `{rel}` |")
    lines += ["", "## 复算方式", "", "```bash",
              "python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \\",
              '    --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md',
              'python3 scripts/build_db_dict.py --fingerprint "' + m["label"] + '"',
              f"python3 scripts/snapshot_baseline.py --label {m['label']}",
              "```", "",
              "复算后与快照逐字节一致 = 该版本基线仍成立；否则机制区文档已过期。", ""]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True, help="版本标签（commit 短 sha 或版本号，如 810986c / v2.0.0）")
    ap.add_argument("--note", default="", help="版本说明")
    ap.add_argument("--out-dir", default=None, help="默认 api_testcases/baselines/<label>")
    args = ap.parse_args()

    missing = [f"artifacts/{a}" for a in ARTIFACTS if not (ROOT / "artifacts" / a).exists()]
    if missing:
        print("缺少机读产物，请先运行生成器：\n"
              "  python3 scripts/build_api_doc.py --repo-root .. --openapi ../docs/openapi-v3-api-docs.json \\\n"
              '      --out "../docs/测试资料/接口文档.md" --diff artifacts/api-doc-diff.md\n'
              "  python3 scripts/build_db_dict.py --fingerprint \"<label>\"", file=sys.stderr)
        for m in missing:
            print(f"  - 缺 {m}", file=sys.stderr)
        return 2

    out_dir = Path(args.out_dir) if args.out_dir else (ROOT / "baselines" / args.label)
    out_dir.mkdir(parents=True, exist_ok=True)

    previous = None
    prev_manifest = out_dir / "manifest.json"
    if prev_manifest.exists():
        try:
            previous = json.loads(prev_manifest.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            previous = None
    manifest = build_manifest(args.label, args.note, previous)

    for a in ARTIFACTS:
        shutil.copy2(ROOT / "artifacts" / a, out_dir / a)
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                           encoding="utf-8")
    (out_dir / "manifest.md").write_text(render_md(manifest), encoding="utf-8")

    print(json.dumps({
        "快照目录": str(out_dir),
        "版本标签": args.label,
        "git": manifest["git_commit_short"],
        "表/列": f"{manifest['schema']['table_count']}/{manifest['schema']['column_count']}",
        "端点": manifest["api"]["endpoint_count"],
        "迁移": manifest["schema"].get("migration_range"),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
