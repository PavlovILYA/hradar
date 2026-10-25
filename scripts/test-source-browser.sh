#!/usr/bin/env bash
# Account-free real REST/services/temporary SQLite and fake TDLib browser acceptance.
set -euo pipefail
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:18082 -sTCP:LISTEN >/dev/null 2>&1; then
  echo 'Port 18082 is occupied; refusing to start a competing fixture.' >&2
  exit 1
fi
mkdir -p .tdlib-build/source-browser
source_init="$PWD/.tdlib-build/source-browser/fixture.gradle"
source_log="$PWD/.tdlib-build/source-browser/fixture.log"
cat > "$source_init" <<'GRADLE'
allprojects {
    if (name == 'backend') {
        afterEvaluate {
            tasks.register('sourceBrowserFixture', JavaExec) {
                dependsOn tasks.named('testClasses')
                classpath = sourceSets.test.runtimeClasspath
                mainClass = 'dev.radar.sources.SourceBrowserFixture'
            }
        }
    }
}
GRADLE
./gradlew -I "$source_init" :backend:sourceBrowserFixture --console=plain > "$source_log" 2>&1 &
source_gradle_pid=$!
source_fixture_pid=''
cleanup() {
  if [[ -n "$source_fixture_pid" ]]; then kill -TERM "$source_fixture_pid" 2>/dev/null || true; fi
  kill -TERM "$source_gradle_pid" 2>/dev/null || true
  wait "$source_gradle_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
for ((source_attempt=0; source_attempt<180; source_attempt++)); do
  if curl --silent --fail --header 'Host: 127.0.0.1:8080' http://127.0.0.1:18082/api/sources >/dev/null; then
    source_fixture_pid=$(lsof -t -nP -iTCP:18082 -sTCP:LISTEN)
    break
  fi
  if ! kill -0 "$source_gradle_pid" 2>/dev/null; then cat "$source_log"; exit 1; fi
  sleep 1
done
if [[ -z "$source_fixture_pid" ]]; then echo "Fixture startup timed out; see $source_log" >&2; exit 1; fi
SOURCE_FIXTURE_URL=http://127.0.0.1:18082 npm run test:ci --prefix frontend -- --config playwright.projects.config.ts tests/source-reading.spec.ts --grep 'selected topic reads'
