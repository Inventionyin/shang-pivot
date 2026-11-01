"""UI 夹具：页面、登录态注入、步骤截图与 Playwright trace。"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from config.settings import settings
from core.logger import get_logger
from core.step_recorder import StepRecorder

log = get_logger("ui")


def _safe_test_id(nodeid: str) -> str:
    """与 pytest-playwright 的输出目录命名保持一致。"""
    return re.sub(r"[^a-z0-9]+", "-", nodeid.lower()).strip("-")


def trace_path(nodeid: str) -> Path:
    return Path(settings.artifacts_dir) / "ui" / "playwright" / _safe_test_id(nodeid) / "trace.zip"


def _attach_evidence(page, request) -> None:
    """给页面挂步骤录制器；用例结束时写 steps.json。"""
    recorder = StepRecorder(page, request.node.nodeid)
    page._step_recorder = recorder
    request.addfinalizer(recorder.save_manifest)


def _start_trace(context) -> None:
    context.tracing.start(screenshots=True, snapshots=True, sources=True)


def _stop_trace(context, nodeid: str) -> None:
    path = trace_path(nodeid)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        context.tracing.stop(path=str(path))
        log.info("Playwright trace: %s", path)
    except Exception as exc:  # noqa: BLE001 - trace 失败不能掩盖用例结果
        log.warning("Playwright trace 保存失败 %s: %s", nodeid, exc)


@pytest.fixture
def require_ui():
    if not settings.run_ui:
        pytest.skip("RUN_UI=0，UI 用例跳过")


@pytest.fixture
def admin_credentials():
    """管理端账号密码；密码来自环境变量或 .env.local。"""
    if not settings.admin_password:
        pytest.skip(
            "未设置 ADMIN_PASSWORD：请在 pytest_automation/.env.local 填写，"
            "或通过环境变量注入（.env.local 已 gitignore）"
        )
    return settings.admin_username, settings.admin_password


@pytest.fixture
def mall_page(require_ui, page, request):
    """商城匿名页面（1280x720），自动录 trace 与每步截图。"""
    _start_trace(page.context)
    _attach_evidence(page, request)
    try:
        yield page
    finally:
        _stop_trace(page.context, request.node.nodeid)


@pytest.fixture
def admin_page(require_ui, page, request):
    """管理端匿名页面，自动录 trace 与每步截图。"""
    _start_trace(page.context)
    _attach_evidence(page, request)
    try:
        yield page
    finally:
        _stop_trace(page.context, request.node.nodeid)


@pytest.fixture
def logged_in_mall_page(require_ui, browser, customer, request):
    """把 API 登录得到的 token 注入 localStorage，直接以登录态进入商城。"""
    context = browser.new_context(viewport={"width": 1280, "height": 720})
    context.add_init_script(
        "localStorage.setItem('shang_mall_access', %s);"
        "localStorage.setItem('shang_mall_refresh', %s);"
        % (_js(customer.token), _js(customer.refresh_token))
    )
    _start_trace(context)
    page = context.new_page()
    _attach_evidence(page, request)
    try:
        yield page
    finally:
        _stop_trace(context, request.node.nodeid)
        context.close()


@pytest.fixture
def logged_in_admin_page(require_ui, browser, admin, request):
    """注入后台 token；路由守卫会自行调用 /admin/auth/me 补齐用户信息。"""
    context = browser.new_context(viewport={"width": 1280, "height": 720})
    context.add_init_script(
        "localStorage.setItem('shang_admin_token', %s);" % _js(admin.token)
    )
    _start_trace(context)
    page = context.new_page()
    _attach_evidence(page, request)
    try:
        yield page
    finally:
        _stop_trace(context, request.node.nodeid)
        context.close()


def _js(value: str) -> str:
    """把 Python 字符串安全嵌进 JS 字面量。"""
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
