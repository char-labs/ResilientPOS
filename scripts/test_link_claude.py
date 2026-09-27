import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest

from link_claude import COPIES, LINKS, VAULT_ENV, main


def run(**kwargs):
    """main을 조용히 실행하고 (종료 코드, 출력)을 돌려준다."""
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = main(**kwargs)
    return code, buffer.getvalue()


class LinkClaudeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / 'repo'
        self.vault = Path(self.tmp.name) / 'vault'
        self.root.mkdir()
        for link, source in LINKS:
            if source.endswith('.md'):
                (self.vault / source).parent.mkdir(parents=True, exist_ok=True)
                (self.vault / source).write_text('x\n')
            else:
                (self.vault / source).mkdir(parents=True)
                (self.vault / source / 'placeholder.md').write_text('x\n')
        for _, source in COPIES:
            (self.vault / source).mkdir(parents=True)
            (self.vault / source / 'rule.md').write_text('rule\n')

    def tearDown(self):
        self.tmp.cleanup()

    def test_links_every_configured_directory(self):
        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        for link, source in LINKS:
            path = self.root / link
            self.assertTrue(path.is_symlink(), link)
            self.assertEqual(path.resolve(), (self.vault / source).resolve())
            self.assertTrue((path if source.endswith('.md') else path / 'placeholder.md').exists(), link)

    def test_links_cover_claude_and_codex(self):
        names = {link for link, _ in LINKS}

        self.assertIn('.claude/skills', names)
        self.assertIn('.claude/agents', names)
        self.assertIn('.codex/agents', names)

    def test_links_cover_vault_wiki_documents(self):
        names = {link for link, _ in LINKS}

        self.assertIn('docs/wiki/current-state.md', names)
        self.assertIn('docs/wiki/modulith-kafka-keywords.md', names)

    def test_running_twice_keeps_the_same_links(self):
        run(root=self.root, vault=self.vault)
        before = {link: os.readlink(self.root / link) for link, _ in LINKS}

        code, _ = run(root=self.root, vault=self.vault)
        after = {link: os.readlink(self.root / link) for link, _ in LINKS}

        self.assertEqual(code, 0)
        self.assertEqual(before, after)

    def test_skips_when_vault_is_missing(self):
        code, output = run(root=self.root, vault=Path(self.tmp.name) / 'absent')

        self.assertEqual(code, 0)
        self.assertIn('absent', output)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_skips_a_directory_the_vault_does_not_have(self):
        first, rest = LINKS[0], LINKS[1:]
        (self.vault / first[1] / 'placeholder.md').unlink()
        (self.vault / first[1]).rmdir()

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        self.assertFalse((self.root / first[0]).exists())
        for link, _ in rest:
            self.assertTrue((self.root / link).is_symlink(), link)

    def test_environment_variable_overrides_the_default_vault(self):
        previous = os.environ.get(VAULT_ENV)
        os.environ[VAULT_ENV] = str(self.vault)
        try:
            code, _ = run(root=self.root)
        finally:
            if previous is None:
                del os.environ[VAULT_ENV]
            else:
                os.environ[VAULT_ENV] = previous

        self.assertEqual(code, 0)
        self.assertTrue((self.root / LINKS[0][0]).is_symlink())

    def test_refuses_to_replace_a_real_directory(self):
        existing = self.root / LINKS[0][0]
        existing.mkdir(parents=True)
        (existing / 'keep.md').write_text('keep\n')

        code, output = run(root=self.root, vault=self.vault)

        self.assertNotEqual(code, 0)
        self.assertIn(LINKS[0][0], output)
        self.assertFalse(existing.is_symlink())
        self.assertTrue((existing / 'keep.md').exists())
        self.assertTrue((self.root / LINKS[1][0]).is_symlink())


    def test_copies_rules_as_real_files(self):
        self.assertNotIn('.claude/rules', {link for link, _ in LINKS})

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        rules = self.root / '.claude/rules'
        self.assertTrue(rules.is_dir())
        self.assertFalse(rules.is_symlink())
        self.assertFalse((rules / 'rule.md').is_symlink())
        self.assertEqual((rules / 'rule.md').read_text(), 'rule\n')

    def test_replaces_rules_symlink_without_touching_vault(self):
        rules = self.root / '.claude/rules'
        rules.parent.mkdir(parents=True)
        rules.symlink_to(self.vault / 'rules', target_is_directory=True)

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        self.assertFalse(rules.is_symlink())
        self.assertEqual((self.vault / 'rules' / 'rule.md').read_text(), 'rule\n')

    def test_mirrors_vault_over_local_edits_and_stale_files(self):
        run(root=self.root, vault=self.vault)
        rules = self.root / '.claude/rules'
        (rules / 'rule.md').write_text('local edit\n')
        (self.vault / 'rules' / 'gone.md').write_text('x\n')
        run(root=self.root, vault=self.vault)
        (self.vault / 'rules' / 'gone.md').unlink()

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        self.assertEqual((rules / 'rule.md').read_text(), 'rule\n')
        self.assertFalse((rules / 'gone.md').exists())

    def test_skips_rules_when_vault_lacks_it(self):
        (self.vault / 'rules' / 'rule.md').unlink()
        (self.vault / 'rules').rmdir()

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        self.assertFalse((self.root / '.claude/rules').exists())
        self.assertTrue((self.root / '.claude/skills').is_symlink())


if __name__ == '__main__':
    unittest.main()
