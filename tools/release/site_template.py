"""Turn the checked-in, disabled website preview into a verified release site."""
import re
from pathlib import Path


def render_site(template: Path, version: str, apk_name: str, apk_size: int, sha256: str):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Invalid release version')
    if apk_name != f'XAdSkip-{version}.apk' or apk_size <= 0 or not re.fullmatch(r'[0-9a-f]{64}', sha256):
        raise ValueError('Invalid verified APK metadata')
    page = (template / 'index.html').read_text()
    if 'data-release-ready="false"' not in page or '{{APK_SHA256}}' not in page:
        raise ValueError('Expected the disabled source template, not an already published page')
    old = re.search(r'<dt>版本</dt><dd>([^<]+)</dd>', page).group(1)
    script_name, style_name, icon_name = f'app-{version}.js', f'style-{version}.css', f'icon-{version}.svg'
    page = page.replace(f'XAdSkip-{old}', f'XAdSkip-{version}')
    page = page.replace(f'<dt>版本</dt><dd>{old}</dd>', f'<dt>版本</dt><dd>{version}</dd>')
    page = page.replace(f'正在准备 {old} 正式版', f'当前提供 {version} 正式版')
    page, count = re.subn(r'<p id="release-preview-notice"[^>]*>.*?</p>', '', page, flags=re.S)
    if count != 1:
        raise ValueError('Expected exactly one release preview notice')
    page = page.replace('data-release-ready="false"', 'data-release-ready="true"')
    page = page.replace('{{APK_SIZE}}', f'{apk_size/1_000_000:.1f} MB').replace('{{APK_SHA256}}', sha256)
    page = page.replace('src="app.js"', f'src="{script_name}"')
    page = page.replace('href="style.css"', f'href="{style_name}"')
    page = page.replace('href="icon.svg"', f'href="{icon_name}"').replace('src="icon.svg"', f'src="{icon_name}"')
    if '{{' in page:
        raise ValueError('Unresolved website template variable')
    script = (template / 'app.js').read_text().replace(f'XAdSkip-{old}', f'XAdSkip-{version}')
    return {
        'index.html': page.encode(), script_name: script.encode(),
        style_name: (template / 'style.css').read_bytes(), icon_name: (template / 'icon.svg').read_bytes(),
    }
