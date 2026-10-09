#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script targets macOS. Follow the upstream TDLib Java build guide on other OSes." >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${TDLIB_SOURCE_DIR:-$ROOT/.tdlib-build/td}"
INSTALL="$ROOT/.tdlib-build/artifacts"
CMAKE_BUILD="$ROOT/.tdlib-build/native-build"
EXAMPLE_BUILD="$ROOT/.tdlib-build/example-build"
BUILD_JOBS="${TDLIB_BUILD_JOBS:-2}"
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

export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
OPENSSL_ROOT="$(brew --prefix openssl@3)"
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
  git clone --depth 1 https://github.com/tdlib/td.git "$SRC"
fi

echo "Building official TDLib with JNI for $(uname -m). This is the expensive one-time step."
cmake -S "$SRC" -B "$CMAKE_BUILD" \
  "${CMAKE_CXX_OPTIONS[@]}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DTD_ENABLE_JNI=ON \
  -DJAVA_HOME="$JAVA_HOME" \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT" \
  -DCMAKE_INSTALL_PREFIX="$SRC/example/java/td"
cmake --build "$CMAKE_BUILD" --target install --parallel "$BUILD_JOBS"

cmake -S "$SRC/example/java" -B "$EXAMPLE_BUILD" \
  "${CMAKE_CXX_OPTIONS[@]}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DJAVA_HOME="$JAVA_HOME" \
  -DTd_DIR="$SRC/example/java/td/lib/cmake/Td" \
  -DCMAKE_INSTALL_PREFIX="$INSTALL"
cmake --build "$EXAMPLE_BUILD" --target install --parallel "$BUILD_JOBS"

BIN="$INSTALL/bin"
test -f "$BIN/libtdjni.dylib" || { echo "Missing libtdjni.dylib in $BIN" >&2; exit 1; }
test -f "$BIN/org/drinkless/tdlib/TdApi.class" || { echo "Missing generated TdApi.class in $BIN" >&2; exit 1; }
mkdir -p "$ROOT/backend/libs" "$ROOT/backend/native"
jar --create --file "$ROOT/backend/libs/tdlib-java.jar" -C "$BIN" org/drinkless/tdlib
cp "$BIN/libtdjni.dylib" "$ROOT/backend/native/libtdjni.dylib"
echo "Success: backend/libs/tdlib-java.jar and backend/native/libtdjni.dylib are ready."
