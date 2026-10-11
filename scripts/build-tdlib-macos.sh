#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script targets macOS. Follow the upstream TDLib Java build guide on other OSes." >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${TDLIB_SOURCE_DIR:-$ROOT/.tdlib-build/td}"
BUILD_JOBS="${TDLIB_BUILD_JOBS:-2}"
PIN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$ROOT/config/tdlib.json")"
[[ "${TDLIB_COMMIT:-$PIN}" = "$PIN" ]] || { echo "TDLIB_COMMIT conflicts with config/tdlib.json" >&2; exit 1; }
BUILD_SUFFIX=""
[[ "${TDLIB_PORTABLE:-false}" != true ]] || BUILD_SUFFIX="-portable"
INSTALL="$ROOT/.tdlib-build/artifacts-$PIN-$(uname -m)$BUILD_SUFFIX"
CMAKE_BUILD="$ROOT/.tdlib-build/native-build-$PIN-$(uname -m)$BUILD_SUFFIX"
EXAMPLE_BUILD="$ROOT/.tdlib-build/example-build-$PIN-$(uname -m)$BUILD_SUFFIX"
LOCAL_CMAKE="$ROOT/.tdlib-build/tools/cmake-3.31.8-macos-universal/CMake.app/Contents/bin"
if [[ -x "$LOCAL_CMAKE/cmake" ]]; then
  export PATH="$LOCAL_CMAKE:$PATH"
fi

command -v brew >/dev/null || { echo "Install Homebrew first."; exit 1; }
command -v git >/dev/null || { echo "git not found"; exit 1; }
command -v cmake >/dev/null || { echo "cmake not found; brew install cmake"; exit 1; }
command -v javac >/dev/null || { echo "javac not found; install JDK 21"; exit 1; }
command -v gperf >/dev/null || { echo "gperf not found; brew install gperf"; exit 1; }
xcode-select -p >/dev/null 2>&1 || {
  echo "Install Xcode Command Line Tools: xcode-select --install" >&2
  exit 1
}

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
OPENSSL_ROOT="${TDLIB_OPENSSL_ROOT:-$(brew --prefix openssl@3)}"
test -d "$OPENSSL_ROOT" || { echo "Install OpenSSL: brew install openssl@3" >&2; exit 1; }

# Older CLT installations can leave an incomplete libc++ include directory
# which takes precedence over the headers shipped in the current SDK.
CMAKE_CXX_OPTIONS=("-DCMAKE_CXX_FLAGS=${CXXFLAGS:-}")
if ! printf '#include <atomic>\n' | xcrun clang++ -x c++ -std=c++17 -fsyntax-only - >/dev/null 2>&1; then
  SDK_CXX_HEADERS="$(xcrun --show-sdk-path)/usr/include/c++/v1"
  test -f "$SDK_CXX_HEADERS/atomic" || { echo "C++ SDK headers are missing; repair Command Line Tools." >&2; exit 1; }
  CMAKE_CXX_OPTIONS=("-DCMAKE_CXX_FLAGS=${CXXFLAGS:-} -isystem $SDK_CXX_HEADERS")
fi

if [[ ! -f "$SRC/CMakeLists.txt" ]]; then
  mkdir -p "$(dirname "$SRC")"
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  if ! git -C "$SRC" remote get-url origin >/dev/null 2>&1; then
    git -C "$SRC" remote add origin https://github.com/tdlib/td.git
  fi
  [[ "$(git -C "$SRC" remote get-url origin)" = https://github.com/tdlib/td.git ]] || {
    echo "Unexpected TDLib source remote; refusing checkout" >&2; exit 1;
  }
  git -C "$SRC" fetch --depth 1 origin "$PIN"
  git -C "$SRC" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$SRC" rev-parse HEAD)" = "$PIN" ]] || {
  echo "TDLib checkout differs from pinned SHA. Preserve it and use TDLIB_SOURCE_DIR with a fresh path." >&2; exit 1;
}
# Only our exact idempotent CMake patch is allowed in tracked third-party sources.
[[ -z "$(git -C "$SRC" diff HEAD --name-only -- . ':!example/java/CMakeLists.txt')" ]] || {
  echo "Unexpected tracked TDLib source changes; refusing native build" >&2; exit 1;
}
PATCH_OPTIONS=()
[[ "${TDLIB_PORTABLE:-false}" != true ]] || PATCH_OPTIONS=(--portable)
python3 "$ROOT/scripts/prepare-tdlib-java.py" "$SRC" ${PATCH_OPTIONS[@]+"${PATCH_OPTIONS[@]}"}
PORTABLE_OPTIONS=()
[[ "${TDLIB_PORTABLE:-false}" != true ]] || PORTABLE_OPTIONS=(-DOPENSSL_USE_STATIC_LIBS=TRUE -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0)

echo "Building official TDLib $PIN with JNI for $(uname -m). This is the expensive one-time step."
cmake -S "$SRC" -B "$CMAKE_BUILD" \
  "${CMAKE_CXX_OPTIONS[@]}" ${PORTABLE_OPTIONS[@]+"${PORTABLE_OPTIONS[@]}"} \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES="$(uname -m)" \
  -DTD_ENABLE_JNI=ON \
  -DJAVA_HOME="$JAVA_HOME" \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT" \
  -DCMAKE_INSTALL_PREFIX="$SRC/example/java/td"
cmake --build "$CMAKE_BUILD" --target install --parallel "$BUILD_JOBS"

cmake -S "$SRC/example/java" -B "$EXAMPLE_BUILD" \
  "${CMAKE_CXX_OPTIONS[@]}" ${PORTABLE_OPTIONS[@]+"${PORTABLE_OPTIONS[@]}"} \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES="$(uname -m)" \
  -DJAVA_HOME="$JAVA_HOME" \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT" \
  -DTd_DIR="$SRC/example/java/td/lib/cmake/Td" \
  -DCMAKE_INSTALL_PREFIX="$INSTALL"
cmake --build "$EXAMPLE_BUILD" --target install --parallel "$BUILD_JOBS"

BIN="$INSTALL/bin"
test -f "$BIN/libtdjni.dylib" || { echo "Missing libtdjni.dylib in $BIN" >&2; exit 1; }
test -f "$BIN/org/drinkless/tdlib/TdApi.class" || { echo "Missing generated TdApi.class in $BIN" >&2; exit 1; }
mkdir -p "$ROOT/backend/libs" "$ROOT/backend/native"
MANIFEST="$(mktemp)"
trap 'rm -f "$MANIFEST"' EXIT
printf 'Manifest-Version: 1.0\nTDLib-Commit: %s\n\n' "$PIN" > "$MANIFEST"
jar --create --file "$ROOT/backend/libs/tdlib-java.jar" --manifest "$MANIFEST" -C "$BIN" org/drinkless/tdlib
python3 "$ROOT/scripts/check-tdlib-bindings.py" "$ROOT/backend/libs/tdlib-java.jar"
cp "$BIN/libtdjni.dylib" "$ROOT/backend/native/libtdjni.dylib"
bash "$ROOT/scripts/verify-tdlib-macos.sh" "$(uname -m)"
echo "Success: backend/libs/tdlib-java.jar and backend/native/libtdjni.dylib are ready."
