#!/usr/bin/env bash
# bandcore 纯协议层离线自测（Linux / macOS / Git Bash）
#
# 只用 JDK 21 的 javac/java：不联网、不跑 Gradle、不需要 Android SDK、不需要手环。
# 做的事：把 bandcore 里不依赖 Android 设备/API 的协议源码编到 build/selftest/，
# 再编 bandcore/src/test/java 下的自测类，最后跑 tools/selftest/SelfTestRunner.java。
#
# 覆盖什么、测不到什么：docs/TESTING.md
# 退出码 0 = 全过；非 0 = 有失败或编译失败。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Work relative to the repo root: keeps paths identical on Linux CI and in Git Bash
# on Windows (Windows javac cannot resolve MSYS-style /c/... paths from @argfiles).
cd "$root"

if [[ -n "${JAVA_HOME:-}" ]]; then
  javac="$JAVA_HOME/bin/javac"
  java="$JAVA_HOME/bin/java"
else
  javac="$(command -v javac || true)"
  java="$(command -v java || true)"
fi

if [[ -z "$javac" || -z "$java" || ! -x "$javac" || ! -x "$java" ]]; then
  echo "找不到 javac/java：请设置 JAVA_HOME（需要 JDK 17+，项目用 JDK 21）或把 JDK 加进 PATH" >&2
  exit 2
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) cpsep=';' ;;
  *) cpsep=':' ;;
esac

out="build/selftest"
stub_out="$out/stubs"
main_out="$out/main"
test_out="$out/test"

rm -rf "$out"
mkdir -p "$stub_out" "$main_out" "$test_out"

echo "== [1/4] compile stubs (android.* / org.json 的最小替身)"
find tools/selftest/stubs -name '*.java' | sort > "$out/stubs.list"
"$javac" -encoding UTF-8 --release 17 -d "$stub_out" "@$out/stubs.list"

echo "== [2/4] compile pure bandcore sources"
# 只编「不依赖 Android 设备/Context/Bluetooth」的部分：
#   proto/** 整个包（TLV / 帧 / CRC / 时间换算 / 序列解析 / 各报文类）
#   Probe（诊断日志，只用 android.util.Log）
#   transport/PacketAssembler（攒包分发；HuaweiLink 需要真蓝牙，不编）
# bandcore 里其余类（BandSession / HuaweiLink / AuthFlow / data/**）需要 Context、
# BluetoothGatt 或 SharedPreferences，属于「必须上设备才能测」的那一半。
find bandcore/src/main/java/io/github/bandbridge/core/proto -name '*.java' | sort > "$out/main.list"
echo 'bandcore/src/main/java/io/github/bandbridge/core/Probe.java' >> "$out/main.list"
echo 'bandcore/src/main/java/io/github/bandbridge/core/transport/PacketAssembler.java' >> "$out/main.list"
"$javac" -encoding UTF-8 --release 17 -cp "$stub_out" -d "$main_out" "@$out/main.list"

echo "== [3/4] compile self-tests"
find bandcore/src/test/java -name '*.java' | sort > "$out/test.list"
echo 'tools/selftest/SelfTestRunner.java' >> "$out/test.list"
"$javac" -encoding UTF-8 --release 17 -cp "$main_out$cpsep$stub_out" -d "$test_out" "@$out/test.list"

echo "== [4/4] run self-tests"
"$java" -cp "$test_out$cpsep$main_out$cpsep$stub_out" SelfTestRunner
