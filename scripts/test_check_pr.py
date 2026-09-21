import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from check_pr import check, SECTIONS


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Harness test')
        self.git('config', 'user.email', 'harness@example.invalid')
        self.write('README.md', 'initial\n')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.spec = 'docs/spec/2026-09-07-example.md'

    def tearDown(self):
        os.chdir(self.previous)
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.PIPE).decode()

    def write(self, path, content):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(content)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'test')

    def start(self):
        self.write(self.spec, '\n'.join(f'## {s}\n' + ('- docs/**\n- src/**' if s == '변경 경로' else '구체적인 내용') for s in SECTIONS))
        self.commit()

    def implementation(self):
        self.write('src/example.txt', 'implementation\n')

    def errors(self, worktree=False):
        return '\n'.join(check(self.base, worktree=worktree)[0])

    def warnings(self, worktree=False):
        return '\n'.join(check(self.base, worktree=worktree)[1])

    def migration(self, name, statement='select 1;\n'):
        self.write(f'src/main/resources/db/migration/{name}', statement)

    REVIEW = '\n### 스키마 변경 검토\n- 필요성: 기존 컬럼으로 도출할 수 없다.\n'
    RECORD = '- 대조 기록: 2026-09-07, code-reviewer, 불일치 없음.\n'

    def add_to_spec(self, text):
        content = Path(self.spec).read_text()
        self.write(self.spec, content.replace('## 검증\n', f'{text}\n## 검증\n', 1))

    def test_valid_history_and_worktree(self):
        self.start()
        self.implementation()
        self.assertEqual('', self.errors(True))
        self.commit()
        self.assertEqual('', self.errors())

    def test_missing_spec(self):
        self.implementation()
        self.commit()
        self.assertIn('정확히 한 개', self.errors())

    def test_same_commit_spec(self):
        self.start()
        self.git('reset', '--soft', self.base)
        self.implementation()
        self.commit()
        self.assertIn('첫 커밋', self.errors())

    def test_uncommitted_spec(self):
        self.start()
        self.git('reset', '--soft', self.base)
        self.implementation()
        self.assertIn('독립 커밋', self.errors(True))

    def test_scope_and_untracked(self):
        self.start()
        self.implementation()
        self.write('unexpected.txt', 'x')
        self.assertIn('범위 밖', self.errors(True))

    def test_multiple_specs(self):
        self.start()
        self.write('docs/spec/2026-09-07-second.md', 'another')
        self.commit()
        self.assertIn('정확히 한 개', self.errors())

    def test_size_and_warning(self):
        self.start()
        self.implementation()
        self.write('src/large.txt', 'line\n' * 600)
        self.assertTrue(check(self.base, worktree=True)[1])
        self.write('src/large.txt', 'line\n' * 801)
        self.assertIn('800줄 초과', self.errors(True))

    def test_file_limit(self):
        self.start()
        self.implementation()
        for i in range(26):
            self.write(f'src/file-{i}.txt', 'x')
        self.assertIn('800줄 초과', self.errors(True))

    def test_binary_rejected(self):
        self.start()
        self.implementation()
        Path('src/binary').write_bytes(b'\x00')
        self.assertIn('바이너리', self.errors(True))

    def test_nested_directory(self):
        self.start()
        self.implementation()
        os.chdir('src')
        self.assertEqual('', self.errors(True))

    def test_crlf_checkout(self):
        self.write('windows.txt', 'line\n')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.start()
        self.implementation()
        Path('windows.txt').write_bytes(b'line\r\n')
        self.assertEqual('', self.errors(True))

    def test_archive_bound(self):
        self.start()
        self.implementation()
        self.write('docs/references/source.md', 'source\n' * 5001)
        self.assertIn('5000줄 초과', self.errors(True))

    def test_archive_immutable(self):
        self.write('docs/references/source.md', 'original')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.start()
        self.implementation()
        self.write('docs/references/source.md', 'modified')
        self.assertIn('신규 Markdown', self.errors(True))

    def test_non_dated_spec_file_is_not_counted_as_new_spec(self):
        self.start()
        self.implementation()
        self.write('docs/spec/DESIGN.md', '# 장기 스펙\n')
        self.write('docs/spec/concerns/order.md', '# concern\n')
        self.assertEqual('', self.errors(True))
        self.commit()
        self.assertEqual('', self.errors())

    def test_first_spec_must_be_filled(self):
        self.write(self.spec, '# empty')
        self.commit()
        self.implementation()
        self.assertIn('최초 spec 항목 미작성', self.errors(True))




