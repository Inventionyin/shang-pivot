# 后端日志路径陷阱

后端从不同工作目录启动时，logback 相对路径会把日志写到 `pytest_automation/logs/` 或 `backend/logs/`；
`shang-pivot-access.log` 更新更早且包含 traceId，但只有访问行、没有异常堆栈。

**Why:** 失败诊断曾因此只挂上 access 日志，Allure 里看不到 500 堆栈。

**How to apply:** `helpers/diagnose.py` 必须同时搜索 `backend/logs`、`pytest_automation/logs`、`cwd/logs`，
排除文件名含 `access` 的日志，按 mtime 倒序取应用日志；`start_backend_jacoco.sh` 先 `cd $REPO` 再启动。
