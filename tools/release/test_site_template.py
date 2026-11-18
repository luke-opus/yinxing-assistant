from pathlib import Path
import tempfile
import unittest

from site_template import render_site

TEMPLATE = Path(__file__).resolve().parents[2] / 'web/main'


class SiteTemplateTest(unittest.TestCase):
    def test_disabled_preview_becomes_versioned_verified_release_without_mutating_source(self):
        before = (TEMPLATE / 'index.html').read_bytes()
        files = render_site(TEMPLATE, '0.3.5', 'XAdSkip-0.3.5.apk', 4_321_000, 'a' * 64)
        page = files['index.html'].decode()
        script = files['app-0.3.5.js'].decode()
        self.assertIn('data-release-ready="true"', page)
        self.assertNotIn('release-preview-notice', page)
        self.assertNotIn('{{', page)
        self.assertIn('4.3 MB', page)
        self.assertIn('a' * 64, page)
        self.assertIn('downloads/XAdSkip-0.3.5-source.zip', page)
        self.assertIn('downloads/XAdSkip-0.3.5.apk', script)
        self.assertNotIn('XAdSkip-0.3.4', page + script)
        self.assertIn('icon-0.3.5.svg', page)
        self.assertEqual(before, (TEMPLATE / 'index.html').read_bytes())

    def test_unverified_or_already_rendered_inputs_cannot_be_reused(self):
        with self.assertRaises(ValueError):
            render_site(TEMPLATE, '0.3.5', 'wrong.apk', 1, 'a' * 64)
        with self.assertRaises(ValueError):
            render_site(TEMPLATE, '0.3.5', 'XAdSkip-0.3.5.apk', 0, 'a' * 64)
        with self.assertRaises(ValueError):
            render_site(TEMPLATE, '0.3.5', 'XAdSkip-0.3.5.apk', 1, 'bad')
        files = render_site(TEMPLATE, '0.3.5', 'XAdSkip-0.3.5.apk', 1, 'a' * 64)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / 'index.html').write_bytes(files['index.html'])
            with self.assertRaises(ValueError):
                render_site(path, '0.3.6', 'XAdSkip-0.3.6.apk', 1, 'a' * 64)


if __name__ == '__main__':
    unittest.main()
