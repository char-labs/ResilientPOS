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
MIGRATION = re.compile(r'(?:^|/)db/migration/V([0-9._]+)__[^/]*\.sql$')
# Flyway가 읽는 경로 전부다. 확장자 대소문자를 가리지 않고, 하위 디렉터리와 R(반복) 접두,
# db.migration 패키지의 Java·Kotlin 마이그레이션도 읽는다. src 밖의 예시 SQL은 대상이 아니다.
SCHEMA_CHANGE = re.compile(
    r'^src/[^/]+/(?:resources/db/migration/.+\.sql|(?:kotlin|java)/db/migration/.+\.(?:kt|java))$', re.I)
FENCE = re.compile(r'^(```|~~~).*?^\1[^\n]*$', re.M | re.S)
SCHEMA_HEADING = re.compile(r'^(#{2,3}) 스키마 변경 검토(?:[ \t][^\n]*)?\n', re.M)
SCHEMA_RECORD = re.compile(r'^- 대조 기록:[ \t]*\S', re.M)


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def tree(ref):
    paths = git('ls-tree', '-r', '--name-only', '-z', ref).decode().split('\0')
    return {p: git('show', f'{ref}:{p}') for p in paths if p}


def migration_version(path):
    """Flyway는 버전을 숫자로 비교한다. V001과 V1은 같은 버전이다."""
    matched = MIGRATION.search(path)
    if not matched:
        return None
    try:
        return tuple(int(part) for part in re.split(r'[._]', matched.group(1)) if part)
    except ValueError:
        return None


def remote_is_ahead(base_ref, head):
    """기준 브랜치의 원격이 head에 반영되지 않았으면 이 검사 결과는 낡은 기준 위에서 나온 것이다."""
    try:
        remote = git('rev-parse', '--verify', f'origin/{base_ref}').decode().strip()
    except subprocess.CalledProcessError:
        return False
    try:
        git('merge-base', '--is-ancestor', remote, head)
        return False
    except subprocess.CalledProcessError:
        return True


def tracked_paths(ref):
    return {p for p in git('ls-tree', '-r', '--name-only', '-z', ref).decode().split('\0') if p}


def migration_conflicts(paths):
    """같은 버전 마이그레이션이 둘 이상 머지되면 배포된 애플리케이션이 기동에서 죽는다."""
    versions = {}
    for path in paths:
        version = migration_version(path)
        if version:
            versions.setdefault(version, set()).add(path)
    return [
        f'Flyway 버전 {".".join(str(number) for number in version)} 마이그레이션이 여럿입니다: '
        f'{", ".join(sorted(conflicting))}'
        for version, conflicting in sorted(versions.items()) if len(conflicting) > 1
    ]


def sections(content):
    parts = re.split(r'^## (.+)\n', content, flags=re.M)
    return dict(zip(parts[1::2], parts[2::2]))


def schema_review(content):
    """코드 블록을 뺀 spec에서 스키마 변경 검토 절의 본문을 돌려준다. 절이 없으면 None이다."""
    text = FENCE.sub('', content)
    heading = SCHEMA_HEADING.search(text)
    if not heading:
        return None
    rest = text[heading.end():]
    # 같은 수준이나 더 높은 수준의 제목에서 절이 끝난다. 하위 제목은 본문이다.
    end = re.search(rf'^#{{1,{len(heading.group(1))}}} ', rest, flags=re.M)
    return rest[:end.start()] if end else rest


def check(base, head='HEAD', worktree=False, migrations_only=False):
    os.chdir(git('rev-parse', '--show-toplevel').decode().strip())
    base_ref = base
    if migrations_only:
        # 워크플로는 머지 결과 위에서 돌아 spec 첫 커밋 판정이 달라진다. 버전 중복만 본다.
        conflicts = migration_conflicts(tracked_paths(head) | tracked_paths(base_ref))
        return conflicts, [], f'마이그레이션 버전 검사 전용 (기준 {base_ref})'
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
        if any(SCHEMA_CHANGE.search(p) for p in changed):
            body = schema_review(after[spec].decode('utf-8')) or ''
            if not re.sub(r'^#+ .*$', '', body, flags=re.M).strip():
                errors.append('마이그레이션을 바꾸는 PR은 spec에 "스키마 변경 검토" 절과 답이 필요합니다: docs/wiki/domain-model.md')
            elif '<' in body:
                errors.append('스키마 변경 검토 절에 placeholder가 남아 있습니다. 부등호 여는 문자는 코드 블록 안에만 씁니다.')
            elif not SCHEMA_RECORD.search(body):
                # 독립 대조는 PR 제출 직전에 하므로 개발 중(--worktree)에는 막지 않는다.
                (warnings if worktree else errors).append(
                    '스키마 변경 검토 절에 "- 대조 기록: 리뷰어, 시점, 불일치 항목" 줄이 필요합니다'
                    '(줄 맨 앞 하이픈, 반각 콜론, 내용 필수). 구현자가 아닌 리뷰어가 남깁니다.')
        log = 'docs/wiki/changes.md'
        new_log = after.get(log, b'').decode('utf-8')
        added = '\n'.join(line[2:] for line in difflib.ndiff(
            before.get(log, b'').decode('utf-8').splitlines(), new_log.splitlines()) if line.startswith('+ '))
        if log not in changed or f'../spec/{Path(spec).name}' not in added or '요구사항 영향:' not in added:
            errors.append('wiki 변경 기록에 이번 spec 링크와 요구사항 영향: 설명을 추가하세요.')
    errors.extend(migration_conflicts(set(after) | tracked_paths(base_ref)))
    if remote_is_ahead(base_ref, head):
        warnings.append(f'기준의 원격 origin/{base_ref}이 head에 없습니다. fetch/rebase 후 다시 실행하세요.')
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
    parser.add_argument('--migrations-only', action='store_true',
                        help='Flyway 버전 중복만 검사 (워크플로용)')
    args = parser.parse_args()
    try:
        errors, warnings, stats = check(args.base, args.head, args.worktree, args.migrations_only)
    except (subprocess.CalledProcessError, UnicodeError, OSError) as exc:
        parser.exit(1, f'검사 실패: {exc}\n')
    print(stats)
    for message in warnings:
        print(f'WARN: {message}')
    for message in errors:
        print(f'FAIL: {message}')
    if not errors:
        print('PASS: 마이그레이션 버전' if args.migrations_only else 'PASS: PR contract')
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
