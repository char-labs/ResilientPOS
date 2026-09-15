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
        self.write('docs/wiki/changes.md', '# 기록\n[spec](../spec/2026-09-07-example.md)\n요구사항 영향: 기능 추가\n')

    def errors(self, worktree=False):
        return '\n'.join(check(self.base, worktree=worktree)[0])

    def warnings(self, worktree=False):
        return '\n'.join(check(self.base, worktree=worktree)[1])

    def migration(self, name, statement='select 1;\n'):
        self.write(f'src/main/resources/db/migration/{name}', statement)

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

    def test_wiki_missing(self):
        self.start()
        self.write('src/example.txt', 'x')
        self.commit()
        self.assertIn('wiki 변경 기록', self.errors())

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


if __name__ == '__main__':
    unittest.main()


class MigrationVersionTest(HarnessTest):
    """같은 Flyway 버전이 둘 이상이면 배포 앱이 기동에 실패한다. 머지 전에 잡는다."""

    def test_distinct_versions_pass(self):
        self.start()
        self.implementation()
        self.migration('V001__baseline.sql')
        self.migration('V002__order_create_orders.sql')
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
