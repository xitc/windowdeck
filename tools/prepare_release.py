#!/usr/bin/env python3
"""Plan a release before installing the SDK; only --apply changes the manifest."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime
from pathlib import Path
from urllib.parse import quote
from zoneinfo import ZoneInfo

ANDROID = "{http://schemas.android.com/apk/res/android}"
SOURCE_PATHS = ["module", "tools", ".github", "CHANGELOG.md"]
MARKER = re.compile(r"<!-- windowdeck-build: (\{.*?\}) -->")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def manifest_version(text):
    import xml.etree.ElementTree as ET
    root = ET.fromstring(text)
    version = root.attrib[ANDROID + "versionName"]
    if not re.fullmatch(r"[A-Za-z0-9.-]+", version):
        raise ValueError("Invalid APK versionName")
    return version, int(root.attrib[ANDROID + "versionCode"])


def metadata(release):
    match = MARKER.search(release.get("body") or "")
    return json.loads(match[1]) if match else {}


def fingerprint(ref):
    # Tree entries include paths, modes and blob IDs, but ignore commit messages
    # and README-only edits. The same source after a revert is the same build.
    tree = git("ls-tree", "-r", ref, "--", *SOURCE_PATHS)
    return hashlib.sha256(tree.encode()).hexdigest()


def release_source(release):
    # Published tags survive authorized history cleanup; old asset metadata
    # can still contain the pre-cleanup source SHA. Prefer the current tag.
    tag = "refs/tags/" + release["tag_name"]
    resolved = subprocess.run(["git", "rev-parse", "--verify", f"{tag}^{{commit}}"],
                              text=True, capture_output=True)
    if resolved.returncode == 0:
        return resolved.stdout.strip()
    source = metadata(release).get("source_sha")
    return git("rev-parse", "--verify", f"{source or tag}^{{commit}}")


def release_code(release):
    info = metadata(release)
    if "version_code" in info:
        return int(info["version_code"])
    match = re.search(r"versionCode\s+`?(\d+)", release.get("body") or "")
    if match:
        return int(match[1])
    # Older releases did not put versionCode in their descriptions.
    return manifest_version(git("show", f"{release_source(release)}:module/AndroidManifest.xml"))[1]


def changelog_section(text, tag):
    match = re.search(r"^## " + re.escape(tag) + r"\s*$\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError(f"CHANGELOG.md has no notes under ## {tag}")
    return match[1].strip()


def commit_notes(source, previous, repository):
    revision = f"{previous}..{source}" if previous else source
    log = git("log", "--reverse", "--format=%H%x09%s", revision, "--", *SOURCE_PATHS)
    groups = {name: [] for name in ["新增功能", "问题修复", "性能与实现调整", "构建与测试", "其他变更"]}
    categories = {"feat": "新增功能", "fix": "问题修复", "perf": "性能与实现调整",
                  "refactor": "性能与实现调整", "ci": "构建与测试", "build": "构建与测试",
                  "test": "构建与测试"}
    for line in log.splitlines():
        sha, subject = line.split("\t", 1)
        match = re.match(r"(\w+)(?:\([^)]*\))?!?:\s*(.*)", subject)
        category = categories.get(match[1], "其他变更") if match else "其他变更"
        title = match[2] if match else subject
        # Commit subjects are text, not trusted Markdown/HTML.
        title = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        title = re.sub(r"([\\`*\[\]_])", r"\\\1", title)
        groups[category].append(f"- {title} ([{sha[:7]}](https://github.com/{repository}/commit/{sha}))")
    sections = []
    for category, changes in groups.items():
        if changes:
            sections.append(f"#### {category}\n\n" + "\n".join(changes[:50]))
            if len(changes) > 50:
                sections[-1] += "\n- 更多提交见完整变更链接。"
    return "\n\n".join(sections) or "源码与上次发布不同；提交范围无法列出，请查看完整变更。"


def plan(releases, mode, day, repository, run_url):
    source = git("rev-parse", "HEAD")
    if os.environ.get("GITHUB_SHA", source) != source:
        raise ValueError("Checkout does not match GITHUB_SHA")
    original, original_code = manifest_version(Path("module/AndroidManifest.xml").read_text())
    published = sorted((r for r in releases if not r.get("draft")),
                       key=lambda r: r.get("published_at") or "", reverse=True)
    current_fingerprint = fingerprint(source)
    previous_release = published[0] if published else None
    previous = release_source(previous_release) if previous_release else None
    if mode == "nightly" and previous_release:
        old_fingerprint = metadata(previous_release).get("source_fingerprint") or fingerprint(previous)
        if current_fingerprint == old_fingerprint:
            return {"publish": False, "reason": "与上次发布相比，源码、构建配置及更新记录没有变化。"}
    base = original.split("-", 1)[0]
    version = f"{base}-nightly.{day}.{source[:12]}" if mode == "nightly" else original
    legacy_tag = f"v{version}"
    def same_version(r):
        return (metadata(r).get("version_name") == version or
                r["tag_name"] == legacy_tag or
                re.fullmatch(r"\d+-" + re.escape(version), r["tag_name"]) is not None)
    if any(same_version(r) for r in published):
        return {"publish": False, "reason": f"{version} 已发布；不会覆盖已有发布。"}
    # Both nightly and manually named releases share the same monotonic counter.
    # Include drafts, so a failed publication cannot reuse a reserved code.
    code = max(original_code, max((release_code(r) for r in releases), default=0) + 1)
    # Retry an existing draft with the same code and tag. Published releases
    # remain immutable, and the normal counter includes all reserved drafts.
    retry = next((r for r in releases if r.get("draft") and same_version(r)), None)
    if retry:
        if release_source(retry) != source:
            raise ValueError("Draft version belongs to another source commit")
        code = release_code(retry)
    tag = retry["tag_name"] if retry else f"{code}-{version}"
    if not 0 < code <= 2100000000:
        raise ValueError("versionCode is outside Android's supported range")
    info = {"source_sha": source, "source_fingerprint": current_fingerprint,
            "version_name": version, "version_code": code, "channel": mode,
            "build_date": day, "run_url": run_url}
    prerelease = mode == "nightly" or bool(re.search(r"(?:alpha|beta|rc|dev|nightly)", version))
    title = f"WindowDeck {day[:4]}-{day[4:6]}-{day[6:]} · 每日测试版" if mode == "nightly" else f"WindowDeck {tag}"
    if mode == "version" and prerelease:
        title += " · 测试版"
    changes = (commit_notes(source, previous, repository) if mode == "nightly" else
               changelog_section(Path("CHANGELOG.md").read_text(), legacy_tag))
    compare = (f"https://github.com/{repository}/compare/{quote(previous_release['tag_name'], safe='')}...{source}"
               if previous_release else f"https://github.com/{repository}/commits/{source}")
    notes = f"""## 多窗工作台 · WindowDeck

