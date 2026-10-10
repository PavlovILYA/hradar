#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_ARCH="${1:-$(uname -m)}"
JAVA_ROOT="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
JAR="$ROOT/backend/libs/tdlib-java.jar"
LIB="$ROOT/backend/native/libtdjni.dylib"
test "$(uname -s)" = Darwin
[[ "$EXPECTED_ARCH" = arm64 || "$EXPECTED_ARCH" = x86_64 ]]
test -s "$JAR" && test -s "$LIB"
file "$LIB"
lipo "$LIB" -archs
lipo "$LIB" -verify_arch "$EXPECTED_ARCH"
otool -L "$LIB"
# dyld + synchronous TDLib execution verifies actual dependencies/JNI without an account.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/JniSmoke.java" <<'JAVA'
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
class JniSmoke {
    public static void main(String[] args) throws Exception {
        // Verify the pinned schema's two-argument API; never send this keyboard.
        TdApi.ReplyMarkupInlineKeyboard keyboard = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[0][], false);
        if (keyboard.forceReply) throw new IllegalStateException("Unexpected generated schema");
        System.loadLibrary("tdjni");
        TdApi.TextEntities result = Client.execute(new TdApi.GetTextEntities("hradar JNI smoke"));
        if (result == null) throw new IllegalStateException("Missing JNI result");
        System.out.println("Native load and synchronous TDLib call passed; no client/session created");
    }
}
JAVA
"$JAVA_ROOT/bin/javac" -cp "$JAR" -d "$WORK" "$WORK/JniSmoke.java"
"$JAVA_ROOT/bin/java" -Djava.library.path="$ROOT/backend/native" -cp "$JAR:$WORK" JniSmoke
