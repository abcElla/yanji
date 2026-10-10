"""Optional development smoke test of the packaged JAR. Uses synthetic data and an in-memory DB."""
import json
import os
import re
import pathlib
import socket
import subprocess
import time
import urllib.request
import uuid

root=pathlib.Path(__file__).resolve().parents[1]
with socket.socket() as probe:
    probe.bind(('127.0.0.1',0))
    port=probe.getsockname()[1]
base=f'http://127.0.0.1:{port}'
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def call(path,body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:
        headers['Authorization']='Bearer '+token
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    with opener.open(request,timeout=10) as response:
        result=json.load(response)
        assert response.headers.get('X-Request-Id')
        return result

with (root/'target/smoke-api.log').open('w',encoding='utf-8') as log:
    runtime=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True,check=True)
    java_home=re.search(r'java.home\s*=\s*(.+)',runtime.stderr).group(1).strip()
    java=str(pathlib.Path(java_home)/'bin'/('java.exe' if os.name=='nt' else 'java'))
    process=subprocess.Popen([java,'-jar','target/backend-java-1.0.0.jar',f'--server.port={port}',
        '--server.address=127.0.0.1',
        '--spring.datasource.url=jdbc:h2:mem:smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY',
        '--yanji.storage-root=./target/smoke-files', '--yanji.agent-dispatch-url=',
        '--yanji.internal-service-key=', '--yanji.agent-receiver-key=', '--yanji.workers-enabled=true'],
        cwd=root,stdout=log,stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    try:
        for attempt in range(40):
            try:
                assert call('/health/live')['data']['status']=='ok'
                break
            except Exception:
                if process.poll() is not None:
                    raise RuntimeError('JAR exited; inspect target/smoke-api.log')
                time.sleep(.5)
        else:
            raise RuntimeError('JAR startup timed out')
        assert call('/health/ready')['data']['status']=='ready'
        assert call('/api/v1/client-config')['data']['api_contract']=='3.3'
        spec=call('/openapi.json')
        assert sum(sum(k in {'get','post','patch','delete','put'} for k in v) for v in spec['paths'].values())==75
        username='smoke_'+uuid.uuid4().hex[:12]
        user=call('/api/v1/auth/register',{'username':username,'password':'Synthetic123','display_name':'HTTP smoke','role':'student'})
        login=call('/api/v1/auth/login',{'username':username,'password':'Synthetic123','installation_id':str(uuid.uuid4()),'device_label':'smoke'})
        me=call('/api/v1/me',token=login['data']['access_token'])
        assert me['data']['user']['id']==user['data']['user_id']
        assert me['data']['onboarding_status']=='choose_mentor'
        result={'status':'passed','transport':'real HTTP on loopback','endpoints':['/health/live','/health/ready','/api/v1/client-config','/openapi.json','/api/v1/auth/register','/api/v1/auth/login','/api/v1/me'],'openapi_operations':75,'database':'isolated in-memory H2','process_stopped_after_test':True}
        (root/'target/smoke-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False))
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
