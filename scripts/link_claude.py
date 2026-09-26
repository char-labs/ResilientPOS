#!/usr/bin/env python3
"""저장소의 LLM 도구 디렉터리를 Obsidian 볼트로 심볼릭 링크하고, rules는 실제 파일로 복사한다.

스킬·에이전트 정본은 Obsidian 볼트에 두고 저장소는 추적하지 않는다. `.claude`와 `.codex`는
`.gitignore` 대상이며 이 스크립트가 새 머신에서 링크를 다시 만든다. 링크가 저장소 안에 있으므로
스킬은 이 저장소에서 작업할 때만 적용된다.

Claude Code는 `.claude/rules`가 링크이거나 안의 파일이 링크이면 규칙을 싣지 않는다(2026-09-26 실측).
그래서 rules만 볼트와 똑같이 미러링해 복사하며, 볼트 rules를 고치면 이 스크립트를 다시 실행해야 반영된다.

실행 시점에 홈 경로를 풀기 때문에 사용자 이름이나 저장소 위치에 의존하지 않는다. 볼트가 없으면
안내만 남기고 정상 종료하므로 CI와 새 머신에서 안전하다.

    make bootstrap
    python3 scripts/link_claude.py
    RESILIENTPOS_VAULT=/다른/볼트/경로 python3 scripts/link_claude.py
"""
import os
from pathlib import Path
import shutil
import sys

# (저장소 안 링크 경로, 볼트 안 원본 경로)
LINKS = (
    ('.claude/skills', 'skills'),
    ('.claude/agents', 'agents'),
    ('.codex/agents', 'codex/agents'),
    # PR마다 한 줄씩 바뀌어 병렬 PR이 늘 충돌하던 문서라 저장소 밖에 둔다.
    ('docs/wiki/current-state.md', 'wiki/current-state.md'),
)
# (저장소 안 복사 경로, 볼트 안 원본 경로). 로컬 수정은 덮어쓰고 볼트에 없는 파일은 지운다.
COPIES = (
    ('.claude/rules', 'rules'),
)
VAULT_ENV = 'RESILIENTPOS_VAULT'
DEFAULT_VAULT_PARTS = ('Documents', 'Obsidian Vault', 'ResilientPOS')


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
        return False, f'  (중단) {link} 가 이미 링크가 아닌 실제 경로입니다. 옮기거나 지운 뒤 다시 실행하세요.'
    link.parent.mkdir(parents=True, exist_ok=True)
    link.symlink_to(target, target_is_directory=target.is_dir())
    return True, None


def copy_one(dest, source):
    """source 디렉터리를 dest로 미러링한다. 링크인 dest는 링크만 지워 원본을 보존한다."""
    if dest.is_symlink():
        dest.unlink()
    elif dest.exists():
        shutil.rmtree(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, dest)


def main(root=None, vault=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parent.parent
    vault = resolve_vault(vault)

    if not vault.is_dir():
        print(f'  (건너뜀) Obsidian 볼트 없음: {vault}')
        return 0

    linked, errors = 0, []
    for name, source in LINKS:
        target = vault / source
        if not target.exists():
            print(f'  (건너뜀) {target} 없음')
            continue
        ok, error = link_one(root / name, target)
        if error:
            errors.append(error)
        elif ok:
            linked += 1

    copied = 0
    for name, source in COPIES:
        source = vault / source
        if not source.is_dir():
            print(f'  (건너뜀) {source} 없음')
            continue
        copy_one(root / name, source)
        copied += 1

    for error in errors:
        print(error)
    print(f'  linked {linked}/{len(LINKS)}, copied {copied}/{len(COPIES)} -> {vault}')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