class MigrationVersionTest(HarnessTest):
    """같은 Flyway 버전이 둘 이상이면 배포 앱이 기동에 실패한다. 머지 전에 잡는다."""

    def test_distinct_versions_pass(self):
        self.start()
        self.implementation()
        self.migration('V001__baseline.sql')
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + self.RECORD)
        self.commit()
        self.assertEqual('', self.errors())

    def test_duplicate_version_fails(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.migration('V002__payment_create_attempts.sql')
        self.commit()
        failure = self.errors()
        self.assertIn('Flyway 버전 2', failure)
        self.assertIn('V002__payment_create_attempts.sql', failure)

    def test_leading_zero_is_same_version(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.migration('V2__payment_create_attempts.sql')
        self.commit()
        self.assertIn('Flyway 버전 2', self.errors())

    def test_collision_with_base_branch_fails(self):
        """PR 안에서만 보면 중복이 없어도 기준 브랜치와 겹치면 잡아야 한다."""
        self.migration('V002__order_create_orders.sql')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.start()
        self.implementation()
        self.migration('V002__payment_create_attempts.sql')
        self.commit()
        self.assertIn('Flyway 버전 2', self.errors())

    def test_stale_base_warns(self):
        """기준이 원격보다 뒤처지면 낡은 결과를 최신 판정으로 착각하게 된다."""
        remote = tempfile.TemporaryDirectory()
        self.addCleanup(remote.cleanup)
        self.git('branch', '-M', 'main')
        self.git('clone', '-q', '--bare', '.', f'{remote.name}/origin.git')
        self.git('remote', 'add', 'origin', f'{remote.name}/origin.git')
        self.git('fetch', '-q', 'origin')
        self.base = 'main'
        self.git('switch', '-q', '-c', 'work')
        self.start()
        self.implementation()
        self.commit()
        self.assertEqual('', self.warnings())

        self.git('switch', '-q', 'main')
        self.write('README.md', 'moved on\n')
        self.commit()
        self.git('push', '-q', 'origin', 'main')
        self.git('switch', '-q', 'work')
        self.assertIn('원격', self.warnings())


class MigrationsOnlyModeTest(HarnessTest):
    """워크플로는 머지 커밋 위에서 돌기 때문에 spec 이력 규칙 없이 버전만 본다."""

    def test_reports_duplicates_without_spec_rules(self):
        self.migration('V002__order_create_orders.sql')
        self.migration('V2__payment_create_attempts.sql')
        self.commit()
        errors, _, _ = check(self.base, migrations_only=True)

        self.assertEqual(1, len(errors), errors)
        self.assertIn('Flyway 버전 2', errors[0])

    def test_ignores_missing_spec_and_size_rules(self):
        self.write('src/example.txt', 'spec 없이 큰 변경\n')
        self.migration('V002__order_create_orders.sql')
        self.commit()
        errors, _, _ = check(self.base, migrations_only=True)

        self.assertEqual([], errors)
        self.assertIn('정확히 한 개', '\n'.join(check(self.base)[0]))


class SchemaReviewGateTest(HarnessTest):
    """마이그레이션을 바꾸는 PR은 spec의 스키마 변경 검토 절과 구현자가 아닌 리뷰어의 대조 기록이 있어야 한다."""

    def test_migration_without_review_section_fails(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.assertIn('스키마 변경 검토', self.errors(True))

    def test_review_section_with_record_passes(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + self.RECORD)
        self.assertEqual('', self.errors(True))
        self.commit()
        self.assertEqual('', self.errors())

    def test_missing_record_warns_in_worktree_and_fails_in_history(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW)
        self.assertEqual('', self.errors(True))
        self.assertIn('대조 기록', self.warnings(True))
        self.commit()
        self.assertIn('대조 기록', self.errors())

    def test_empty_or_placeholder_section_fails(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n### 스키마 변경 검토\n')
        self.assertIn('스키마 변경 검토', self.errors(True))
        self.add_to_spec('<질문에 답한다>\n' + self.RECORD)
        self.assertIn('스키마 변경 검토', self.errors(True))

    def test_record_outside_the_section_does_not_count(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + '\n### 다른 절\n' + self.RECORD)
        self.commit()
        self.assertIn('대조 기록', self.errors())

    def test_repeatable_and_deleted_migrations_also_require_review(self):
        self.migration('V002__order_create_orders.sql')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.start()
        self.implementation()
        self.migration('R__order_views.sql')
        self.assertIn('스키마 변경 검토', self.errors(True))
        Path('src/main/resources/db/migration/R__order_views.sql').unlink()
        self.assertEqual('', self.errors(True))
        Path('src/main/resources/db/migration/V002__order_create_orders.sql').unlink()
        self.assertIn('스키마 변경 검토', self.errors(True))

    def test_pr_without_migration_needs_no_review_section(self):
        self.start()
        self.implementation()
        self.assertEqual('', self.errors(True))
        self.assertEqual('', self.warnings(True))

    def test_every_path_flyway_reads_requires_review(self):
        """Flyway는 확장자 대소문자를 가리지 않고 하위 디렉터리와 db.migration 패키지의 Java·Kotlin 마이그레이션도 읽는다."""
        self.start()
        self.implementation()
        for path in ('src/main/resources/db/migration/V003__x.SQL',
                     'src/main/resources/db/migration/order/V003__x.sql',
                     'src/main/kotlin/db/migration/V003__AddX.kt',
                     'src/main/java/db/migration/V003__AddX.java'):
            with self.subTest(path):
                self.write(path, 'x\n')
                self.assertIn('스키마 변경 검토', self.errors(True))
                Path(path).unlink()

    def test_example_sql_outside_src_needs_no_review(self):
        self.start()
        self.implementation()
        self.write('docs/wiki/examples/db/migration/V1__x.sql', 'select 1;\n')
        self.assertEqual('', self.errors(True))

    def test_empty_record_fails(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + '- 대조 기록:   \n')
        self.commit()
        self.assertIn('대조 기록', self.errors())

    def test_fenced_heading_and_record_do_not_count(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n```\n### 스키마 변경 검토\n- 답\n' + self.RECORD + '```\n')
        self.assertIn('스키마 변경 검토', self.errors(True))

    def test_fenced_hash_lines_do_not_cut_the_section(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + '```sql\n## 위반 행 세기\nselect 1;\n```\n' + self.RECORD)
        self.commit()
        self.assertEqual('', self.errors())

    def test_heading_only_body_and_lookalike_heading_fail(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n### 스키마 변경 검토하지 않음\n- 답\n' + self.RECORD)
        self.assertIn('스키마 변경 검토', self.errors(True))
        self.add_to_spec('\n### 스키마 변경 검토\n#### 필요성\n')
        self.assertIn('스키마 변경 검토', self.errors(True))

    def test_record_after_a_top_level_heading_does_not_count(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + '\n# 부록\n' + self.RECORD)
        self.commit()
        self.assertIn('대조 기록', self.errors())

    def test_second_level_section_may_hold_subsections(self):
        """템플릿은 이 절을 필수 절 밖의 독립 ## 절로 둔다. 그 안의 ### 하위 절과 끝의 대조 기록을 본문으로 본다."""
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n## 스키마 변경 검토 (스키마를 바꿀 때만)\n### 필요성\n- 도출할 수 없다.\n### 반영\n- nullable 추가.\n' + self.RECORD)
        self.commit()
        self.assertEqual('', self.errors())

    def test_unfilled_template_section_fails(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n## 스키마 변경 검토 (스키마를 바꿀 때만)\n<질문별 답>\n- 대조 기록: <리뷰어, 시점, 불일치 항목>\n')
        self.assertIn('placeholder', self.errors(True))


class BypassTest(HarnessTest):
    """검사를 통과하면서 규칙을 어길 수 있는 경로가 있으면 실패한다. 하네스 변경은 우회를 따로 본다."""

    def assert_version_conflict_for(self, path):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.write(path, 'select 1;\n')
        self.commit()
        self.assertIn('Flyway 버전 2', self.errors())

    def test_uppercase_extension_is_versioned(self):
        self.assert_version_conflict_for('src/main/resources/db/migration/V002__upper.SQL')

    def test_nested_directory_is_versioned(self):
        self.assert_version_conflict_for('src/main/resources/db/migration/sub/V002__nested.sql')

    def test_kotlin_migration_is_versioned(self):
        self.assert_version_conflict_for('src/main/kotlin/db/migration/V002__Kotlin.kt')

    def test_repeatable_migration_has_no_version(self):
        self.start()
        self.implementation()
        self.migration('R__refresh_view.sql')
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + self.RECORD)
        self.commit()
        self.assertEqual('', self.errors())

    def test_single_star_does_not_cross_path_separator(self):
        """변경 경로에 별표 하나만 적어도 범위 검사가 살아 있어야 한다."""
        self.write(self.spec, '\n'.join(
            f'## {s}\n' + ('- *' if s == '변경 경로' else '구체적인 내용') for s in SECTIONS))
        self.commit()
        self.implementation()
        self.write('src/main/kotlin/OutOfScope.kt', 'class OutOfScope\n')
        self.commit()
        self.assertIn('spec 범위 밖 변경', self.errors())

    def test_double_star_crosses_but_single_star_does_not(self):
        self.write(self.spec, '\n'.join(
            f'## {s}\n' + ('- docs/**\n- src/**\n- scripts/*.py' if s == '변경 경로' else '구체적인 내용')
            for s in SECTIONS))
        self.commit()
        self.implementation()
        self.write('docs/wiki/deep/nested/note.md', 'deep\n')
        self.write('scripts/tool.py', 'pass\n')
        self.commit()
        self.assertEqual('', self.errors())

        self.write('scripts/sub/nested.py', 'pass\n')
        self.commit()
        self.assertIn('scripts/sub/nested.py', self.errors())

    def test_duplicate_section_name_is_rejected(self):
        """같은 이름의 절이 둘이면 리뷰어가 읽는 절과 검사기가 쓰는 절이 달라진다."""
        self.start()
        self.implementation()
        self.add_to_spec('\n## 변경 경로\n- *\n')
        self.commit()
        self.assertIn('절 이름이 중복', self.errors())

    def test_fenced_heading_is_not_a_section(self):
        body = '\n'.join(f'## {s}\n구체적인 내용' for s in SECTIONS if s != '결과')
        body = body.replace('## 변경 경로\n구체적인 내용', '## 변경 경로\n- docs/**\n- src/**')
        self.write(self.spec, body + '\n\n```text\n## 결과\n코드 블록 안이라 절이 아니다\n```\n')
        self.commit()
        self.assertIn('spec 항목 미작성: 결과', self.errors())

    def test_last_section_without_trailing_newline_is_found(self):
        body = '\n'.join(
            f'## {s}\n' + ('- docs/**\n- src/**' if s == '변경 경로' else '구체적인 내용')
            for s in SECTIONS)
        self.write(self.spec, body.rstrip('\n'))
        self.commit()
        self.implementation()
        self.commit()
        self.assertEqual('', self.errors())

    def test_renaming_a_migration_is_not_a_version_conflict(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + self.RECORD)
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()

        self.write(self.spec.replace('example', 'rename'), Path(self.spec).read_text())
        Path(self.spec).unlink()
        self.spec = self.spec.replace('example', 'rename')
        self.git('mv', 'src/main/resources/db/migration/V002__order_create_orders.sql',
                 'src/main/resources/db/migration/V002__order_create_order.sql')
        self.commit()
        self.assertNotIn('Flyway 버전', self.errors())

    def test_renaming_still_detects_a_conflict_added_to_base_later(self):
        """기준 브랜치가 merge-base 이후로 전진해 같은 버전이 생기면 파일명을 바꿨어도 잡는다."""
        self.git('branch', '-M', 'main')
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec(self.REVIEW + self.RECORD)
        self.commit()
        self.base = 'main'
        merge_base = self.git('rev-parse', 'HEAD').strip()

        self.git('checkout', '-qb', 'work')
        self.git('mv', 'src/main/resources/db/migration/V002__order_create_orders.sql',
                 'src/main/resources/db/migration/V002__order_create_order.sql')
        self.commit()

        self.git('checkout', '-q', 'main')
        self.git('reset', '-q', '--hard', merge_base)
        self.migration('V002__payment_create_attempts.sql')
        self.commit()
        self.git('checkout', '-q', 'work')

        self.assertIn('Flyway 버전 2', self.errors())

    def test_pattern_without_literal_is_rejected(self):
        """별표 하나를 막으면 별표 둘로 되살아난다. 리터럴이 없는 패턴은 범위 선언이 아니다."""
        for pattern in ('- **', '- *', '- **/*'):
            with self.subTest(pattern=pattern):
                self.write(self.spec, '\n'.join(
                    f'## {s}\n' + (pattern if s == '변경 경로' else '구체적인 내용') for s in SECTIONS))
                self.commit()
                self.assertIn('범위를 좁히지 않는 변경 경로', self.errors())

    def test_unclosed_fence_hides_nothing(self):
        body = '\n'.join(f'## {s}\n' + ('- docs/**\n- src/**' if s == '변경 경로' else '구체적인 내용')
                         for s in SECTIONS if s != '결과')
        self.write(self.spec, body + '\n\n```text\n## 결과\n닫는 표시가 없다\n')
        self.commit()
        self.assertIn('spec 항목 미작성: 결과', self.errors())

    def test_unclosed_fence_does_not_satisfy_schema_review(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.add_to_spec('\n### 스키마 변경 검토\n```sql\n- 필요성: 답\n'
                         + self.RECORD)
        self.commit()
        self.assertIn('스키마 변경 검토', self.errors())

    def test_section_of_only_a_code_block_is_not_missing(self):
        """검증 절에 명령만 적은 spec이 막히면 안 된다."""
        body = '\n'.join(
            f'## {s}\n' + ('- docs/**\n- src/**' if s == '변경 경로'
                           else '```bash\n./gradlew test\n```' if s == '검증'
                           else '구체적인 내용')
            for s in SECTIONS)
        self.write(self.spec, body)
        self.commit()
        self.implementation()
        self.commit()
        self.assertEqual('', self.errors())

    def test_section_name_whitespace_does_not_evade_duplicate_check(self):
        self.start()
        self.implementation()
        self.add_to_spec('\n## 변경 경로 \n- *\n')
        self.commit()
        self.assertIn('절 이름이 중복', self.errors())

    def test_migration_outside_top_level_src_is_versioned(self):
        self.start()
        self.implementation()
        self.migration('V002__order_create_orders.sql')
        self.write('modules/order/src/main/resources/db/migration/V002__dup.sql', 'select 1;\n')
        self.commit()
        self.assertIn('Flyway 버전 2', self.errors())


if __name__ == '__main__':
    unittest.main()
