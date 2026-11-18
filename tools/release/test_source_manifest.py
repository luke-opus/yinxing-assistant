"""Prevent incomplete corresponding-source ZIPs before files have been committed."""
import hashlib
import json
from pathlib import Path
import unittest

from prepare_release import REPO, source_paths


class SourceManifestTest(unittest.TestCase):
    def test_complete_fixed_upstream_sources_and_new_build_inputs_are_included(self):
        names = {name for name, _ in source_paths()}
        snapshots = json.loads((REPO / 'rules/community-snapshots.json').read_text())['sources']
        for source in snapshots.values():
            for relative, expected_hash in source['files'].items():
                path = Path(source['path']) / relative
                self.assertIn(str(path), names)
                self.assertEqual(hashlib.sha256((REPO / path).read_bytes()).hexdigest(), expected_hash)
        for name in (
            'gkd-app/src/main/assets/xadskip/unified/release-public.der',
            'rules/unified-identities.json', 'rules/unified-manifest-payload.json',
            'gkd-db/schemas/li.gkd.db.AppDb/17.json',
            'web/main/index.html', 'web/feedback/feedback.js',
            'services/feedback/feedback_server.py', 'tools/release/sign_apk.py',
        ):
            self.assertIn(name, names)


if __name__ == '__main__':
    unittest.main()
