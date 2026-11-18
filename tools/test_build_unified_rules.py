import copy
import unittest

from build_unified_rules import build, identity_key


def source(key, groups, categories=None):
    return {'key': key, 'name': key, 'subscriptionId': {'ganlinte': 11, 'aoguai': 12, 'ltt': 13}[key],
            'version': 1, 'license': 'test', 'data': {'apps': [{'id': 'example.app', 'name': 'Example', 'groups': groups}],
            'categories': categories or []}}


def group(key=0, **extra):
    return {'key': key, 'name': '开屏广告', 'rules': [{'key': 0, 'matches': '[text="跳过"]'}], **extra}


class UnifiedRuleBuilderTest(unittest.TestCase):
    def test_exact_duplicates_preserve_both_origins_and_stricter_default(self):
        result, _, report = build([source('ganlinte', [group()]), source('aoguai', [group(enable=False)])], 1)
        self.assertEqual(len(result['apps'][0]['groups']), 1)
        self.assertFalse(result['apps'][0]['groups'][0]['enable'])
        self.assertEqual(len(result['xadskip']['groupMappings']), 2)
        self.assertEqual(report['exactDuplicateGroupsRemoved'], 1)

    def test_distinct_actions_with_same_trigger_are_retained_but_not_both_enabled(self):
        first = group()
        second = group()
        second['rules'][0]['action'] = 'back'
        result, _, report = build([source('ganlinte', [first]), source('aoguai', [second])], 1)
        groups = result['apps'][0]['groups']
        self.assertEqual(len(groups), 2)
        self.assertTrue(groups[0]['enable'])
        self.assertFalse(groups[1]['enable'])
        self.assertEqual(report['overlappingGroupsKeptDisabled'], 1)

    def test_scope_group_keys_are_remapped_while_rule_dependencies_stay_intact(self):
        dependent = group(0, scopeKeys=[13], actionMaximumKey=7)
        dependent['rules'] = [{'key': 8, 'preKeys': [7], 'matches': '[text="继续"]'}]
        target = group(13)
        target['rules'][0]['key'] = 7
        result, _, _ = build([source('aoguai', [dependent, target])], 1)
        first, second = result['apps'][0]['groups']
        self.assertEqual(first['scopeKeys'], [second['key']])
        self.assertEqual(first['actionMaximumKey'], 7)
        self.assertEqual(first['rules'][0]['preKeys'], [7])
        self.assertEqual(second['rules'][0]['key'], 7)

    def test_source_category_disable_and_non_ad_rules_are_never_default_enabled(self):
        result, _, _ = build([source('ganlinte', [group(enable=True), group(3, name='权限提示')],
                                         [{'key': 0, 'name': '开屏广告', 'enable': False}])], 1)
        self.assertTrue(all(g['enable'] is False for g in result['apps'][0]['groups']))
        self.assertTrue(all('enable' not in c for c in result['categories']))

    def test_ltt_drafts_cannot_become_enabled_or_claim_ad_coverage(self):
        result, catalog, _ = build([source('ltt', [group(enable=False)])], 1)
        self.assertFalse(result['apps'][0]['groups'][0]['enable'])
        self.assertEqual(catalog[0]['advertisementGroupCount'], 0)
        self.assertTrue(result['xadskip']['groupMappings'][0]['reviewRequired'])

    def test_changing_behavior_does_not_change_persistent_group_identity(self):
        first = source('ganlinte', [group()])
        second = copy.deepcopy(first)
        second['data']['apps'][0]['groups'][0]['rules'][0]['matches'] = '[text="关闭"]'
        a, _, _ = build([first], 1)
        b, _, _ = build([second], 2)
        self.assertEqual(a['apps'][0]['groups'][0]['key'], b['apps'][0]['groups'][0]['key'])

    def test_retained_alias_keeps_its_identity_after_the_primary_source_disappears(self):
        first, _, _ = build([source('ganlinte', [group()]), source('aoguai', [group()])], 1)
        ledger = {identity_key(m['source'], m['appId'], m['sourceGroupKey']): m['unifiedGroupKey']
                  for m in first['xadskip']['groupMappings']}
        second, _, _ = build([source('aoguai', [group()])], 2, ledger)
        self.assertEqual(first['apps'][0]['groups'][0]['key'], second['apps'][0]['groups'][0]['key'])

    def test_splitting_persisted_aliases_requires_explicit_migration(self):
        first, _, _ = build([source('ganlinte', [group()]), source('aoguai', [group()])], 1)
        ledger = {identity_key(m['source'], m['appId'], m['sourceGroupKey']): m['unifiedGroupKey']
                  for m in first['xadskip']['groupMappings']}
        changed = group()
        changed['rules'][0]['matches'] = '[text="不同"]'
        with self.assertRaises(ValueError):
            build([source('ganlinte', [changed]), source('aoguai', [group()])], 2, ledger)

    def test_global_fallback_excludes_only_its_original_source_apps(self):
        primary = source('ganlinte', [group()])
        primary['data']['globalGroups'] = [group(9, disableIfAppGroupMatch='开屏广告')]
        other = source('aoguai', [group()])
        other['data']['apps'][0]['id'] = 'only.other'
        result, _, _ = build([primary, other], 1)
        fallback = result['globalGroups'][0]
        self.assertNotIn('disableIfAppGroupMatch', fallback)
        self.assertEqual(fallback['apps'], [{'id': 'example.app', 'enable': False}])


if __name__ == '__main__':
    unittest.main()
