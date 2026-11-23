# Convenience targets. Everything works without make too — see README.md.

.PHONY: help test build run-api run-desktop up down logs smoke backup

help:
	@echo "make test        - backend unit + integration tests, desktop tests"
	@echo "make build       - build both modules (jar + desktop fat jar)"
	@echo "make run-api     - run the API locally on :8080 (dev profile, seeded demo data)"
	@echo "make run-desktop - run the JavaFX client (needs a display)"
	@echo "make up          - docker compose up --build (postgres + api + web)"
	@echo "make down        - docker compose down"
	@echo "make logs        - follow container logs"
	@echo "make smoke       - end-to-end smoke test against BASE_URL (default localhost:8080)"
	@echo "make backup      - pg_dump the configured database"

test:
	cd backend && mvn -B verify
	cd desktop && mvn -B verify

build:
	cd backend && mvn -B -DskipTests package
	cd desktop && mvn -B -DskipTests package

run-api:
	cd backend && mvn -B spring-boot:run -Dspring-boot.run.profiles=dev

run-desktop:
	cd desktop && mvn -B javafx:run

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f api

smoke:
	BASE_URL=$${BASE_URL:-http://localhost:8080} ./scripts/smoke-test.sh

backup:
	./scripts/backup.sh