**{'每日测试版：自动构建，不代表完成真机验收。' if mode == 'nightly' else '测试版本，尚非稳定版。' if prerelease else '版本发布。'}**

- APK 版本：`{version}` / versionCode {code}
- 包名：`io.github.xitc.windowdeck`
- 下载附件中的 APK；`SHA256SUMS` 用于校验文件完整性。
- 沿用 GitHub 发布签名，可覆盖安装相同新包名的版本；不能覆盖旧包名 `dev.windowdeck.app`。

### 本次变化

{'以下按上次发布以来的提交说明自动归类。' if mode == 'nightly' else ''}

{changes}

{Path('.github/RELEASE_CONTEXT.md').read_text().strip()}

### 自动验证

- 自动发布不运行本地回归测试；回归测试与辅助脚本仅在维护者本地保留。
- APK 编译、签名验证与发布证书校验通过。
- 本次自动构建未执行手机安装、动画、触控或 ROM 兼容性验收。

### 构建来源

- 源码提交：[{source[:12]}](https://github.com/{repository}/commit/{source})
- [完整变更]({compare}) · [构建记录]({run_url})
- `BUILD_INFO.json` 记录本 APK 的版本、源码与构建信息。

<!-- windowdeck-build: {json.dumps(info, ensure_ascii=False, sort_keys=True)} -->
"""
    return {"publish": True, "tag": tag, "version": version, "code": code,
            "title": title, "prerelease": prerelease, "notes": notes, "info": info}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["nightly", "version"], default="nightly")
    parser.add_argument("--releases", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--date", default=datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    datetime.strptime(args.date, "%Y%m%d")
    releases = json.loads(args.releases.read_text())
    if releases and isinstance(releases[0], list):
        releases = [release for page in releases for release in page]
    repository = os.environ.get("GITHUB_REPOSITORY", "xitc/windowdeck")
    run_url = f"https://github.com/{repository}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"
    result = plan(releases, args.mode, args.date, repository, run_url)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if result["publish"]:
        (args.output_dir / "notes.md").write_text(result["notes"])
        (args.output_dir / "BUILD_INFO.json").write_text(json.dumps(result["info"], ensure_ascii=False, indent=2) + "\n")
        if args.apply:
            manifest = Path("module/AndroidManifest.xml")
            text = re.sub(r'android:versionName="[^"]*"', f'android:versionName="{result["version"]}"', manifest.read_text(), count=1)
            text = re.sub(r'android:versionCode="[^"]*"', f'android:versionCode="{result["code"]}"', text, count=1)
            manifest.write_text(text)
        summary = f"待发布 {result['tag']}，versionCode {result['code']}。\n"
    else:
        summary = result["reason"] + "\n"
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as handle:
            handle.write(summary)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as handle:
            for key in ["publish", "tag", "version", "code", "title", "prerelease"]:
                if key in result:
                    value = str(result[key]).lower() if isinstance(result[key], bool) else str(result[key])
                    handle.write(f"{key}={value}\n")
    print(summary, end="")


if __name__ == "__main__":
    main()
