#!/usr/bin/env python3
"""Real two-connection tests; Docker Compose must already be running locally."""
import concurrent.futures, json, pathlib, subprocess, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[3]
BASE=['docker','compose','exec','-T','tserver-asia-1','/home/yugabyte/bin/ysqlsh','-X','-qAt','-p','5433','-U','isureal_java_app','-d','isureal_java','-v','ON_ERROR_STOP=1','-v','VERBOSITY=verbose']
class Session:
    def __init__(self,host):
        self.proc=subprocess.Popen(BASE+['-h',host],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    def ask(self,sql):
        self.proc.stdin.write(sql+'\n\\echo __GEO_DONE__\n');self.proc.stdin.flush()
        out=[]
        for line in self.proc.stdout:
            line=line.rstrip('\n')
            if line=='__GEO_DONE__': return '\n'.join(out)
            out.append(line)
        raise RuntimeError('\n'.join(out))
    def close(self):
        if self.proc.poll() is None:
            try:self.proc.stdin.close();self.proc.wait(timeout=10)
            except Exception:self.proc.terminate()
def check(condition,message):
    if not condition:raise AssertionError(message)
def blocked(future,label):
    try: future.result(timeout=0.5)
    except concurrent.futures.TimeoutError:return
    raise AssertionError(label+' did not wait for the held lock')
prefix='geo-race-'+uuid.uuid4().hex[:12]
u=prefix+'-u'; u2=prefix+'-2'; u3=prefix+'-3'; login=prefix+'-s'
quote=lambda x:"'"+x.replace("'","''")+"'"
sessions=[];passed=[]
def connect(region):
    s=Session('tserver-'+region+'-1');sessions.append(s);s.ask("SET statement_timeout='15s';");return s
try:
    a=connect('asia');b=connect('europe');c=connect('america')
    a.ask(f"INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES({quote(u)},{quote(u)},'synthetic','initial','asia');")
    a.ask('BEGIN ISOLATION LEVEL READ COMMITTED;')
    check(a.ask(f'SELECT display_name FROM users WHERE id={quote(u)};')=='initial','initial snapshot')
    b.ask(f"UPDATE users SET display_name='committed' WHERE id={quote(u)};")
    check(a.ask(f'SELECT display_name FROM users WHERE id={quote(u)};')=='committed','RC must see new statement snapshot')
    a.ask('COMMIT;');passed.append('RC statement snapshots through views')
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        a.ask('BEGIN; LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;')
        f=pool.submit(b.ask,'BEGIN; LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;')
        blocked(f,'view table lock');a.ask('COMMIT;');f.result(timeout=20);b.ask('COMMIT;')
        passed.append('view table lock serializes other regional session')
        a.ask(f"BEGIN; UPDATE users SET region='america' WHERE id={quote(u)};")
        f=pool.submit(b.ask,f"INSERT INTO login_sessions(id,user_id,token_hash) VALUES({quote(login)},{quote(u)},'synthetic');")
        blocked(f,'owner move versus child insert');a.ask('COMMIT;');f.result(timeout=20)
        check(c.ask(f'SELECT region FROM users WHERE id={quote(u)};')=='america','move visible across regions')
        passed.append('owner move races child insert without orphan or stale region')
        name=prefix+'-unique'
        a.ask(f"BEGIN; INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES({quote(u2)},{quote(name)},'synthetic','unique','asia');")
        f=pool.submit(b.ask,f"INSERT INTO users(id,username,secret_key_hash,display_name,region) VALUES({quote(u3)},{quote(name)},'synthetic','duplicate','europe');")
        blocked(f,'global username uniqueness');a.ask('COMMIT;')
        try:f.result(timeout=20);raise AssertionError('duplicate username committed')
        except RuntimeError as ex:check('23505' in str(ex),'expected unique violation: '+str(ex))
        passed.append('concurrent cross-region username collision rejects duplicate')
        b=connect('europe')
        a.ask(f"BEGIN; UPDATE users SET display_name='writer-a' WHERE id={quote(u)};")
        f=pool.submit(b.ask,f"UPDATE users SET bio='writer-b' WHERE id={quote(u)};")
        blocked(f,'concurrent update');a.ask('COMMIT;')
        try:f.result(timeout=20)
        except RuntimeError as ex:
            check('40001' in str(ex),'expected retryable stale-row error: '+str(ex))
            b=connect('europe');b.ask(f"UPDATE users SET bio='writer-b' WHERE id={quote(u)};")
        check(c.ask(f"SELECT display_name||'|'||bio FROM users WHERE id={quote(u)};")=='writer-a|writer-b','concurrent update lost another field')
        passed.append('concurrent updates preserve both fields (retry on 40001)')
    # Admin-only integrity check: registry/physical region match is additionally
    # enforced by a composite FK, but verify the raced child moved to America.
    inspect=subprocess.run([x if x!='isureal_java_app' else 'yugabyte' for x in BASE]+['-h','tserver-america-1','-c',f"SELECT placement_region FROM geo.data_login_sessions WHERE id={quote(login)}"],cwd=ROOT,text=True,capture_output=True,check=True)
    check(inspect.stdout.strip()=='america','child inserted into stale owner region')
    print(json.dumps({'passed':passed,'count':len(passed)},indent=2))
finally:
    for s in sessions:s.close()
    cleanup=Session('tserver-asia-1')
    try:
        cleanup.ask(f'DELETE FROM login_sessions WHERE id={quote(login)}; DELETE FROM users WHERE id IN ({quote(u)},{quote(u2)},{quote(u3)});')
    finally:cleanup.close()
