#
# Makefile — ResilientPOS 개발 명령 단축.
#
# 타깃 설명은 `## ` 주석으로 달고, help 가 그 목록을 그대로 출력합니다.
# 자동완성은 scripts/make-completion.zsh 참고 (선택).
#
.DEFAULT_GOAL := help
.PHONY: help bootstrap build test harness check up down

BASE ?= main
COMPOSE := docker compose -f docker/docker-compose.yaml

help: ## 이 목록 출력
	@printf '\n  ResilientPOS  ·  개발 명령 단축\n\n'
	@grep -hE '^[a-zA-Z][a-zA-Z0-9_-]*:.*## ' $(MAKEFILE_LIST) \
		| sed -E 's/:[^#]*## /|/' \
		| awk -F'|' '{ printf "  %-11s %s\n", $$1, $$2 }'
	@printf '\n'

bootstrap: ## 클론·새 worktree 직후, 볼트 rules 수정 후 — .claude/.codex 링크와 rules 복사
	@python3 scripts/link_claude.py

build: ## 애플리케이션 빌드
	./gradlew build

test: ## 애플리케이션 테스트
	./gradlew test

harness: ## 로컬 하네스 테스트 (Python 표준 라이브러리)
	python3 -m unittest discover -s scripts -p 'test_*.py'

check: ## PR 규칙 검사 (예: make check BASE=main)
	python3 scripts/check_pr.py --base $(BASE) --worktree

up: ## 로컬 PostgreSQL 기동 (앱·LGTM profile은 README 참고)
	$(COMPOSE) up -d --wait

down: ## compose로 띄운 컨테이너를 profile 구분 없이 모두 중지
	$(COMPOSE) --profile '*' down
