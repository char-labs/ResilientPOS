#!/usr/bin/env python3
"""`.claude/skills`와 `.claude/agents`를 Obsidian 볼트로 심볼릭 링크한다.

Claude Code가 읽는 스킬과 에이전트 정본은 Obsidian 볼트에 두고 저장소에서는 추적하지 않는다.
이 스크립트가 새 머신에서 링크를 다시 만든다. 실행 시점에 홈 경로를 풀기 때문에 사용자 이름이나
저장소 위치에 의존하지 않는다. 볼트가 없으면 안내만 남기고 정상 종료하므로 CI에서도 안전하다.

    python3 scripts/link_claude.py
    RESILIENTPOS_VAULT=/다른/볼트/경로 python3 scripts/link_claude.py
"""
import os
from pathlib import Path
import sys

LINK_NAMES = ('skills', 'agents')
VAULT_ENV = 'RESILIENTPOS_VAULT'
DEFAULT_VAULT_PARTS = ('Documents', 'Obsidian Vault', 'ResilientPOS')
CLAUDE_DIR = '.claude'


def resolve_vault(vault=None):
    """인자, 환경 변수, 기본 경로 순으로 볼트 위치를 정한다."""
    if vault is not None:
        return Path(vault)
    override = os.environ.get(VAULT_ENV)
    if override:
        return Path(override).expanduser()
    return Path.home().joinpath(*DEFAULT_VAULT_PARTS)


def link_one(link, target):
    """링크 하나를 만든다. (성공 여부, 오류 메시지)를 돌려준다."""
    if link.is_symlink():
        if os.readlink(link) == str(target):
            return True, None
        link.unlink()
    elif link.exists():
        return False, f'  (중단) {link} 가 실제 디렉터리입니다. 옮기거나 지운 뒤 다시 실행하세요.'
    link.symlink_to(target, target_is_directory=True)
    return True, None


def main(root=None, vault=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parent.parent
    vault = resolve_vault(vault)

    if not vault.is_dir():
        print(f'  (건너뜀) Obsidian 볼트 없음: {vault}')
        return 0

    targets = []
    for name in LINK_NAMES:
        target = vault / name
        if target.is_dir():
            targets.append((name, target))
        else:
            print(f'  (건너뜀) {target} 없음')
    if not targets:
        return 0

    claude = root / CLAUDE_DIR
    claude.mkdir(parents=True, exist_ok=True)

    linked, errors = 0, []
    for name, target in targets:
        ok, error = link_one(claude / name, target)
        if error:
            errors.append(error)
        elif ok:
            linked += 1

    for error in errors:
        print(error)
    print(f'  linked {linked}/{len(LINK_NAMES)} -> {vault}')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
