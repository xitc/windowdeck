#!/usr/bin/env python3
"""Mirror published WindowDeck APK releases without rebuilding or overwriting them."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

SOURCE = 'xitc/windowdeck'
TARGET = 'Xposed-Modules-Repo/io.github.xitc.windowdeck'
TAG = re.compile(r'([1-9][0-9]*)-([A-Za-z0-9][A-Za-z0-9.-]*)')


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def releases(repository):
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repository}/releases?per_page=100'))
    return [release for page in pages for release in page]


def release_identity(release):
    match = TAG.fullmatch(release['tag_name'])
    if release.get('draft') or not match:
        return None
    code, version = int(match[1]), match[2]
    required = {f'windowdeck-v{version}.apk', 'BUILD_INFO.json', 'SHA256SUMS'}
    assets = {asset['name']: asset for asset in release.get('assets', [])}
    if not required.issubset(assets):
        raise ValueError(f"{release['tag_name']}: missing required release assets")
    return code, version, required


def validate_download(directory, code, version, required):
    checksums = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([a-fA-F0-9]{64}) [ *]([^/\\]+)', line)
        if not match or match[2] not in required - {'SHA256SUMS'}:
            raise ValueError('Invalid checksum entry')
        if match[2] in checksums:
            raise ValueError('Duplicate checksum entry')
        checksums[match[2]] = match[1].lower()
    if set(checksums) != required - {'SHA256SUMS'}:
        raise ValueError('Incomplete checksums')
    for name, expected in checksums.items():
        actual = hashlib.sha256((directory / name).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f'Checksum mismatch: {name}')
    info = json.loads((directory / 'BUILD_INFO.json').read_text())
    if info['version_code'] != code or info['version_name'] != version:
        raise ValueError('Release tag does not match BUILD_INFO')


def sync(only_tag=None):
    source_releases = releases(SOURCE)
    target_releases = {r['tag_name']: r for r in releases(TARGET)}
    candidates = [r for r in source_releases if not r.get('draft') and TAG.fullmatch(r['tag_name'])]
    if only_tag:
        candidates = [r for r in candidates if r['tag_name'] == only_tag]
        if not candidates:
            raise ValueError('Requested published release was not found')
    candidates.sort(key=lambda r: int(TAG.fullmatch(r['tag_name'])[1]))
    branch = json.loads(gh('api', f'repos/{TARGET}'))['default_branch']
    messages = []
    for release in candidates:
        code, version, required = release_identity(release)
        tag = release['tag_name']
        existing = target_releases.get(tag)
        if existing and not existing.get('draft'):
            _, _, target_required = release_identity(existing)
            source_assets = {a['name']: a for a in release['assets']}
            target_assets = {a['name']: a for a in existing['assets']}
            for name in target_required:
                left, right = source_assets[name], target_assets[name]
                if left['size'] != right['size'] or (left.get('digest') and right.get('digest') and left['digest'] != right['digest']):
                    raise ValueError(f'{tag}: published target asset differs: {name}')
            messages.append(f'{tag}: already published; unchanged.')
            continue
        with tempfile.TemporaryDirectory(prefix='windowdeck-sync-') as temporary:
            directory = Path(temporary)
            download = ['release', 'download', tag, '--repo', SOURCE, '--dir', str(directory)]
            for name in sorted(required):
                download += ['--pattern', name]
            gh(*download)
            validate_download(directory, code, version, required)
            notes = directory / 'notes.md'
            notes.write_text(release.get('body') or f'WindowDeck {version}')
            common = ['--repo', TARGET, '--title', version, '--notes-file', str(notes)]
            common += ['--prerelease' if release.get('prerelease') else '--prerelease=false']
            files = [str(directory / name) for name in sorted(required)]
            if existing:
                gh('release', 'edit', tag, *common)
                gh('release', 'upload', tag, '--repo', TARGET, '--clobber', *files)
            else:
                gh('release', 'create', tag, '--target', branch, '--draft', *common, *files)
            # Verify the draft's uploaded bytes before making it public.
            uploaded = json.loads(gh('api', f'repos/{TARGET}/releases/tags/{tag}'))
            assets = {a['name']: a for a in uploaded['assets']}
            for name in required:
                asset = assets[name]
                path = directory / name
                digest = 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()
                if asset['size'] != path.stat().st_size or asset.get('digest') != digest:
                    raise ValueError(f'{tag}: uploaded asset verification failed: {name}')
            gh('release', 'edit', tag, '--repo', TARGET, '--draft=false',
               '--latest=false' if release.get('prerelease') else '--latest')
            messages.append(f'Published [{tag}](https://github.com/{TARGET}/releases/tag/{tag}).')
    return messages or ['No eligible releases to sync.']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', help='Retry one published source tag; default: all missing releases')
    args = parser.parse_args()
    if not os.environ.get('GH_TOKEN'):
        raise ValueError('GH_TOKEN is required; use the target repository GITHUB_TOKEN')
    messages = sync(args.tag)
    summary = '\n'.join(messages) + '\n'
    print(summary, end='')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as handle:
            handle.write(summary)


if __name__ == '__main__':
    main()
