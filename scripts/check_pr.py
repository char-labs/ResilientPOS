#!/usr/bin/env python3
"""Dependency-free PR contract check. Run from any directory within the repository."""
import argparse
import datetime
import difflib
import fnmatch
import os
import re
import subprocess
from pathlib import Path

SPEC = re.compile(r"docs/spec/(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
SECTIONS = ('목적', '범위', '제외', '변경 경로', '인수 조건', '검증', 'Wiki 영향', '분할 계획', '결과')


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def tree(ref):
    paths = git('ls-tree', '-r', '--name-only', '-z', ref).decode().split('\0')
    return {p: git('show', f'{ref}:{p}') for p in paths if p}


def sections(content):
    parts = re.split(r'^## (.+)\n', content, flags=re.M)
    return dict(zip(parts[1::2], parts[2::2]))


def check(base, head='HEAD', worktree=False):
    os.chdir(git('rev-parse', '--show-toplevel').decode().strip())
    base = git('merge-base', base, head).decode().strip()
    before, after = tree(base), tree(head)
    errors, warnings = [], []
    if worktree:
        root = Path(git('rev-parse', '--show-toplevel').decode().strip())
        paths = git('ls-files', '--cached', '--others', '--exclude-standard', '-z').decode().split('\0')
        after = {}
        for p in paths:
            if not p:
                continue
            path = root / p
            if path.is_symlink():
                errors.append(f'심볼릭 링크는 지원하지 않습니다: {p}')
            elif path.is_file():
                after[p] = path.read_bytes()
    # Git normalizes CRLF text on checkout; compare normalized contents.
    normalize = lambda data: data.replace(b'\r\n', b'\n') if data is not None else None
    changed = sorted(p for p in before.keys() | after.keys()
                     if normalize(before.get(p)) != normalize(after.get(p)))
    specs = [p for p in changed if SPEC.fullmatch(p) and p not in before and p in after]
    if len(specs) != 1:
        errors.append('PR에는 새 docs/spec/yyyy-mm-dd-slug.md spec이 정확히 한 개 필요합니다.')
    commits = git('rev-list', '--reverse', '--topo-order', f'{base}..{head}').decode().splitlines()
    if len(specs) == 1:
        spec = specs[0]
        try:
            datetime.date.fromisoformat(SPEC.fullmatch(spec).group(1))
        except ValueError:
            errors.append('spec 날짜가 유효하지 않습니다.')
        if not commits:
            errors.append('개발 전에 spec 독립 커밋이 필요합니다.')
        else:
            first = tree(commits[0])
            first_changed = [p for p in before.keys() | first.keys() if before.get(p) != first.get(p)]
            if first_changed != [spec] or spec not in first:
                errors.append('첫 커밋은 새 spec 파일 한 개만 포함해야 합니다.')
            else:
                initial = sections(first[spec].decode('utf-8'))
                for name in SECTIONS:
                    if not initial.get(name, '').strip() or '<' in initial.get(name, ''):
                        errors.append(f'최초 spec 항목 미작성: {name}')
        doc = sections(after[spec].decode('utf-8'))
        for name in SECTIONS:
            if not doc.get(name, '').strip() or '<' in doc.get(name, ''):
                errors.append(f'spec 항목 미작성: {name}')
        patterns = re.findall(r'^- (\S+)\s*$', doc.get('변경 경로', ''), flags=re.M)
        if not patterns:
            errors.append('spec에 변경 경로 목록이 필요합니다.')
        for p in changed:
            if p != spec and not any(fnmatch.fnmatchcase(p, pattern) for pattern in patterns):
                errors.append(f'spec 범위 밖 변경: {p}')
        log = 'docs/wiki/changes.md'
        new_log = after.get(log, b'').decode('utf-8')
        added = '\n'.join(line[2:] for line in difflib.ndiff(
            before.get(log, b'').decode('utf-8').splitlines(), new_log.splitlines()) if line.startswith('+ '))
        if log not in changed or f'../spec/{Path(spec).name}' not in added or '요구사항 영향:' not in added:
            errors.append('wiki 변경 기록에 이번 spec 링크와 요구사항 영향: 설명을 추가하세요.')
    regular_files = regular_lines = source_files = source_lines = 0
    for p in changed:
        old, new = before.get(p, b''), after.get(p, b'')
        try:
            if b'\0' in old + new:
                raise UnicodeError()
            a, b = old.decode('utf-8').splitlines(), new.decode('utf-8').splitlines()
        except UnicodeError:
            errors.append(f'바이너리/비 UTF-8 변경은 별도 정책 검토가 필요합니다: {p}')
            continue
        lines = sum(tag != 'equal' and (i2 - i1 + j2 - j1) or 0
                    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes())
        if p.startswith('docs/references/'):
            if p in before or not p.endswith('.md'):
                errors.append(f'원문 보관은 신규 Markdown만 허용합니다: {p}')
            source_files += 1
            source_lines += lines
        else:
            regular_files += 1
            regular_lines += lines
    if regular_files > 25 or regular_lines > 800:
        errors.append('PR 상한 25개 파일/800줄 초과: 후속 PR로 분할하세요.')
    elif regular_files >= 20 or regular_lines >= 600:
        warnings.append('20개 파일/600줄 도달: 남은 개발은 후속 PR로 분할하세요.')
    if source_files > 10 or source_lines > 5000:
        errors.append('원문 보관 상한 10개 파일/5000줄 초과')
    return errors, warnings, f'일반 {regular_files}개/{regular_lines}줄; 원문 {source_files}개/{source_lines}줄'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='PR base ref/SHA')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--worktree', action='store_true')
    args = parser.parse_args()
    try:
        errors, warnings, stats = check(args.base, args.head, args.worktree)
    except (subprocess.CalledProcessError, UnicodeError, OSError) as exc:
        parser.exit(1, f'검사 실패: {exc}\n')
    print(stats)
    for message in warnings:
        print(f'WARN: {message}')
    for message in errors:
        print(f'FAIL: {message}')
    if not errors:
        print('PASS: PR contract')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
