#!/usr/bin/env python3
"""Isolated packaged lifecycle smoke. No Telegram client, credentials or user directories."""
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.request, urllib.error, sqlite3, socket, shutil, fcntl, hashlib
app = pathlib.Path(sys.argv[1]).resolve()
launcher = app / 'Contents/MacOS/hradar'
port = int(sys.argv[2]) if len(sys.argv) > 2 else 1709
metadata = json.loads(pathlib.Path(sys.argv[3]).read_text()) if len(sys.argv) > 3 else None
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

def document_bytes(document_id, headers):
    request = urllib.request.Request(f'http://127.0.0.1:{port}/api/documents/{document_id}/file', headers=headers)
    with urllib.request.urlopen(request, timeout=3) as response:
        assert response.status == 200
        assert response.headers['X-Hradar-Workspace'] == headers['X-Hradar-Workspace']
        assert response.headers['X-Content-Type-Options'] == 'nosniff'
        return response.read()

def wait_ready(process, marker):
    # Bounded cold start: heavily loaded developer Macs can exceed ten seconds.
    for _ in range(300):
        if process.poll() is not None: raise RuntimeError('Packaged process exited early')
        if marker.exists():
            http('/health'); return json.loads(marker.read_text())['token']
        time.sleep(.1)
    raise RuntimeError('Packaged server never became ready\n' + '\n'.join((marker.parent.parent/'runtime.log').read_text().splitlines()[-20:]))

def selected_workspace(home):
    registry = json.loads((home / '.hradar/profiles.json').read_text())
    profile_id = registry['activeId']
    import uuid
    assert str(uuid.UUID(profile_id)) == profile_id
    assert sum(profile['id'] == profile_id for profile in registry['profiles']) == 1
    return home / '.hradar/profiles' / profile_id

def wait_profile(headers):
    for _ in range(100):
        state = http('/api/profiles/status', headers=headers)
        if state['phase'] == 'READY':
            assert state['identity']['userId'] > 0 and state['active']['confirmed']
            return state
        time.sleep(.1)
    raise RuntimeError('Synthetic profile identity never became READY')

def wait_graph_change(process, marker, previous_token):
    for _ in range(300):
        if process.poll() is not None:
            raise RuntimeError('Profile switch exited the packaged process')
        try:
            value = json.loads(marker.read_text())
            assert value['pid'] == process.pid, 'Profile switch must retain the same JVM'
            if value['token'] != previous_token:
                candidate = {'Cookie':'hradar-desktop='+value['token'], 'Origin':f'http://127.0.0.1:{port}', 'Content-Type':'application/json'}
                http('/health')
                profile = wait_profile(candidate)
                candidate['X-Hradar-Workspace'] = profile['generation']
                onboarding = http('/api/onboarding', headers=candidate)
                candidate['X-Hradar-CSRF'] = onboarding['csrfToken']
                return value['token'], candidate, profile, onboarding
        except (OSError, ValueError):
            # Marker removal/atomic replacement and listener closure occur between graphs.
            pass
        time.sleep(.1)
    raise RuntimeError('Profile service graph did not restart with fresh authority')

def assert_leases_released(home):
    for lock in [home / '.hradar/hradar.lock', selected_workspace(home) / 'tdlib/hradar.lock']:
        with lock.open('r+') as handle:
            fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.lockf(handle, fcntl.LOCK_UN)

