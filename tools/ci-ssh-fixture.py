"""Ephemeral localhost SSH fixture for Android tests. Never uses real user credentials."""
import io,json,pathlib,socket,sys,threading
import paramiko
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding,PrivateFormat,NoEncryption
import hashlib,base64

def generate(path):
    def key():return Ed25519PrivateKey.generate().private_bytes(Encoding.PEM,PrivateFormat.OpenSSH,NoEncryption()).decode()
    host,client=key(),key()
    public=paramiko.Ed25519Key.from_private_key(io.StringIO(host))
    fingerprint='SHA256:'+base64.b64encode(hashlib.sha256(public.asbytes()).digest()).decode().rstrip('=')
    target=pathlib.Path(path);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps({'hostKey':host,'clientKey':client,'fingerprint':fingerprint}),encoding='utf-8')
    print('Ephemeral SSH fixture generated')

def serve(path):
    config=json.loads(pathlib.Path(path).read_text(encoding='utf-8'))
    host=paramiko.Ed25519Key.from_private_key(io.StringIO(config['hostKey']))
    client=paramiko.Ed25519Key.from_private_key(io.StringIO(config['clientKey']))
    class Server(paramiko.ServerInterface):
        def __init__(self):self.ready=threading.Event()
        def get_allowed_auths(self,username):return 'publickey,password'
        def check_auth_publickey(self,username,key):return paramiko.AUTH_SUCCESSFUL if username=='fixture' and key.asbytes()==client.asbytes() else paramiko.AUTH_FAILED
        def check_auth_password(self,username,password):return paramiko.AUTH_SUCCESSFUL if username=='fixture' and password=='fixture-only' else paramiko.AUTH_FAILED
        def check_channel_request(self,kind,chanid):return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        def check_channel_exec_request(self,channel,command):self.ready.set();return True
    def handle(sock):
        transport=paramiko.Transport(sock);transport.add_server_key(host);server=Server()
        try:
            transport.start_server(server=server);channel=transport.accept(30)
            if channel is None or not server.ready.wait(30):return
            channel.send_stderr(b'__CP_CWD__=/home/fixture\n__CP_BIN__=/usr/local/bin/codex\n')
            for raw in channel.makefile('rb'):
                event=json.loads(raw.decode());method=event.get('method')
                if 'id' not in event:continue
                result={'userAgent':'android-ssh-fixture','platformOs':'linux','platformFamily':'unix','codexHome':'/home/fixture/.codex'} if method=='initialize' else {}
                if method in ('thread/start','thread/resume'):result={'thread':{'id':'fixture-thread','cwd':'/home/fixture'}}
                if method in ('turn/start','turn/steer'):
                    params=event['params'];assert params['threadId']=='fixture-thread'
                    if method=='turn/steer':assert params['expectedTurnId']=='fixture-turn'
                    for item in params['input']:
                        if item['type']=='image':assert item['url'].startswith('data:image/jpeg;base64,') and base64.b64decode(item['url'].split(',',1)[1]).startswith(b'\xff\xd8')
                    result={'turn':{'id':'fixture-turn'}} if method=='turn/start' else {'turnId':'fixture-turn'}
                    print('Validated '+method+' input; image count='+str(sum(i['type']=='image' for i in params['input'])),flush=True)
                channel.sendall((json.dumps({'id':event['id'],'result':result})+'\n').encode())
        except Exception as e:print('Fixture transport closed: '+type(e).__name__,flush=True)
        finally:transport.close();sock.close()
    listener=socket.socket();listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(('0.0.0.0',2222));listener.listen(8)
    print('SSH fixture ready on port 2222',flush=True)
    while True:
        sock,_=listener.accept();threading.Thread(target=handle,args=(sock,),daemon=True).start()

if __name__=='__main__':
    {'generate':generate,'serve':serve}[sys.argv[1]](sys.argv[2])
