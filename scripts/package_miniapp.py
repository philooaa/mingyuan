#!/usr/bin/env python3
"""Package production miniapp source; retain local debugging only in the development checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def package(output):
    output = Path(output).resolve()
    if output == ROOT or ROOT in output.parents:
        raise ValueError('Choose an output directory outside the repository')
    if output.exists():
        raise FileExistsError(output)
    shutil.copytree(ROOT / 'WeChatMiniApp', output,
                    ignore=shutil.ignore_patterns('tests', 'project.private.config.json', '.DS_Store'))
    app = output / 'app.js'
    text = app.read_text()
    start = text.index('    let allowOverride')
    end = text.index('    const previousBackend', start)
    app.write_text(text[:start] + '    // 发布包连接服务器。\n' + text[end:])
    page = output / 'pages/register/register.wxml'
    text = page.read_text().replace(' bindlongpress="openBackendSetting"', '')
    text = text.replace('wx:if="{{!localPreviewEnabled && privacyAgreed}}"', 'wx:if="{{privacyAgreed}}"')
    text = text.replace('wx:elif="{{!localPreviewEnabled}}"', 'wx:else').replace(' wx:if="{{!localPreviewEnabled}}"', '')
    text = '\n'.join(line for line in text.split('\n')
                     if 'bindtap="handleLocalPreview"' not in line and 'class="dev-backend"' not in line)
    page.write_text(text)
    config = output / 'project.config.json'
    settings = json.loads(config.read_text())
    settings['setting']['minified'] = True
    settings['packOptions']['ignore'] = [{'type': 'folder', 'value': 'tests'}]
    config.write_text(json.dumps(settings, ensure_ascii=False, indent=2))
    assert '127.0.0.1:8080' not in app.read_text() and 'backendOverride' not in app.read_text()
    assert '本地预览' not in text and '当前后端' not in text
    assert 'wx:if="{{privacyAgreed}}" class="wechat-btn" open-type="getPhoneNumber"' in text
    assert json.loads((output / 'app.json').read_text())['pages'][0] == 'pages/browse/browse'
    return {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(output.rglob('*')) if p.is_file()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, help='New miniapp directory outside the repository')
    args = parser.parse_args()
    print(json.dumps({'files': len(package(args.output)), 'output': str(Path(args.output).resolve())}))
