#!/usr/bin/env python3
"""End-to-end browser test for the optional web client (Chromium + Selenium).

Assumes the SPA is served at http://127.0.0.1:8090 proxying the API.
Writes screenshots to work/screens/.
"""
import os
import sys
import time

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import Select, WebDriverWait

URL = os.environ.get("SPA_URL", "http://127.0.0.1:8090")
EMAIL = os.environ.get("SPA_EMAIL", "admin@taskhub.local")
PASSWORD = os.environ.get("SPA_PASSWORD", "Admin@12345")
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens")
TASK_TITLE = f"Selenium smoke {int(time.time())}"

os.makedirs(SHOTS, exist_ok=True)


def shot(driver, name):
    driver.save_screenshot(os.path.join(SHOTS, name))
    print(f"  screenshot: {name}")


def main():
    options = webdriver.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1400,900")
    driver = webdriver.Chrome(options=options)
    wait = WebDriverWait(driver, 20)
    failures = []

    def check(name, condition):
        if condition:
            print(f"  PASS {name}")
        else:
            print(f"  FAIL {name}")
            failures.append(name)

    try:
        driver.get(URL)
        wait.until(EC.visibility_of_element_located((By.ID, "login-form")))
        check("login view renders", True)
        shot(driver, "web-login.png")

        driver.find_element(By.ID, "email").send_keys(EMAIL)
        driver.find_element(By.ID, "password").send_keys(PASSWORD)
        driver.find_element(By.ID, "login-button").click()

        wait.until(EC.visibility_of_element_located((By.ID, "workspace")))
        wait.until(lambda d: len(d.find_elements(By.CSS_SELECTOR, "#task-rows tr")) > 0)
        rows = driver.find_elements(By.CSS_SELECTOR, "#task-rows tr")
        check(f"workspace lists tasks ({len(rows)} rows)", len(rows) > 0)
        check("server pill shows host", "127.0.0.1" in driver.find_element(By.ID, "server-pill").text)
        shot(driver, "web-workspace.png")

        # ---- create a task through the UI
        driver.find_element(By.ID, "new-task").click()
        wait.until(EC.visibility_of_element_located((By.ID, "task-dialog")))
        Select(driver.find_element(By.ID, "task-project")).select_by_index(0)
        driver.find_element(By.ID, "task-title").send_keys(TASK_TITLE)
        Select(driver.find_element(By.ID, "task-priority")).select_by_value("CRITICAL")
        driver.find_element(By.CSS_SELECTOR, "#task-form button[type='submit']").click()

        wait.until(lambda d: TASK_TITLE in d.find_element(By.ID, "task-rows").text)
        check("created task appears in the table", True)

        # ---- server-side search finds it
        search = driver.find_element(By.ID, "search")
        search.send_keys(TASK_TITLE)
        time.sleep(0.8)
        wait.until(lambda d: len(d.find_elements(By.CSS_SELECTOR, "#task-rows tr")) == 1)
        check("search narrows to the new task", True)

        # ---- delete it again (confirmation dialog)
        driver.find_element(By.CSS_SELECTOR, "#task-rows tr button.danger").click()
        wait.until(EC.alert_is_present())
        driver.switch_to.alert.accept()
        wait.until(lambda d: TASK_TITLE not in d.find_element(By.ID, "task-rows").text)
        check("deleted task disappears", True)

        # ---- theme toggle
        search.clear()
        time.sleep(0.5)
        driver.find_element(By.ID, "theme-toggle").click()
        check("dark theme applied", driver.find_element(By.TAG_NAME, "html").get_attribute("data-theme") == "dark")
        shot(driver, "web-dark.png")

        # ---- sign out
        driver.find_element(By.ID, "logout").click()
        wait.until(EC.visibility_of_element_located((By.ID, "login-form")))
        check("sign out returns to login", True)

    finally:
        driver.quit()

    if failures:
        print("\nFAILURES: " + ", ".join(failures))
        return 1
    print("\nAll browser checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
