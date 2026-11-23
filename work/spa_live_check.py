#!/usr/bin/env python3
"""Live check of the SPA served by the Fly deployment.

Proves the browser client actually runs under the production CSP (no inline script), can
authenticate against the same-origin API, render server data, and create a task through the UI.

    python3 work/spa_live_check.py https://crud-app-metbxg.fly.dev <email> <password>
"""
import json
import os
import sys
import time
import urllib.request

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://crud-app-metbxg.fly.dev").rstrip("/")
EMAIL = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("SPA_EMAIL", "")
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("SPA_PASSWORD", "")
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens")
os.makedirs(SHOTS, exist_ok=True)

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  PASS " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail else ""))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(req, data=data, timeout=30) as res:
        raw = res.read()
        return json.loads(raw) if raw else None


def main():
    # ---- seed one project + task so the table has a row to render
    auth = api("/api/v1/auth/login", "POST", {"email": EMAIL, "password": PASSWORD})
    token = auth["accessToken"]
    project = api("/api/v1/projects", "POST", {
        "code": f"LIVE{int(time.time()) % 100000}",
        "name": "Live browser check",
        "status": "ACTIVE",
    }, token)
    title = f"Browser-rendered task {int(time.time())}"
    task = api("/api/v1/tasks", "POST", {"projectId": project["id"], "title": title,
                                         "priority": "HIGH", "status": "TODO"}, token)

    options = webdriver.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1400,900")
    options.set_capability("goog:loggingPrefs", {"browser": "ALL"})
    driver = webdriver.Chrome(options=options)
    wait = WebDriverWait(driver, 25)

    try:
        driver.get(BASE + "/")
        wait.until(EC.visibility_of_element_located((By.ID, "login-form")))
        check("SPA document served and bootstrapped", True)
        check("no CSP violation on load", not has_csp_violation(driver))

        driver.find_element(By.ID, "email").send_keys(EMAIL)
        driver.find_element(By.ID, "password").send_keys(PASSWORD)
        driver.find_element(By.ID, "login-button").click()

        wait.until(EC.visibility_of_element_located((By.ID, "workspace")))
        check("login through the SPA succeeded", True)
        check("login view hidden after auth",
              driver.find_element(By.ID, "login-view").is_displayed() is False)

        wait.until(lambda d: title in d.find_element(By.ID, "task-rows").text)
        check("server data rendered in the table", True)
        check("no CSP violation after login", not has_csp_violation(driver))

        driver.save_screenshot(os.path.join(SHOTS, "live-spa.png"))
        print("  screenshot: live-spa.png")

        # ---- create a task through the UI (proves same-origin POST works under CSP)
        ui_title = f"Created in browser {int(time.time())}"
        driver.find_element(By.ID, "new-task").click()
        wait.until(EC.visibility_of_element_located((By.ID, "task-dialog")))
        driver.find_element(By.ID, "task-title").send_keys(ui_title)
        driver.find_element(By.CSS_SELECTOR, "#task-form button[type='submit']").click()
        wait.until(lambda d: ui_title in d.find_element(By.ID, "task-rows").text)
        check("task created through the UI", True)

    finally:
        driver.quit()
        # ---- clean up seeded data
        try:
            api(f"/api/v1/projects/{project['id']}", "DELETE", token=token)
        except Exception as exc:  # noqa: BLE001
            print(f"  (cleanup warning: {exc})")

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


def has_csp_violation(driver):
    for entry in driver.get_log("browser"):
        message = entry.get("message", "")
        if entry.get("level") == "SEVERE" and ("Content Security Policy" in message
                                              or "Refused to" in message):
            print("   console: " + message[:200])
            return True
    return False


if __name__ == "__main__":
    sys.exit(main())
