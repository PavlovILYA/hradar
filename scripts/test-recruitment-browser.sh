#!/usr/bin/env bash
# Real recruitment REST + temporary SQLite and fake Telegram, with other APIs mocked.
set -euo pipefail
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:18081 -sTCP:LISTEN >/dev/null 2>&1; then
  echo 'Port 18081 is occupied; stop the existing test fixture before running this script.' >&2
  exit 1
fi
mkdir -p .tdlib-build/crm-browser
crm_log="$PWD/.tdlib-build/crm-browser/fixture.log"
crm_init="$PWD/.tdlib-build/crm-browser/fixture.gradle"
# Keep compiled classes away from any running production backend in this checkout.
crm_build="$PWD/.tdlib-build/crm-browser/source"
mkdir -p "$crm_build"
cp build.gradle.kts settings.gradle.kts gradle.properties "$crm_build/"
rsync -a --exclude=build --exclude=native backend/ "$crm_build/backend/"
cat > "$crm_init" <<'GRADLE'
allprojects {
    if (name == 'backend') {
        afterEvaluate {
            tasks.register('crmBrowserFixture', JavaExec) {
                dependsOn tasks.named('testClasses')
                classpath = sourceSets.test.runtimeClasspath
                mainClass = 'dev.radar.research.RecruitmentBrowserFixture'
            }
        }
    }
}
GRADLE
./gradlew -p "$crm_build" -I "$crm_init" :backend:crmBrowserFixture --console=plain > "$crm_log" 2>&1 &
crm_gradle_pid=$!
crm_fixture_pid=''
cleanup() {
  if [[ -n "$crm_fixture_pid" ]]; then kill -TERM "$crm_fixture_pid" 2>/dev/null || true; fi
  kill -TERM "$crm_gradle_pid" 2>/dev/null || true
  wait "$crm_gradle_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
for ((crm_attempt=0; crm_attempt<180; crm_attempt++)); do
  if curl --silent --fail --header 'Host: 127.0.0.1:8080' http://127.0.0.1:18081/api/projects >/dev/null; then
    crm_fixture_pid=$(lsof -t -nP -iTCP:18081 -sTCP:LISTEN)
    break
  fi
  if ! kill -0 "$crm_gradle_pid" 2>/dev/null; then cat "$crm_log"; exit 1; fi
  sleep 1
done
if [[ -z "$crm_fixture_pid" ]]; then echo "Fixture startup timed out; see $crm_log" >&2; exit 1; fi
CRM_FIXTURE_URL=http://127.0.0.1:18081 npm run test:ci --prefix frontend -- --config playwright.projects.config.ts tests/projects.spec.ts
