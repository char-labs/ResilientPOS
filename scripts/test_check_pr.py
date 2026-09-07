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

    def test_first_spec_must_be_filled(self):
        self.write(self.spec, '# empty')
        self.commit()
        self.implementation()
        self.assertIn('최초 spec 항목 미작성', self.errors(True))


if __name__ == '__main__':
    unittest.main()
