"""Optional Playwright UI smoke layer.

API tests remain the default. Set RUN_UI=1 after installing Playwright and a
browser (`python -m playwright install chromium`) to enable this layer.
"""
import os
from pathlib import Path

import pytest


@pytest.mark.ui
def test_storefront_home(page):
    if os.getenv("RUN_UI", "0") != "1":
        pytest.skip("RUN_UI != 1; Playwright UI layer is opt-in")
    base = os.getenv("APP_BASE_URL", "http://103.236.91.136:60080").rstrip("/")
    page.goto(base, wait_until="domcontentloaded", timeout=30_000)
    assert page.locator("body").is_visible()
    assert page.title() or page.locator("body").inner_text()


@pytest.fixture
def page(request):
    if os.getenv("RUN_UI", "0") != "1":
        yield None
        return
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as manager:
        browser = manager.chromium.launch(headless=True)
        context = browser.new_context()
        current = context.new_page()
        try:
            yield current
        except Exception:
            evidence = Path(os.getenv("ARTIFACT_DIR", "artifacts")) / "failures"
            evidence.mkdir(parents=True, exist_ok=True)
            current.screenshot(path=str(evidence / "playwright-storefront-failure.png"), full_page=True)
            raise
        finally:
            context.close()
            browser.close()
