#!/usr/bin/env python3
"""Live sign-up flow check against the deployed SPA.

1. opens the public URL and switches to the sign-up form
2. registers a brand-new account through the UI
3. verifies it lands in the workspace as a MEMBER
4. verifies the new account can create its own project and task
5. disables the throwaway account afterwards (admin API)

    python3 work/spa_signup_check.py <base-url> <admin-email> <admin-password>
"""
import json
import os
import sys
import time
import urllib.request

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import Select, WebDriverWait

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://crud-app-metbxg.fly.dev").rstrip("/")
ADMIN_EMAIL = sys.argv[2]
ADMIN_PASSWORD = sys.argv[3]
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens")

STAMP = int(time.time())
NEW_EMAIL = f"signup-{STAMP}@example.com"
NEW_PASSWORD = "SignupPass123!"
NEW_NAME = f"Signup Tester {STAMP}"
PROJECT_NAME = f"New user project {STAMP}"
PROJECT_CODE = f"SIGN{STAMP % 100000}"
TASK_TITLE = f"First task {STAMP}"

results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("  PASS " if ok else "  FAIL ") + name + (f"  [{detail}]" if detail else ""))


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        return {"_error": err.code, "_body": err.read().decode()[:200]}


def main():
    options = webdriver.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1400,950")
    options.set_capability("goog:loggingPrefs", {"browser": "ALL"})
    driver = webdriver.Chrome(options=options)
    wait = WebDriverWait(driver, 25)
    project_id = None

    try:
        driver.get(BASE + "/")
        wait.until(EC.visibility_of_element_located((By.ID, "login-form")))
        check("login view renders", True)

        driver.find_element(By.ID, "show-register").click()
        wait.until(EC.visibility_of_element_located((By.ID, "register-form")))
        check("sign-up form opens", driver.find_element(By.ID, "register-form").is_displayed())
        check("login form hidden", not driver.find_element(By.ID, "login-form").is_displayed())
        driver.save_screenshot(os.path.join(SHOTS, "live-signup.png"))

        # client-side validation first
        driver.find_element(By.ID, "reg-name").send_keys(NEW_NAME)
        driver.find_element(By.ID, "reg-email").send_keys(NEW_EMAIL)
        driver.find_element(By.ID, "reg-password").send_keys("short")
        driver.find_element(By.ID, "reg-confirm").send_keys("short")
        driver.find_element(By.ID, "register-button").click()
        time.sleep(0.4)
        error_text = driver.find_element(By.ID, "register-error").text
        check("rejects a weak password client-side", "12 characters" in error_text, error_text[:60])

        # fill it correctly
        pw = driver.find_element(By.ID, "reg-password")
        cf = driver.find_element(By.ID, "reg-confirm")
        pw.clear(); pw.send_keys(NEW_PASSWORD)
        cf.clear(); cf.send_keys(NEW_PASSWORD)
        driver.find_element(By.ID, "register-button").click()

        wait.until(EC.visibility_of_element_located((By.ID, "workspace")))
        check("registered and signed in", True)
        check("display name shown in header", NEW_NAME in driver.find_element(By.ID, "user-label").text)

        # a brand-new account starts with no data and can create its own
        driver.find_element(By.ID, "new-project").click()
        wait.until(EC.visibility_of_element_located((By.ID, "project-dialog")))
        code = driver.find_element(By.ID, "project-code")
        code.send_keys(PROJECT_CODE)
        driver.find_element(By.ID, "project-name").send_keys(PROJECT_NAME)
        driver.find_element(By.CSS_SELECTOR, "#project-form button[type='submit']").click()
        wait.until(lambda d: PROJECT_CODE in d.find_element(By.ID, "project-filter").text)
        check("new user created a project", True)

        driver.find_element(By.ID, "new-task").click()
        wait.until(EC.visibility_of_element_located((By.ID, "task-dialog")))
        Select(driver.find_element(By.ID, "task-project")).select_by_visible_text(
            next(o.text for o in driver.find_elements(By.CSS_SELECTOR, "#task-project option")
                 if PROJECT_CODE in o.text))
        driver.find_element(By.ID, "task-title").send_keys(TASK_TITLE)
        driver.find_element(By.CSS_SELECTOR, "#task-form button[type='submit']").click()
        wait.until(lambda d: TASK_TITLE in d.find_element(By.ID, "task-rows").text)
        check("new user created a task", True)
        driver.save_screenshot(os.path.join(SHOTS, "live-signup-workspace.png"))

        # role check via API
        auth = api("/api/v1/auth/login", "POST", {"email": NEW_EMAIL, "password": NEW_PASSWORD})
        check("registered account is MEMBER (not admin)",
              auth.get("user", {}).get("role") == "MEMBER", str(auth.get("user", {}).get("role")))
        project_id = next((p["id"] for p in api("/api/v1/projects", token=auth["accessToken"])["content"]
                           if p["code"] == PROJECT_CODE), None)
        check("new user only sees their own project",
              len(api("/api/v1/projects", token=auth["accessToken"])["content"]) == 1)

        # duplicate email is refused with a friendly message
        driver.find_element(By.ID, "logout").click()
        wait.until(EC.visibility_of_element_located((By.ID, "login-form")))
        driver.find_element(By.ID, "show-register").click()
        wait.until(EC.visibility_of_element_located((By.ID, "register-form")))
        def fill(field_id, value):
            element = driver.find_element(By.ID, field_id)
            element.clear()
            element.send_keys(value)

        fill("reg-name", "Duplicate")
        fill("reg-email", NEW_EMAIL)          # may be prefilled by the sign-in form
        fill("reg-password", NEW_PASSWORD)
        fill("reg-confirm", NEW_PASSWORD)
        driver.find_element(By.ID, "register-button").click()
        wait.until(lambda d: "already registered" in d.find_element(By.ID, "register-error").text)
        check("duplicate email shows a clear error", True)

    finally:
        driver.quit()
        # ---- cleanup: remove seeded project, disable the throwaway account
        admin = api("/api/v1/auth/login", "POST", {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
        admin_token = admin.get("accessToken")
        if admin_token and project_id:
            api(f"/api/v1/projects/{project_id}", "DELETE", token=admin_token)
        new_user = api("/api/v1/auth/login", "POST", {"email": NEW_EMAIL, "password": NEW_PASSWORD})
        uid = new_user.get("user", {}).get("id")
        if admin_token and uid:
            res = api(f"/api/v1/users/{uid}/status", "PATCH", {"enabled": False}, admin_token)
            print(f"  cleanup: test account disabled={res.get('enabled')}")

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
