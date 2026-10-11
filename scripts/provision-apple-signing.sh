#!/bin/bash
# Only runs in the separately authorized signing job. Never enable shell tracing.
set -euo pipefail
: "${APPLE_CERTIFICATE_BASE64:?Missing Developer ID certificate}"
: "${APPLE_CERTIFICATE_PASSWORD:?Missing certificate password}"
: "${APPLE_SIGNING_IDENTITY:?Missing signing identity}"
: "${APPLE_NOTARY_KEY_BASE64:?Missing App Store Connect API key}"
: "${APPLE_NOTARY_KEY_ID:?Missing API key ID}"
: "${APPLE_NOTARY_ISSUER_ID:?Missing API issuer}"
: "${RUNNER_TEMP:?Requires an ephemeral CI runner}"
KEYCHAIN="$RUNNER_TEMP/hradar-signing.keychain-db"
KEYCHAIN_PASSWORD="$(/usr/bin/openssl rand -hex 32)"
echo "::add-mask::$KEYCHAIN_PASSWORD"
export HRADAR_CERTIFICATE_PATH="$RUNNER_TEMP/hradar-certificate.p12"
export HRADAR_NOTARY_KEY_PATH="$RUNNER_TEMP/hradar-notary.p8"
python3 - <<'PY'
import os,base64
for field,path in [('APPLE_CERTIFICATE_BASE64','HRADAR_CERTIFICATE_PATH'),('APPLE_NOTARY_KEY_BASE64','HRADAR_NOTARY_KEY_PATH')]:
    fd=os.open(os.environ[path],os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as f: f.write(base64.b64decode(os.environ[field],validate=True))
PY
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security import "$HRADAR_CERTIFICATE_PATH" -P "$APPLE_CERTIFICATE_PASSWORD" -A -t cert -f pkcs12 -k "$KEYCHAIN"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null
security list-keychains -d user -s "$KEYCHAIN"
xcrun notarytool store-credentials hradar-ci --keychain "$KEYCHAIN" --key "$HRADAR_NOTARY_KEY_PATH" --key-id "$APPLE_NOTARY_KEY_ID" --issuer "$APPLE_NOTARY_ISSUER_ID"
rm -f "$HRADAR_CERTIFICATE_PATH" "$HRADAR_NOTARY_KEY_PATH"
echo "APPLE_NOTARY_PROFILE=hradar-ci" >> "$GITHUB_ENV"