def user_database_snapshot(db):
    tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    # FTS5 shadow tables can be WITHOUT ROWID; compare every row without assuming rowid.
    return {table: sorted(db.execute(f'SELECT * FROM "{table}"').fetchall(), key=repr) for table in tables}

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
                listeners = subprocess.check_output(['/usr/sbin/lsof','-nP','-a','-p',str(process.pid),'-iTCP','-sTCP:LISTEN','-Fn'],text=True)
                assert [line for line in listeners.splitlines() if line.startswith('n')] == [f'n127.0.0.1:{port}'], listeners
                cookie = 'hradar-desktop='+token
                headers = {'Cookie':cookie, 'Origin':f'http://127.0.0.1:{port}', 'Content-Type':'application/json'}
                http('/api/onboarding', expected=403)
                for path in ['/%61pi/onboarding', '//api/onboarding', '/%61pi/events']:
                    http(path, expected=403)
                for host in ['foreign.localhost:'+str(port), 'hradar.localhost:'+str(port)+'.evil', '[::1]:'+str(port), '192.168.1.2:'+str(port), '127.0.0.1:5173']:
                    http('/health', headers={'Host':host}, expected=403)
                http('/mcp', {}, expected=403)
                http('/desktop/recover', {}, {'Origin':f'http://hradar.localhost:{port}','X-Hradar-Recovery':'1'}, expected=403)
                http('/api/onboarding', headers={'Cookie':cookie,'Origin':'https://evil.example'}, expected=403)
                profile = wait_profile(headers)
                generation = profile['generation']
                assert profile['legacyAvailable'], 'External legacy session must remain available for explicit import'
                http('/api/onboarding', headers=headers, expected=409)
                headers['X-Hradar-Workspace'] = generation
                stale_headers = dict(headers, **{'X-Hradar-Workspace':'stale-generation'})
                http('/api/projects', headers=stale_headers, expected=409)
                state = http('/api/onboarding', headers=headers)
                assert state['state'] == 'Credentials'
                # Stage C routes use the same packaged hostname/cookie/CSRF policy.
                local_headers = dict(headers, Host=f'hradar.localhost:{port}', Origin=f'http://hradar.localhost:{port}')
                if attempt == 0: assert http('/api/local-notifications', headers=local_headers)['items'] == []
                http('/api/sources', headers=local_headers)
                for path in ['/api/local-notifications', '/%61pi/local-notifications', '/api//local-notifications']:
                    http(path, headers={'Host':f'hradar.localhost:{port}'}, expected=403)
                for path in ['/api/local-notifications/read-all', '/api/local-%6eotifications/read-all', '/api/sources/synthetic/monitoring', '/api/sources/synthetic/messages/5/discussion']:
                    http(path, {}, local_headers, expected=403)
                local_headers['X-Hradar-CSRF'] = state['csrfToken']
                assert http('/api/local-notifications/read-all', {}, local_headers) == {'read':True}

                http('/api/onboarding/credentials', {'apiId':1,'apiHash':'0'*32}, headers, expected=403)
                http('/api/%6fnboarding/credentials', {'apiId':1,'apiHash':'0'*32}, headers, expected=403)
                http('/api/projects', expected=403)
                http('/api/projects', headers={'Cookie':cookie,'Origin':'https://evil.example'}, expected=403)
                workspace = selected_workspace(home)
                assert workspace.name == profile['active']['id']
                database = workspace / 'data/research.sqlite'
                if attempt == 0:
                    with sqlite3.connect(database) as db:
                        assert db.execute('PRAGMA user_version').fetchone() == (14,)
                        if metadata is not None:
                            assert metadata['sqliteSchemaVersion'] == db.execute('PRAGMA user_version').fetchone()[0], 'Installer metadata must match the actual SQLite schema'
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
                    # Actual service-graph A → B → A, using synthetic smoke identities only.
                    document_id = 'smoke-profile-a-document'
                    document_content = b'Synthetic Kotlin engineer profile A document.'
                    document_hash = hashlib.sha256(document_content).hexdigest()
                    document_sentinel = workspace / 'documents' / document_hash
                    document_sentinel.write_bytes(document_content)
                    document_sentinel.chmod(0o600)
                    document = {'id':document_id,
                                'message':{'id':9,'channelId':-1001,'date':1700000000,'text':'Synthetic Kotlin document publication',
                                           'title':'Synthetic source','username':'synthetic_jobs','sourceKey':'smoke-document-source'},
                                'fileName':'profile-a-kotlin.txt','mimeType':'text/plain','size':len(document_content),
                                'format':'txt','state':'READY','downloadedBytes':len(document_content),'hasFile':True,'hasText':True}
                    bot_identity = {'id':123,'username':'synthetic_bot','name':'Synthetic disabled bot'}
                    a_uid = profile['identity']['userId']
                    with sqlite3.connect(database) as db:
                        db.execute('INSERT INTO documents(id,source_key,message_id,file_id,remote_id,hash,data,text) VALUES(?,?,?,?,?,?,?,?)',
                                   (document_id,'smoke-document-source',9,99,'synthetic-profile-a-remote',document_hash,json.dumps(document),document_content.decode()))
                        db.execute('INSERT INTO document_fts(id,file_name,text) VALUES(?,?,?)',
                                   (document_id,document['fileName'],document_content.decode()))
                        db.execute('INSERT INTO bot_access(bot_id,user_id,role,name,username) VALUES(?,?,?,?,?)',
                                   (123,a_uid,'owner','Synthetic profile A owner',None))
                    bot_configuration = workspace / 'config/telegram-bot.json'
                    bot_configuration.write_text(json.dumps({'token':'123:synthetic','identity':bot_identity,'enabled':False}))
                    bot_configuration.chmod(0o600)
                    assert http('/api/documents/search?q=Kotlin', headers=headers)['items'][0]['document']['id'] == document_id
                    assert document_bytes(document_id, headers) == document_content
                    # Reload A once so the disabled saved bot is loaded through the real
                    # constructor. Smoke mode never starts a Telegram client or bot poller.
                    http(f"/api/profiles/{profile['active']['id']}/switch", {'confirm':True}, dict(headers, **{'X-Hradar-CSRF':state['csrfToken']}))
                    token, headers, profile, state = wait_graph_change(process, marker, token)
                    generation, cookie = profile['generation'], headers['Cookie']
                    bot_a = http('/api/integrations/bot/settings', headers=headers)
                    assert bot_a['configured'] and not bot_a['enabled']
                    assert bot_a['identity'] == bot_identity and bot_a['owner']['id'] == a_uid
                    a_id, a_generation, a_token = profile['active']['id'], generation, token
                    a_state, a_headers = state, dict(headers)
                    change_headers = dict(headers, **{'X-Hradar-CSRF':state['csrfToken']})
                    http('/api/profiles/add', {'confirm':True}, change_headers)
                    token, headers, profile, state = wait_graph_change(process, marker, a_token)
                    b_id, b_generation = profile['active']['id'], profile['generation']
                    assert b_id != a_id and b_generation != a_generation
                    assert state['csrfToken'] != a_state['csrfToken']
                    assert token != a_token and profile['identity']['userId'] > 0
                    assert profile['identity']['userId'] != a_uid
                    with (workspace / 'tdlib/hradar.lock').open('r+') as released_a:
                        fcntl.lockf(released_a, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        fcntl.lockf(released_a, fcntl.LOCK_UN)
                    b_workspace = selected_workspace(home)
                    assert b_workspace.name == b_id
                    assert http('/api/projects', headers=headers)['items'] == []
                    http(f'/api/projects/{project_id}', headers=headers, expected=404)
                    assert not (b_workspace / 'documents' / document_hash).exists()
                    assert http('/api/documents/search?q=Kotlin', headers=headers)['items'] == []
                    http(f'/api/documents/{document_id}/file', headers=headers, expected=404)
                    bot_b = http('/api/integrations/bot/settings', headers=headers)
                    assert not bot_b['configured'] and not bot_b['enabled'] and bot_b.get('owner') is None
                    # A's generation cannot read through B's fresh owner cookie, including SSE.
                    stale_a = dict(a_headers, Cookie=headers['Cookie'])
                    http('/api/projects', headers=stale_a, expected=409)
                    http(f'/api/events?workspace={a_generation}', headers={'Cookie':headers['Cookie']}, expected=409)
                    b_token, b_csrf = token, state['csrfToken']
                    http(f'/api/profiles/{a_id}/switch', {'confirm':True}, headers)
                    token, headers, profile, state = wait_graph_change(process, marker, b_token)
                    generation, cookie = profile['generation'], headers['Cookie']
                    assert profile['active']['id'] == a_id and profile['identity']['userId'] == a_uid
                    with (b_workspace / 'tdlib/hradar.lock').open('r+') as released_b:
                        fcntl.lockf(released_b, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        fcntl.lockf(released_b, fcntl.LOCK_UN)
                    assert generation not in (a_generation, b_generation)
                    assert state['csrfToken'] not in (a_state['csrfToken'], b_csrf)
                    assert token not in (a_token, b_token)
                    assert selected_workspace(home) == workspace
                    assert document_sentinel.read_bytes() == document_content
                    assert http('/api/documents/search?q=Kotlin', headers=headers)['items'][0]['document']['id'] == document_id
                    assert document_bytes(document_id, headers) == document_content
                    bot_restored = http('/api/integrations/bot/settings', headers=headers)
                    assert bot_restored['configured'] and not bot_restored['enabled']
                    assert bot_restored['identity'] == bot_identity and bot_restored['owner']['id'] == a_uid
                    assert http(f'/api/projects/{project_id}', headers=headers)['criteria']['name'] == 'Installer CRM sentinel'
                    restored_card = http(f'/api/projects/{project_id}/cards', headers=headers)['items'][0]
                    assert (restored_card['stage'], restored_card['notes'], restored_card['interest']) == ('CONTACTED','Synthetic preserved note',5)
                    stale_b = dict(headers, **{'X-Hradar-Workspace':b_generation})
                    http('/api/projects', headers=stale_b, expected=409)
                    http(f'/api/events?workspace={b_generation}', headers={'Cookie':cookie}, expected=409)
                    local_headers = dict(headers, Host=f'hradar.localhost:{port}', Origin=f'http://hradar.localhost:{port}')
                else:
                    assert http(f'/api/projects/{project_id}', headers=headers)['criteria']['name'] == 'Installer CRM sentinel'
                    results = http(f'/api/projects/{project_id}/search', {'mode':'archive','query':'java'}, headers)
                    assert len(results['messages']) == 1
                    card = http(f'/api/projects/{project_id}/cards', headers=headers)['items'][0]
                    assert (card['stage'],card['notes'],card['interest']) == ('CONTACTED','Synthetic preserved note',5)
                    with sqlite3.connect(database) as db:
                        assert db.execute('PRAGMA user_version').fetchone() == (14,)
                        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                        assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                        assert crm_snapshot == {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in crm_snapshot}
                if attempt == 0:
                    # Synthetic existing Stage C state; never calls a native Telegram client.
                    rule = http('/api/templates', {'name':'Synthetic local rule','required':['Java'],'telegramQuery':'Java'}, local_headers, expected=201)
                    local_source = {'key':'smoke-local-source','chatId':3001,'type':'PRIVATE','title':'Synthetic cloud chat'}
                    local_subscription = {'id':'smoke-local-subscription','sourceKey':local_source['key'],'templateId':rule['id'],
                                          'enabled':False,'checkpoint':42,'recoveryCursor':40,'recoveryHead':44,'sinceDate':1700000000,'status':'paused'}
                    local_match = {'id':'smoke-local-match','templateId':rule['id'],'templateName':rule['name'],
                                   'message':{'id':42,'channelId':3001,'date':1700000000,'text':'Synthetic local Java','title':local_source['title'],'username':None,'sourceKey':local_source['key']}}
                    with sqlite3.connect(database) as db:
                        db.execute('INSERT INTO telegram_sources VALUES(?,?,?,?,?,?)', (local_source['key'],3001,'CHAT',0,None,json.dumps(local_source)))
                        db.execute('INSERT INTO local_subscriptions VALUES(?,?,?,?)', (local_subscription['id'],local_source['key'],rule['id'],json.dumps(local_subscription)))
                        db.execute('INSERT INTO local_matches(id,template_id,chat_id,message_id,data) VALUES(?,?,?,?,?)', (local_match['id'],rule['id'],3001,42,json.dumps(local_match)))
                    assert http('/api/local-notifications', headers=local_headers)['unread'] == 1
                    denied_headers = dict(local_headers); del denied_headers['X-Hradar-CSRF']
                    http('/api/local-%6eotifications/smoke-local-match/read', {}, denied_headers, expected=403)
                    assert http('/api/local-notifications', headers=local_headers)['unread'] == 1
                    http('/api/local-%6eotifications/smoke-local-match/read', {}, local_headers)
                matches = http('/api/local-notifications', headers=local_headers)
                assert matches['unread'] == 0 and len(matches['items']) == 1 and matches['items'][0]['read']
                subscriptions = http('/api/sources/smoke-local-source/monitoring', headers=local_headers)
                assert len(subscriptions) == 1 and subscriptions[0]['checkpoint'] == 42
                assert subscriptions[0]['recoveryCursor'] == 40 and subscriptions[0]['recoveryHead'] == 44 and not subscriptions[0]['enabled']
                http('/api/sources/smoke-local-source/monitoring', {'templateId':subscriptions[0]['templateId'],'enabled':False}, local_headers)
                # Browser executes actual production JS/CSS and the launch handshake.
                if attempt == 0:
                    browser_env = dict(os.environ, HRADAR_SMOKE_TOKEN=token, HRADAR_SMOKE_PORT=str(port))
                    subprocess.run(['node',str(root/'frontend/scripts/smoke-packaged.mjs')],env=browser_env,check=True,timeout=60)
                # A duplicate must exit without replacing marker, DB or session.
                subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
                assert json.loads(marker.read_text())['token'] == token
                with sqlite3.connect(selected_workspace(home) / 'data/research.sqlite') as db:
                    if attempt == 0:
                        db.execute('CREATE TABLE distribution_smoke (value TEXT)')
                        db.execute("INSERT INTO distribution_smoke VALUES ('preserved')")
                    else:
                        assert db.execute('SELECT value FROM distribution_smoke').fetchone() == ('preserved',)
                with sqlite3.connect(database) as db:
                    crm_snapshot = {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in ['recruitment_projects','recruitment_cards','recruitment_publications','monitored_channels','imported_messages','search_templates','template_sources','recruitment_templates','telegram_sources','local_subscriptions','local_matches','local_monitoring_limits']}
                headers['X-Hradar-CSRF'] = state['csrfToken']
                http('/api/desktop/shutdown', {}, headers)
                process.wait(timeout=20)
                assert process.returncode == 0
                assert not marker.exists()
                with socket.socket() as released:
                    released.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    released.bind(('127.0.0.1', port))
                assert (session/'preserved-sentinel').read_text() == 'preserved'
                assert not (workspace / 'tdlib/preserved-sentinel').exists()
                assert not (home / '.hradar/tdlib').exists(), 'External legacy session was imported automatically'
                assert_leases_released(home)
            finally:
                if process.poll() is None:
                    process.terminate(); process.wait(timeout=20)
        # Packaged agent without a GUI must fail and clean up, never become invisible.
        headless_env = dict(env, JAVA_TOOL_OPTIONS=env['JAVA_TOOL_OPTIONS']+' -Djava.awt.headless=true')
        failed = subprocess.run([str(launcher)], env=headless_env, stdout=log, stderr=log, timeout=20)
        assert failed.returncode != 0
        assert_leases_released(home)
        assert not marker.exists()
        with socket.socket() as released:
            released.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            released.bind(('127.0.0.1', port))
        # A real browser tab stays open while two packaged processes start/stop.
        subprocess.run([shutil.which('node'),str(root/'frontend/scripts/smoke-desktop-restart.mjs'),str(launcher),str(home),str(port)],
                       env=env,check=True,timeout=180)
        # Old backend/foreign port: abort before even opening SQLite/TDLib.
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1',port)); listener.listen()
            subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
            assert not marker.exists()
        # A forged live marker cannot identify a foreign HTTP server as hradar.
        import http.server, threading
        captured = []
        class Foreign(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                captured.append(self.rfile.read(int(self.headers['Content-Length'])))
                payload = json.dumps({'launch':'x'*43,'proof':'forged'}).encode()
                self.send_response(200); self.send_header('Content-Length',str(len(payload))); self.end_headers(); self.wfile.write(payload)
            def log_message(self, *args): pass
        server = http.server.HTTPServer(('127.0.0.1',port), Foreign)
        thread = threading.Thread(target=server.serve_forever); thread.start()
        fake_secret = 'owner-marker-secret-not-to-be-sent'
        marker.write_text(json.dumps({'pid':os.getpid(),'token':fake_secret}))
        try:
            offset = log.tell()
            subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,check=True,timeout=20)
            assert captured and fake_secret.encode() not in captured[0]
            log.flush()
            assert 'Порт'.encode() in (home/'runtime.log').read_bytes()[offset:] and 'занят'.encode() in (home/'runtime.log').read_bytes()[offset:]
        finally:
            server.shutdown(); server.server_close(); thread.join(); marker.unlink()
        # No downgrade of a database created by a newer local feature build.
        with sqlite3.connect(selected_workspace(home) / 'data/research.sqlite') as db:
            tables_snapshot = user_database_snapshot(db)
            db.execute('PRAGMA user_version=15')
        refused = subprocess.run([str(launcher)],env=env,stdout=log,stderr=log,timeout=20)
        assert refused.returncode == 0, f'Newer-schema refusal did not exit cleanly: {refused.returncode}'
        assert not marker.exists()
        assert_leases_released(home)
        log.flush()
        assert 'База данных создана более новой версией hradar' in (home/'runtime.log').read_text(), 'Application did not report refusing the newer schema'
        with sqlite3.connect(selected_workspace(home) / 'data/research.sqlite') as db:
            assert db.execute('PRAGMA user_version').fetchone() == (15,)
            assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            assert db.execute('PRAGMA foreign_key_check').fetchall() == []
            assert user_database_snapshot(db) == tables_snapshot
            assert db.execute('SELECT value FROM distribution_smoke').fetchone() == ('preserved',)
            assert crm_snapshot == {table:db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in crm_snapshot}
print('Production UI, origin/cookie/CSRF denial, A-B-A graph isolation, duplicate/legacy-port refusal, restart persistence, newer-schema refusal and graceful shutdown passed')
