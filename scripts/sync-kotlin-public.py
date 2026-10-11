#!/usr/bin/env python3
"""Export only tracked native-client source at HEAD, without private Git history or local files."""
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
target = Path(sys.argv[1]).resolve()
def git(*args, cwd=root): return subprocess.check_output(['git', *args], cwd=cwd)
remote = git('remote', 'get-url', 'origin', cwd=target).decode().strip()
if remote not in ('git@github.com:N3urda/hongguoTV-updates.git', 'https://github.com/N3urda/hongguoTV-updates.git'):
    raise SystemExit('Target must be the dedicated public update repository')
paths = git('ls-tree', '-r', '--name-only', '-z', 'HEAD', '--', 'kotlin-tv', 'LICENSE', 'scripts/build-kotlin.sh', 'server/vendor/hongguo.cjs', 'server/vendor/LICENSE.drpys').decode().split('\0')
paths = [p for p in paths if p]
for name in paths:
    if any(part in ('build', '.gradle', '.kotlin') for part in Path(name).parts) or name.endswith(('.jks', '.keystore', 'keystore.properties', 'local.properties')):
        raise SystemExit('Local-only material unexpectedly tracked; refusing export')
git('rm', '-r', '--ignore-unmatch', '--', 'kotlin-tv', 'scripts', 'server', 'README.md', 'LICENSE', '.gitignore', '.github/workflows', cwd=target)
for name in paths:
    destination = target / name; destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(git('show', 'HEAD:' + name))
    if name.endswith('/gradlew') or name == 'scripts/build-kotlin.sh': destination.chmod(0o755)
(target / '.gitignore').write_text('local.properties\nkeystore.properties\n*.jks\n*.keystore\n.gradle/\n.kotlin/\nbuild/\noutputs/\n')
(target / 'README.md').write_text('''# 红果 TV · 原生客户端源码

[下载最新版 APK](https://github.com/N3urda/hongguoTV-updates/releases/latest)

Android 8.0+，Kotlin / Media3，单 APK，无需部署服务。非官方、自用电视客户端。

客户端不含应用内更新：设置里的“版本信息”只读出本机版本号。新版 APK 从本仓库 Release 下载后手动覆盖安装，Android 8 由系统安装器确认，不是静默安装；覆盖安装保留本机收藏、进度与设置。

保留短剧/漫剧浏览、搜索、收藏、观看记录、续播、选集、全局清晰度与倍速。沿用原发布签名，覆盖安装保留本机数据。平台账号同步尚未接通。

本仓库只包含可发布的原生源码及构建文件，使用 GPL-3.0。正式 APK 在上游仓库本机构建并上传为本仓库的 Release 附件（APK、对应源码 ZIP、SHA256SUMS），不包含发布私钥。

构建开发 APK（JDK 17、Android SDK 36）：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
```

正式构建使用 `scripts/build-kotlin.sh`；签名配置见 [原生构建说明](kotlin-tv/README.md)。复现某个 Release 时，从附件名读出版本号，传入 `HONGGUOTV_VERSION_CODE` 与 `HONGGUOTV_VERSION_NAME`，并使用自己的签名配置。

Android 8 实体电视的解码、音频、遥控器和系统安装入口仍需实机确认。
''')
git('add', '--', 'kotlin-tv', 'scripts', 'server', 'README.md', 'LICENSE', '.gitignore', cwd=target)
print(f'Exported {len(paths)} tracked native source files; private history and untracked files excluded.')
