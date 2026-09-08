import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest

from link_claude import LINK_NAMES, VAULT_ENV, main


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
        for name in LINK_NAMES:
            (self.vault / name).mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def link(self, name):
        return self.root / '.claude' / name

    def test_links_every_vault_directory(self):
        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        for name in LINK_NAMES:
            self.assertTrue(self.link(name).is_symlink(), name)
            self.assertEqual(self.link(name).resolve(), (self.vault / name).resolve())

    def test_running_twice_keeps_the_same_links(self):
        run(root=self.root, vault=self.vault)
        before = {name: os.readlink(self.link(name)) for name in LINK_NAMES}

        code, _ = run(root=self.root, vault=self.vault)
        after = {name: os.readlink(self.link(name)) for name in LINK_NAMES}

        self.assertEqual(code, 0)
        self.assertEqual(before, after)

    def test_skips_when_vault_is_missing(self):
        code, output = run(root=self.root, vault=Path(self.tmp.name) / 'absent')

        self.assertEqual(code, 0)
        self.assertIn('absent', output)
        self.assertFalse((self.root / '.claude').exists())

    def test_skips_a_directory_the_vault_does_not_have(self):
        (self.vault / LINK_NAMES[0]).rmdir()

        code, _ = run(root=self.root, vault=self.vault)

        self.assertEqual(code, 0)
        self.assertFalse(self.link(LINK_NAMES[0]).exists())
        self.assertTrue(self.link(LINK_NAMES[1]).is_symlink())

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
        self.assertTrue(self.link(LINK_NAMES[0]).is_symlink())

    def test_refuses_to_replace_a_real_directory(self):
        existing = self.link(LINK_NAMES[0])
        existing.mkdir(parents=True)
        (existing / 'keep.md').write_text('keep\n')

        code, output = run(root=self.root, vault=self.vault)

        self.assertNotEqual(code, 0)
        self.assertIn(LINK_NAMES[0], output)
        self.assertFalse(existing.is_symlink())
        self.assertTrue((existing / 'keep.md').exists())
        self.assertFalse((existing / LINK_NAMES[0]).exists())


if __name__ == '__main__':
    unittest.main()
