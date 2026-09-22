#!/usr/bin/env python3
"""Dependency-free PR contract check. Run from any directory within the repository."""
import argparse
import datetime
import difflib
import os
import re
import subprocess
from pathlib import Path

SPEC = re.compile(r"docs/spec/(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
SECTIONS = ('목적', '범위', '제외', '변경 경로', '인수 조건', '검증', 'Wiki 영향', '분할 계획', '결과')
# 조건과 메시지가 같은 값을 쓴다. 숫자를 두 곳에 적으면 조건만 바꿔도 메시지를 보는 단언이 통과한다.
WARN_LINES = 900
FAIL_LINES = 1400
# Flyway가 읽는 경로 전부다. 확장자 대소문자를 가리지 않고, 하위 디렉터리와 R(반복) 접두,
# db.migration 패키지의 Java·Kotlin 마이그레이션도 읽는다. src 밖의 예시 SQL은 대상이 아니다.
SCHEMA_CHANGE = re.compile(
    r'^(?:.+/)?src/[^/]+/(?:resources/db/migration/.+\.sql|(?:kotlin|java)/db/migration/.+\.(?:kt|java))$', re.I)
# 버전 중복 검사는 SCHEMA_CHANGE가 인정한 경로에서만 버전을 뽑는다. 두 판정이 갈라지면
# 스키마 검토는 요구하면서 버전 중복은 놓치는 경로가 생긴다(대문자 확장자, 하위 디렉터리, Kotlin·Java).
MIGRATION = re.compile(r'(?:^|/)V([0-9._]+)__', re.I)
FENCE = re.compile(r'^(```|~~~).*?^\1[^\n]*$', re.M | re.S)
FENCE_OPEN = re.compile(r'^(?:```|~~~)', re.M)
SCHEMA_HEADING = re.compile(r'^(#{2,3}) 스키마 변경 검토(?:[ \t][^\n]*)?\n', re.M)
SCHEMA_RECORD = re.compile(r'^- 대조 기록:[ \t]*\S', re.M)


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def tree(ref):
    paths = git('ls-tree', '-r', '--name-only', '-z', ref).decode().split('\0')
    return {p: git('show', f'{ref}:{p}') for p in paths if p}


def migration_version(path):
    """Flyway는 버전을 숫자로 비교한다. V001과 V1은 같은 버전이다. 버전이 없는 R(반복)은 대상이 아니다."""
    if not SCHEMA_CHANGE.match(path):
        return None
    matched = MIGRATION.search(Path(path).name)
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


def path_matcher(pattern):
    """glob을 정규식으로 옮긴다. 별표 하나는 한 경로 조각 안에서만, 별표 둘은 조각을 넘어 매칭한다.

    `fnmatch`의 별표는 경로 구분자를 넘어서, 변경 경로에 별표 하나만 적으면 범위 검사가 무력해진다.
    Python 3.13의 `PurePath.full_match`가 같은 일을 하지만 이 저장소는 3.9에서도 돌아야 한다.
    """
    out, i = [], 0
    while i < len(pattern):
        char = pattern[i]
        if char == '*':
            if pattern[i:i + 2] == '**':
                out.append('.*')
                i += 2
                continue
            out.append('[^/]*')
        elif char == '?':
            out.append('[^/]')
        else:
            out.append(re.escape(char))
        i += 1
    return re.compile(''.join(out) + r'\Z')


def fenced_spans(content):
    """코드 블록이 차지하는 구간이다. 닫는 표시가 없으면 여는 표시부터 문서 끝까지 코드 블록으로 본다."""
    spans = [match.span() for match in FENCE.finditer(content)]
    unclosed = next(
        (match for match in FENCE_OPEN.finditer(content)
         if not any(start <= match.start() < end for start, end in spans)), None)
    return spans + [(unclosed.start(), len(content))] if unclosed else spans


def strip_fences(content):
    kept, last = [], 0
    for start, end in sorted(fenced_spans(content)):
        kept.append(content[last:start])
        last = max(last, end)
    kept.append(content[last:])
    return ''.join(kept)


def sections(content):
    """필수 절의 본문을 돌려준다. 코드 블록 안의 제목은 절이 아니다(스키마 검토 판정과 같은 기준).

    본문은 원문 그대로다. 코드 블록을 지운 문자열로 본문까지 판정하면 명령만 적은 절이 빈 절로 보인다.
    """
    spans = fenced_spans(content)
    headings = [match for match in re.finditer(r'^## (.+)$', content, flags=re.M)
                if not any(start <= match.start() < end for start, end in spans)]
    found, names = {}, []
    for index, heading in enumerate(headings):
        name = heading.group(1).strip()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(content)
        names.append(name)
        found.setdefault(name, content[heading.end():end])
    duplicates = sorted({name for name in names if names.count(name) > 1})
    return found, duplicates


def schema_review(content):
    """코드 블록을 뺀 spec에서 스키마 변경 검토 절의 본문을 돌려준다. 절이 없으면 None이다."""
    text = strip_fences(content)
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
        try:
            merge_base = git('merge-base', base_ref, head).decode().strip()
            removed = tracked_paths(merge_base) - tracked_paths(head)
        except subprocess.CalledProcessError:
            # 얕은 클론처럼 공통 조상을 못 찾는 경우다. 이 검사가 그 이유로 빨개지지 않게 이전 동작으로 돌아간다.
            removed = set()
        conflicts = migration_conflicts((tracked_paths(head) | tracked_paths(base_ref)) - removed)
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
                initial, _ = sections(first[spec].decode('utf-8'))
                for name in SECTIONS:
                    if not initial.get(name, '').strip() or '<' in initial.get(name, ''):
                        errors.append(f'최초 spec 항목 미작성: {name}')
        doc, duplicates = sections(after[spec].decode('utf-8'))
        if duplicates:
            # 리뷰어는 앞 절을 읽고 검사기는 뒤 절을 쓰게 된다. 어느 쪽을 쓸지 고르지 않고 spec을 고치게 한다.
            errors.append(f'spec에 같은 절 이름이 중복됩니다: {", ".join(duplicates)}')
        for name in SECTIONS:
            if not doc.get(name, '').strip() or '<' in doc.get(name, ''):
                errors.append(f'spec 항목 미작성: {name}')
        patterns = re.findall(r'^- (\S+)\s*$', doc.get('변경 경로', ''), flags=re.M)
        if not patterns:
            errors.append('spec에 변경 경로 목록이 필요합니다.')
        # 별표 하나를 막으면 별표 둘로 되살아난다. 리터럴이 없는 패턴은 범위를 선언하지 않는다.
        toothless = [p for p in patterns if not re.sub(r'[*?/]', '', p)]
        if toothless:
            errors.append(f'범위를 좁히지 않는 변경 경로 패턴입니다: {", ".join(sorted(toothless))}')
        for p in changed:
            if p != spec and not any(path_matcher(pattern).match(p) for pattern in patterns):
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
    # 이 PR이 지운 경로는 뺀다. 그러지 않으면 파일명만 바꾸는 변경이 옛 이름과 새 이름의 중복으로 잡힌다.
    errors.extend(migration_conflicts((set(after) | tracked_paths(base_ref)) - (set(before) - set(after))))
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
    # 파일 수는 리뷰 부담을 재지 못한다. 한 줄씩 바뀐 25개 파일과 서른 줄씩 바뀐 25개 파일은 다른 일이다.
    # 통계 줄에는 계속 내보내 사람이 보게 두고, 통과/실패는 줄 수로만 가른다.
    if regular_lines > FAIL_LINES:
        errors.append(f'PR 상한 {FAIL_LINES}줄 초과: 후속 PR로 분할하세요.')
    elif regular_lines >= WARN_LINES:
        warnings.append(f'{WARN_LINES}줄 도달: 남은 개발은 후속 PR로 분할하세요.')
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
