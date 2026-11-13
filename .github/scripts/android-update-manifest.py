import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


def main():
    apk_arg, aapt, release_base, tag = sys.argv[1:]
    apk = Path(apk_arg)
    badging = subprocess.check_output([aapt, "dump", "badging", str(apk)], encoding="utf-8")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if not package or not sdk:
        raise SystemExit("Unable to read package, version and minimum SDK from the APK")
    name, version_code, version_name = package.groups()
    if name != "com.forest.android.assistant" or tag != f"v{version_name}" or int(version_code) <= 0:
        raise SystemExit("APK package or version does not match the release tag")
    if apk.name != f"assistant-{tag}-release.apk":
        raise SystemExit("Unexpected APK filename")

    with apk.open("rb") as stream:
        sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = {
        "versionCode": int(version_code),
        "versionName": version_name,
        "minSdk": int(sdk.group(1)),
        "sizeBytes": apk.stat().st_size,
        "sha256": sha256,
        "apkUrl": f"{release_base}/download/{tag}/{apk.name}",
        # Older app versions require a string in this field.
        "notes": "",
    }
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
