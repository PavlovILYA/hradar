#!/usr/bin/env python3
"""Isolated packaged lifecycle smoke. No Telegram client, credentials or user directories."""
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.request, urllib.error, sqlite3, socket
app = pathlib.Path(sys.argv[1]).resolve()
launcher = app / 'Contents/MacOS/hradar'
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
root = pathlib.Path(__file__).resolve().parent.parent

def http(path, body=None, headers=None, expected=200):
    req = urllib.request.Request(f'http://127.0.0.1:{port}'+path, data=None if body is None else json.dumps(body).encode(), headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            data = response.read()
            assert response.status == expected
            return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        assert e.code == expected, (path, e.code, expected)

def wait_ready(process, marker):
    for _ in range(100):
        if process.poll() is not None: raise RuntimeError('Packaged process exited early')
        if marker.exists():
            http('/health'); return json.loads(marker.read_text())['token']
        time.sleep(.1)
    raise RuntimeError('Packaged server never became ready')

with tempfile.TemporaryDirectory(prefix='hradar-smoke-') as temporary:
    home = pathlib.Path(temporary)
    # Sentinel emulates an existing legacy TDLib directory; never open a real session.
    session = home / '.recruitment-radar/tdlib'
    session.mkdir(parents=True)
    (session/'preserved-sentinel').write_text('preserved')
    marker = home / '.hradar/desktop-instance.json'
    env = dict(os.environ, PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME='', NODE_PATH='', JAVA_TOOL_OPTIONS=f'-Duser.home={home} -Dhradar.smoke=true -Dhradar.smokePort={port}')
    for name in ['TG_API_ID','TG_API_HASH','TG_BOT_TOKEN','TG_BOT_ALLOWED_USER_IDS']:
        env.pop(name, None)
    project_id = None
    crm_snapshot = None
    with (home/'runtime.log').open('w') as log:
        for attempt in range(2):
            process = subprocess.Popen([str(launcher)], env=env, stdout=log, stderr=log)
            try:
                token = wait_ready(process, marker)
                cookie = 'hradar-desktop='+token
                headers = {'Cookie':cookie, 'Origin':f'http://127.0.0.1:{port}', 'Content-Type':'application/json'}
                http('/api/onboarding', expected=403)
                http('/api/onboarding', headers={'Cookie':cookie,'Origin':'https://evil.example'}, expected=403)
                state = http('/api/onboarding', headers=headers)
                assert state['state'] == 'Credentials'
                http('/api/onboarding/credentials', {'apiId':1,'apiHash':'0'*32}, headers, expected=403)
                http('/api/projects', expected=403)
                http('/api/projects', headers={'Cookie':cookie,'Origin':'https://evil.example'}, expected=403)
                database = home/'.hradar/data/research.sqlite'
                if attempt == 0:
                    with sqlite3.connect(database) as db:
                        assert db.execute('PRAGMA user_version').fetchone() == (10,)
                        source = {'id':-1001,'username':'synthetic_jobs','title':'Synthetic source'}
                        db.execute('INSERT INTO monitored_channels VALUES (?,?,?)', (-1001,'synthetic_jobs',json.dumps(source)))
                        db.execute('INSERT INTO imported_messages VALUES (?,?,?,?,?)', (-1001,1,1700000000,'Synthetic Java publication','synthetic java publication'))
                    project = http('/api/projects', {'name':'Installer CRM sentinel','required':['Java'],'sources':[-1001]}, headers, expected=201)
                    project_id = project['id']
                    results = http(f'/api/projects/{project_id}/search', {'mode':'archive','query':'java'}, headers)
                    assert len(results['messages']) == 1
                    card = http(f'/api/projects/{project_id}/cards', {'channelId':-1001,'messageId':1}, headers, expected=201)
                    req = urllib.request.Request(f'http://127.0.0.1:{port}/api/projects/{project_id}/cards/{card["id"]}', data=json.dumps({'stage':'CONTACTED','notes':'Synthetic preserved note','interest':5}).encode(), headers=headers, method='PUT')
                    with urllib.request.urlopen(req, timeout=3) as response: assert response.status == 200
                else:
                    assert http(f'/api/projects/{project_id}', headers=headers)['criteria']['name'] == 'Installer CRM sentinel'
                    results = http(f'/api/projects/{project_id}/search', {'mode':'archive','query':'java'}, headers)
                    assert len(results['messages']) == 1
                    card = http(f'/api/projects/{project_id}/cards', headers=headers)['items'][0]
                    assert (card['stage'],card['notes'],card['interest']) == ('CONTACTED','Synthetic preserved note',5)
                    with sqlite3.connect(database) as db:
                        assert db.execute('PRAGMA user_version').fetchone() == (10,)
                        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                        assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                        assert crm_snapshot == {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in crm_snapshot}
                # Browser executes actual production JS/CSS and the launch handshake.
                if attempt == 0:
                    browser_env = dict(os.environ, HRADAR_SMOKE_TOKEN=token, HRADAR_SMOKE_PORT=str(port))
                    subprocess.run(['node',str(root/'frontend/scripts/smoke-packaged.mjs')],env=browser_env,check=True,timeout=60)
                # A duplicate must exit without replacing marker, DB or session.
                subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
                assert json.loads(marker.read_text())['token'] == token
                with sqlite3.connect(home/'.hradar/data/research.sqlite') as db:
                    if attempt == 0:
                        db.execute('CREATE TABLE distribution_smoke (value TEXT)')
                        db.execute("INSERT INTO distribution_smoke VALUES ('preserved')")
                    else:
                        assert db.execute('SELECT value FROM distribution_smoke').fetchone() == ('preserved',)
                with sqlite3.connect(database) as db:
                    crm_snapshot = {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in ['recruitment_projects','recruitment_cards','recruitment_publications','monitored_channels','imported_messages','search_templates','template_sources','recruitment_templates']}
                headers['X-Hradar-CSRF'] = state['csrfToken']
                http('/api/desktop/shutdown', {}, headers)
                process.wait(timeout=20)
                assert process.returncode == 0
                assert not marker.exists()
                assert (session/'preserved-sentinel').read_text() == 'preserved'
                assert (home/'.hradar/tdlib/preserved-sentinel').read_text() == 'preserved'
            finally:
                if process.poll() is None:
                    process.terminate(); process.wait(timeout=20)
        # Old backend/foreign port: abort before even opening SQLite/TDLib.
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1',port)); listener.listen()
            subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
            assert not marker.exists()
        # No downgrade of a database created by a newer local feature build.
        with sqlite3.connect(home/'.hradar/data/research.sqlite') as db:
            db.execute('PRAGMA user_version=11')
        subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
        assert not marker.exists()
        with sqlite3.connect(home/'.hradar/data/research.sqlite') as db:
            assert db.execute('PRAGMA user_version').fetchone() == (11,)
            assert db.execute('SELECT value FROM distribution_smoke').fetchone() == ('preserved',)
            assert crm_snapshot == {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in crm_snapshot}
print('Production UI, origin/cookie/CSRF denial, duplicate/legacy-port refusal, restart persistence, newer-schema refusal and graceful shutdown passed')
