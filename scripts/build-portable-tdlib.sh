#!/bin/bash
# Separate build/cache namespace; never alters the published ARM64 assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export MACOSX_DEPLOYMENT_TARGET=15.0
OPENSSL_VERSION=3.5.9
OPENSSL_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
OPENSSL_WORK="$ROOT/.tdlib-build/openssl-$OPENSSL_VERSION-$(uname -m)"
export TDLIB_OPENSSL_ROOT="$OPENSSL_WORK/install"
if [[ ! -f "$TDLIB_OPENSSL_ROOT/lib/libcrypto.a" ]]; then
  mkdir -p "$OPENSSL_WORK"
  curl --fail --location --retry 3 "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" -o "$OPENSSL_WORK/source.tar.gz"
  echo "$OPENSSL_SHA  $OPENSSL_WORK/source.tar.gz" | shasum -a 256 -c -
  tar -xzf "$OPENSSL_WORK/source.tar.gz" -C "$OPENSSL_WORK"
  (
    cd "$OPENSSL_WORK/openssl-$OPENSSL_VERSION"
    case "$(uname -m)" in
      arm64) target=darwin64-arm64-cc ;;
      x86_64) target=darwin64-x86_64-cc ;;
      *) exit 1 ;;
    esac
    ./Configure "$target" no-shared no-tests no-module --prefix="$TDLIB_OPENSSL_ROOT" --libdir=lib -mmacosx-version-min=15.0
    make -j "${TDLIB_BUILD_JOBS:-3}"
    make install_sw
  )
fi
export TDLIB_PORTABLE=true
export TDLIB_SOURCE_DIR="$ROOT/.tdlib-build/td-portable"
bash scripts/build-tdlib-macos.sh
python3 -B scripts/package-tdlib-artifact.py
cp "$TDLIB_SOURCE_DIR/LICENSE_1_0.txt" .tdlib-build/release/TDLib-LICENSE.txt
cp "$OPENSSL_WORK/openssl-$OPENSSL_VERSION/LICENSE.txt" .tdlib-build/release/OpenSSL-LICENSE.txt
