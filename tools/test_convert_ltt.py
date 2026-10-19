import json
import unittest

from convert_ltt import Unsupported, convert, group_key, java_hash, package_index, selector


class ConversionTests(unittest.TestCase):
    package = 'org.example.reader'

    def run_config(self, config, packages=None):
        return convert([{java_hash(self.package): json.dumps(config)}],
                       packages if packages is not None else {java_hash(self.package): [(self.package, 'Reader')]})

    def test_trigger_is_guard_and_action_is_last_match_target(self):
        sub, report = self.run_config({'popup_rules': [{'id': '=广告', 'action': '=跳过'}]})
        group = sub['apps'][0]['groups'][0]
        self.assertFalse(group['enable'])
        self.assertEqual(group['rules'][0]['matches'], [selector('=广告'), selector('=跳过')])
        self.assertEqual(group['rules'][0]['action'], 'clickNode')
        self.assertEqual(report['convertedRules'], 1)

    def test_hash_uses_java_utf16_and_signed_overflow(self):
        self.assertEqual(java_hash('Aa'), java_hash('BB'))
        self.assertEqual(java_hash('😀'), '1772899')
        self.assertEqual(java_hash('com.tencent.mm'), '-973170826')

    def test_markdown_package_map_preserves_hash_collisions(self):
        idx = package_index('- [Aa](./A/readme.md)（One）\n- [BB](./B/readme.md)（Two）')
        self.assertEqual(len(idx[java_hash('Aa')]), 2)

    def test_nested_markdown_apps_are_not_lost(self):
        idx = package_index('- #### com\n  - [com.tencent.mm](./C/readme.md)（微信）')
        self.assertEqual(idx[java_hash('com.tencent.mm')], [('com.tencent.mm', '微信')])

    def test_unknown_and_ambiguous_package_never_emits_app(self):
        for mapping in [{}, {java_hash(self.package): [('a', 'A'), ('b', 'B')]}]:
            sub, report = self.run_config({'popup_rules': []}, mapping)
            self.assertFalse(sub['apps'])
            self.assertEqual(report['skipped'][0]['reason'], 'ambiguous_or_unknown_package_hash')

    def test_prefix_suffix_and_exact_are_preserved(self):
        self.assertIn('text^=', selector('+以后'))
        self.assertIn('text$=', selector('-再说'))
        self.assertIn('text=', selector('=取消'))
        self.assertIn('text*=', selector('广告'))

    def test_quotes_and_backslashes_stay_in_literal(self):
        value = '广告"\\按钮\n'
        encoded = json.dumps(value, ensure_ascii=True)
        self.assertEqual(selector(value), f'[text*={encoded} || desc*={encoded}]')

    def test_unknown_semantics_do_not_silently_disappear(self):
        for config in [{'popup_rules': [], 'click_way_popup': 1},
                       {'popup_rules': [], 'unite_popup_rules': True}]:
            sub, report = self.run_config(config)
            self.assertFalse(sub['apps'])
            self.assertIn('unsupported_app_options', report['skipped'][0]['reason'])

    def test_coordinates_back_compound_and_repeat_require_review(self):
        rules = [{'id': '=广告', 'action': action} for action in
                 ['10,20,30,40', 'GLOBAL_ACTION_BACK', '还剩&秒']]
        rules.append({'id': '=广告', 'action': '=跳过', 'times': 0})
        sub, report = self.run_config({'popup_rules': rules})
        self.assertFalse(sub['apps'])
        self.assertEqual(len(report['skipped']), 4)

    def test_invalid_rule_is_reported_and_valid_neighbor_survives(self):
        sub, report = self.run_config({'popup_rules': [{'id': None}, {'id': '广告', 'action': '=跳过'}]})
        self.assertEqual(report['convertedRules'], 1)
        self.assertEqual(report['skippedItems'], 1)

    def test_delay_and_stable_key_survive_input_reordering(self):
        rule = {'id': '=广告', 'action': '=跳过', 'delay_popup': 200}
        a, _ = self.run_config({'popup_rules': [rule]})
        b, _ = self.run_config({'popup_rules': [{'id': '更新', 'action': '取消'}, rule]})
        key = group_key(rule)
        self.assertEqual(a['apps'][0]['groups'][0]['key'], key)
        self.assertEqual(next(g for g in b['apps'][0]['groups'] if g['key'] == key)['rules'][0]['actionDelay'], 200)

    def test_duplicate_package_hash_fails_instead_of_overwriting(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            convert([{'1': '{}'}, {'1': '{}'}], {})

    def test_json5_trailing_comma_in_legacy_rule_is_accepted(self):
        data = [{java_hash(self.package): '{popup_rules:[{id:"广告", action:"跳过"},],}'}]
        sub, report = convert(data, {java_hash(self.package): [(self.package, 'Reader')]})
        self.assertEqual(report['convertedRules'], 1)


if __name__ == '__main__':
    unittest.main()
