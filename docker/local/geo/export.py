#!/usr/bin/env python3
"""Export logical view rows as parent-table INSERT SQL in one consistent snapshot.
This is intentionally not ysql_dump: physical geo tables and global registries
must never be imported independently through the application's seed interface.
"""
import argparse,json,os,pathlib,subprocess,tempfile
HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parents[2]
ORDER=['users','administrators','image_objects','login_sessions','administrator_sessions','friendships','follows','block_relations','posting_windows','posts','friend_requests','realmojis','comments','notifications','ip_bans']
def export_sql():
    entities=json.loads((HERE/'entities.json').read_text())
    queries=["BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;", "SET LOCAL timezone='UTC';", "SET LOCAL bytea_output='hex';", "SET LOCAL standard_conforming_strings=on;"]
    for table in ORDER:
        cols=list(entities[table]['fields'])
        prefix='INSERT INTO public.'+table+' ('+','.join(cols)+') VALUES ('
        values=" || ',' || ".join('quote_nullable('+col+'::text)' for col in cols)
        queries.append("SELECT '"+prefix+"' || "+values+" || ');' FROM public."+table+' ORDER BY '+','.join(entities[table]['pk'])+';')
    queries.append('COMMIT;')
    return '\n'.join(queries)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',required=True,type=pathlib.Path)
    args=p.parse_args(); target=args.output.resolve()
    if target.exists():p.error('output already exists; refusing to overwrite')
    if not target.parent.is_dir():p.error('output parent must exist')
    fd,tmp=tempfile.mkstemp(prefix='.geo-export-',suffix='.sql',dir=target.parent)
    try:
        with os.fdopen(fd,'wb') as out:
            out.write(b'-- Logical geo export: parent INSERTs only, restore into an EMPTY geo database.\nSET standard_conforming_strings=on;\n')
            result=subprocess.run(['docker','compose','exec','-T','tserver-asia-1','/home/yugabyte/bin/ysqlsh','-X','-qAt','-h','localhost','-p','5433','-U','isureal_java_app','-d','isureal_java','-v','ON_ERROR_STOP=1'],input=export_sql().encode(),stdout=out,stderr=subprocess.PIPE,cwd=ROOT)
            if result.returncode:raise RuntimeError('logical export failed: '+result.stderr.decode())
        # Hardlink ensures an existing destination cannot be accidentally replaced.
        os.link(tmp,target)
        print('Logical geo export written:',target)
    finally:
        os.unlink(tmp)
if __name__=='__main__':main()
