import base64
import concurrent.futures
import http.client
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
import zipfile
from pathlib import Path
from unittest.mock import patch
import feedback_server as s

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')
def multipart(files=None, fields=None, kind='feedback'):
    boundary = 'test-' + uuid.uuid4().hex
    values = dict(issueType='missed', appName='示例应用', consent='true', requestId=uuid.uuid4().hex)
    if kind == 'wish':
        values = dict(description='希望按钮更大一些', consent='true', requestId=uuid.uuid4().hex)
    values.update(fields or {})
    parts = []
    for name, value in values.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    for name, data, mime in (files if files is not None else [('screen.png', PNG, 'image/png')]):
        parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="attachments"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n'.encode(), data, b'\r\n']
    parts.append(f'--{boundary}--\r\n'.encode())
    return f'multipart/form-data; boundary={boundary}', b''.join(parts)

class FeedbackTests(unittest.TestCase):
    def setUp(self):
        self.umask = os.umask(0o077)
        self.temp = tempfile.TemporaryDirectory()
        self.data = Path(self.temp.name) / 'private'
        self.store = s.Store(self.data, 128 * s.MIB, 0)
        self.http = s.FeedbackServer(('127.0.0.1', 0), self.store, s.RateLimiter(100,1000))
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
    def tearDown(self):
        self.http.shutdown(); self.http.server_close(); self.thread.join()
        self.temp.cleanup(); os.umask(self.umask)
    def request(self, method='POST', path='/api/feedback', files=None, fields=None, headers=None, pair=None):
        kind, body = pair or multipart(files, fields, 'wish' if path == '/api/wishes' else 'feedback')
        head = {'Content-Type':kind, 'Origin':'https://feedback.xadskip.com'}
        head.update(headers or {})
        c = http.client.HTTPConnection(*self.http.server_address, timeout=10)
        try:
            c.request(method,path,body if method=='POST' else None,head)
            r = c.getresponse(); data = r.read()
            return r.status, json.loads(data) if data else None, dict(r.headers)
        finally: c.close()
    def rows(self):
        with self.store.connect() as db: return db.execute('SELECT * FROM feedback').fetchall()
    def empty(self):
        self.assertEqual(self.rows(), []); self.assertEqual(list(self.store.uploads.iterdir()), [])
    def test_http_multipart_private_storage_and_no_public_urls(self):
        status,r,h = self.request(files=[('../../private.png',PNG,'text/plain')])
        self.assertEqual(status,201)
        self.assertEqual(set(r), {'ok','feedbackId','createdAt','duplicate'})
        self.assertEqual(h['Access-Control-Allow-Origin'],'https://feedback.xadskip.com')
        self.assertEqual(h['Cache-Control'],'no-store')
        folder = self.store.uploads/r['feedbackId']; target=folder/'attachment-1.png'
        self.assertEqual(target.read_bytes(),PNG)
        self.assertEqual(folder.stat().st_mode&0o777,0o700)
        self.assertEqual(target.stat().st_mode&0o777,0o600)
        self.assertEqual(self.store.db_path.stat().st_mode&0o777,0o600)
        self.assertEqual(json.loads(self.rows()[0]['attachments_json'])[0]['originalName'],'private.png')
    def test_fake_image_rejected_without_disk_files(self):
        status,r,_=self.request(files=[('fake.png',b'<script>not a picture</script>','image/png')])
        self.assertEqual((status,r['error']['code']),(400,'invalid_media')); self.empty()
    def test_actual_8mib_image_limit(self):
        data=b'\xff\xd8\xff'+b'x'*(s.IMAGE_LIMIT-4)+b'\xff\xd9'
        self.assertEqual(self.request(files=[('large.jpg',data,'image/jpeg')])[0],413); self.empty()
    def test_declared_total_limit_before_reading_body(self):
        self.assertEqual(self.request(headers={'Content-Length':str(s.TOTAL_LIMIT+1)})[0],413); self.empty()
    def test_attachment_counts(self):
        self.assertEqual(self.request(files=[])[0],400)
        self.assertEqual(self.request(files=[('a.png',PNG,'image/png')]*4)[0],400); self.empty()
    def test_video_container_and_mixed_attachment_counts(self):
        def box(kind,payload=b''):
            return (8+len(payload)).to_bytes(4,'big')+kind+payload
        mp4=box(b'ftyp',b'isom'+bytes(4))+box(b'moov')+box(b'mdat',b'example')
        self.assertEqual(s.detect_media(mp4),('video/mp4','mp4'))
        webm=b'\x1a\x45\xdf\xa3\x87\x42\x82\x84webm\x18\x53\x80\x67\xff'
        self.assertEqual(s.detect_media(webm),('video/webm','webm'))
        files=[('a.png',PNG,'image/png')]*3+[('clip.mp4',mp4,'video/mp4')]
        self.assertEqual(self.request(files=files)[0],201)
        self.assertEqual(self.request(files=[('a.mp4',mp4,'video/mp4')]*2)[0],400)
        self.assertEqual(len(self.rows()),1)
    def test_actual_30mib_video_limit(self):
        data=bytes(s.VIDEO_LIMIT+1)
        self.assertEqual(self.request(files=[('clip.mp4',data,'video/mp4')])[0],413)
        self.empty()
    def test_webp_signature_and_truncated_containers(self):
        webp=b'RIFF'+(16).to_bytes(4,'little')+b'WEBPVP8L'+(4).to_bytes(4,'little')+b'abcd'
        self.assertEqual(s.detect_media(webp),('image/webp','webp'))
        self.assertEqual(self.request(files=[('image.webp',webp,'image/webp')])[0],201)
        for data in [PNG[:-2],webp[:-1],b'\x1a\x45\xdf\xa3not-webm']:
            with self.assertRaises(s.FeedbackError):s.detect_media(data)
    def test_invalid_fields(self):
        for fields in [{'consent':'false'},{'appName':''},{'issueType':'invalid'}, {'description':'x'*5001},{'requestId':'short'},{'email':'invalid email'}]:
            self.assertEqual(self.request(fields=fields)[0],400)
        self.empty()
    def test_concurrent_http_idempotency(self):
        pair=multipart()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:self.request(pair=pair),range(2)))
        self.assertEqual(sorted(r[0] for r in results),[200,201])
        self.assertEqual(len({r[1]['feedbackId'] for r in results}),1)
        self.assertEqual(len(self.rows()),1); self.assertEqual(len(list(self.store.uploads.iterdir())),1)
    def test_idempotency_conflict(self):
        key=uuid.uuid4().hex
        self.assertEqual(self.request(fields={'requestId':key})[0],201)
        status,r,_=self.request(fields={'requestId':key,'description':'changed'})
        self.assertEqual((status,r['error']['code']),(409,'request_id_conflict')); self.assertEqual(len(self.rows()),1)
    def test_database_failure_after_rename_cleanup_and_recovery(self):
        with self.store.connect() as db:
            db.execute("CREATE TRIGGER injected BEFORE INSERT ON feedback BEGIN SELECT RAISE(ABORT, 'test'); END")
        self.assertEqual(self.request()[0],500); self.empty()
        with self.store.connect() as db: db.execute('DROP TRIGGER injected')
        self.assertEqual(self.request()[0],201)
    def test_file_write_failure_cleanup(self):
        with patch.object(s.os,'fsync',side_effect=OSError('injected')):
            self.assertEqual(self.request()[0],500)
        self.empty()
    def test_disk_quota(self):
        self.store.quota_bytes=1
        self.assertEqual(self.request()[0],507); self.empty()
    def test_startup_recovers_only_uncommitted_files(self):
        _,result,_=self.request()
        for name in ['.pending-'+uuid.uuid4().hex,uuid.uuid4().hex]:
            p=self.store.uploads/name; p.mkdir(); (p/'partial').write_bytes(b'partial')
        self.store.recover()
        self.assertEqual([p.name for p in self.store.uploads.iterdir()],[result['feedbackId']])
    def test_health_and_no_read_admin_or_attachment_endpoint(self):
        _,r,_=self.request()
        for path in ['/api/feedback/'+r['feedbackId'],'/admin','/attachments/'+r['feedbackId']]:
            self.assertEqual(self.request('GET',path)[0],404)
        self.assertEqual(self.request('GET','/api/health')[:2],(200,{'ok':True}))
    def test_origin_rejection(self):
        self.assertEqual(self.request(headers={'Origin':'https://evil.example'})[0],403); self.empty()
    def test_rate_limit_by_real_ip(self):
        self.http.limiter=s.RateLimiter(1,10)
        self.assertEqual(self.request(headers={'X-Real-IP':'192.0.2.1'})[0],201)
        self.assertEqual(self.request(headers={'X-Real-IP':'192.0.2.1'})[0],429)
        self.assertEqual(self.request(headers={'X-Real-IP':'192.0.2.2'})[0],201)
    def test_parts_bomb_and_truncated_multipart(self):
        kind,body=multipart(); boundary=kind.split('boundary=')[1]
        bomb=f'--{boundary}\r\nContent-Disposition: form-data; name="x"\r\n\r\nx\r\n'.encode()*100
        self.assertEqual(self.request(pair=(kind,bomb))[0],400)
        self.assertEqual(self.request(pair=(kind,body[:-50]))[0],400); self.empty()
    def test_private_local_cli_export_no_overwrite(self):
        _,r,_=self.request(); identity=r['feedbackId']
        self.assertEqual(s.admin(self.data,'list')[0]['id'],identity)
        self.assertEqual(s.admin(self.data,'show',identity)['fields']['appName'],'示例应用')
        out=Path(self.temp.name)/'export.zip'; s.admin(self.data,'export',identity,out)
        self.assertEqual(out.stat().st_mode&0o777,0o600)
        with zipfile.ZipFile(out) as z: self.assertEqual(z.read('attachment-1.png'),PNG)
        with self.assertRaises(FileExistsError):s.admin(self.data,'export',identity,out)
    def test_concurrent_quota_is_atomic(self):
        self.store.quota_bytes=700*1024
        data=b'\xff\xd8\xff'+b'x'*(300*1024)+b'\xff\xd9'
        submissions=[s.parse_submission(*multipart([('a.jpg',data,'image/jpeg')])) for _ in range(2)]
        def save(value):
            try:return self.store.save(value)['ok']
            except s.FeedbackError as e:return e.status
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(save,submissions))
        self.assertIn(True,results); self.assertIn(507,results); self.assertEqual(len(self.rows()),1)
    def test_retry_after_response_loss(self):
        value=s.parse_submission(*multipart())
        a=self.store.save(value); b=self.store.save(value)
        self.assertEqual(a['feedbackId'],b['feedbackId']); self.assertTrue(b['duplicate'])

    def review_wish(self, identity, text='希望按钮更大一些', **kwargs):
        path=Path(self.temp.name)/('review-'+uuid.uuid4().hex+'.txt')
        path.write_text(text,encoding='utf-8')
        return s.wish_admin(self.data,'approve-wish',identity,public_text_file=path,**kwargs)

    def test_wish_text_only_is_private_and_durable(self):
        status,r,_=self.request(path='/api/wishes',files=[],fields={'description':'希望加大字体','email':'private@example.com'})
        self.assertEqual(status,201)
        self.assertEqual(set(r),{'ok','wishId','createdAt','status','duplicate'})
        self.assertEqual(r['status'],'pending_review')
        self.assertEqual(self.request('GET','/api/wishes')[1],{'ok':True,'items':[],'nextCursor':None})
        saved=s.wish_admin(self.data,'show-wish',r['wishId'])
        self.assertEqual(saved['fields']['description'],'希望加大字体')
        self.assertEqual(saved['fields']['email'],'private@example.com')
        self.assertEqual(saved['attachments'],[])
        self.assertEqual(s.wish_admin(self.data,'list-wishes')[0]['status'],'pending_review')
        self.assertEqual(self.rows(),[])

    def test_wish_required_description_consent_and_field_limits(self):
        for fields in [{'description':''},{'description':' \n '},{'description':'x'*5001},
                       {'consent':'false'},{'status':'completed'},{'public_description':'publish me'},
                       {'email':'bad email'},{'email':'x'*255},{'xadskipVersion':'x'*81}]:
            self.assertEqual(self.request(path='/api/wishes',files=[],fields=fields)[0],400)
        self.assertEqual(self.request('GET','/api/wishes')[1]['items'],[])
        self.assertEqual(s.wish_admin(self.data,'list-wishes'),[])

    def test_wish_media_limits_and_private_attachment_recovery(self):
        _,feedback,_=self.request()
        status,wish,_=self.request(path='/api/wishes',files=[('private-screen.png',PNG,'text/plain')])
        self.assertEqual(status,201)
        self.assertEqual(self.request(path='/api/wishes',files=[('a.png',PNG,'image/png')]*4)[0],400)
        self.assertEqual(self.request(path='/api/wishes',files=[('a.png',b'fake','image/png')])[0],400)
        self.store.recover()
        self.assertEqual({p.name for p in self.store.uploads.iterdir()},{feedback['feedbackId'],wish['wishId']})
        self.assertEqual((self.store.uploads/wish['wishId']/'attachment-1.png').read_bytes(),PNG)
        self.review_wish(wish['wishId'])
        public=self.request('GET','/api/wishes')[1]
        self.assertNotIn('private-screen',json.dumps(public))
        self.assertNotIn('attachments',json.dumps(public))
        for path in ['/api/wishes/'+wish['wishId'],'/attachments/'+wish['wishId'],
                     '/api/wishes/'+wish['wishId']+'/approve','/api/approve-wish']:
            self.assertEqual(self.request('GET',path)[0],404)
            self.assertEqual(self.request('POST',path)[0],404)

    def test_wish_idempotency_is_concurrent_and_isolated_from_feedback(self):
        identity=uuid.uuid4().hex
        pair=multipart(files=[],fields={'requestId':identity},kind='wish')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:self.request(path='/api/wishes',pair=pair),range(2)))
        self.assertEqual(sorted(r[0] for r in results),[200,201])
        self.assertEqual(len({r[1]['wishId'] for r in results}),1)
        self.assertEqual(len(s.wish_admin(self.data,'list-wishes')),1)
        status,feedback,_=self.request(fields={'requestId':identity})
        self.assertEqual(status,201)
        self.assertNotEqual(feedback['feedbackId'],results[0][1]['wishId'])
        status,r,_=self.request(path='/api/wishes',files=[],fields={'requestId':identity,'description':'another'})
        self.assertEqual((status,r['error']['code']),(409,'request_id_conflict'))

    def test_wish_approval_requires_explicit_public_text_and_status_is_local_only(self):
        _,r,_=self.request(path='/api/wishes',fields={'description':'private original 电话 12345','email':'secret@example.com'})
        identity=r['wishId']
        with self.assertRaises(ValueError):s.wish_admin(self.data,'approve-wish',identity)
        with self.assertRaises(ValueError):self.review_wish(identity,'  ')
        with self.assertRaises(ValueError):s.wish_admin(self.data,'set-wish-status',identity,status='completed')
        self.assertEqual(self.request('GET','/api/wishes')[1]['items'],[])
        approved=self.review_wish(identity,'请提供大字模式')
        self.assertNotEqual(approved['public']['id'],identity)
        note=Path(self.temp.name)/'developer.txt'; note.write_text('已在新版本完成',encoding='utf-8')
        result=s.wish_admin(self.data,'set-wish-status',identity,status='completed',developer_note_file=note)
        item=self.request('GET','/api/wishes')[1]['items'][0]
        self.assertEqual(item,result['public'])
        self.assertEqual(set(item),{'id','description','status','developerNote','createdAt','updatedAt'})
        self.assertEqual((item['description'],item['status'],item['developerNote']),('请提供大字模式','completed','已在新版本完成'))
        serialized=json.dumps(item)
        for private in [identity,'private original','12345','secret@example.com','attachments','fields']:
            self.assertNotIn(private,serialized)
        self.assertEqual(s.wish_admin(self.data,'show-wish',identity)['fields']['description'],'private original 电话 12345')

    def test_wish_review_edits_do_not_replace_original_or_reset_public_order(self):
        _,r,_=self.request(path='/api/wishes',files=[])
        first=self.review_wish(r['wishId'])['public']
        second=self.review_wish(r['wishId'],'已脱敏的新文字',status='planned')['public']
        self.assertEqual(first['id'],second['id'])
        self.assertEqual(first['createdAt'],second['createdAt'])
        self.assertLessEqual(first['updatedAt'],second['updatedAt'])
        self.assertEqual(second['status'],'planned')
        with self.assertRaises(ValueError):s.wish_admin(self.data,'set-wish-status',r['wishId'],status='invented')
        self.assertEqual(self.request('GET','/api/wishes')[1]['items'][0],second)

    def test_wish_public_pagination_is_bounded_and_cursor_validated(self):
        for index in range(3):
            _,r,_=self.request(path='/api/wishes',files=[])
            self.review_wish(r['wishId'],str(index))
        self.request(path='/api/wishes',files=[],fields={'description':'never public'})
        first=self.request('GET','/api/wishes?limit=2')[1]
        self.assertEqual(len(first['items']),2); self.assertTrue(first['nextCursor'])
        second=self.request('GET','/api/wishes?limit=2&cursor='+first['nextCursor'])[1]
        self.assertEqual(len(second['items']),1); self.assertIsNone(second['nextCursor'])
        self.assertEqual({x['description'] for x in first['items']+second['items']},{'0','1','2'})
        for query in ['limit=0','limit=51','limit=-1','limit=2&limit=3','private=true','cursor=',
                      'cursor=!!!','cursor='+base64.urlsafe_b64encode(b'[1,2]').decode(),'cursor='+'a'*300]:
            status,r,_=self.request('GET','/api/wishes?'+query)
            self.assertEqual(status,400,query); self.assertFalse(r['ok'])

    def test_wish_db_failure_cleanup_and_additive_old_database_upgrade(self):
        _,old,_=self.request()
        before=dict(self.rows()[0])
        with self.store.connect() as db:db.execute('DROP TABLE wishes')
        upgraded=s.Store(self.data,128*s.MIB,0)
        upgraded.recover()
        self.assertEqual(dict(self.rows()[0]),before)
        self.assertEqual((self.store.uploads/old['feedbackId']/'attachment-1.png').read_bytes(),PNG)
        with self.store.connect() as db:
            db.execute("CREATE TRIGGER injected_wish BEFORE INSERT ON wishes BEGIN SELECT RAISE(ABORT, 'test'); END")
        self.assertEqual(self.request(path='/api/wishes')[0],500)
        self.assertEqual(s.wish_admin(self.data,'list-wishes'),[])
        self.assertEqual([p.name for p in self.store.uploads.iterdir()],[old['feedbackId']])

    def test_wish_export_is_private_and_cli_status_round_trip(self):
        _,r,_=self.request(path='/api/wishes')
        identity=r['wishId']; out=Path(self.temp.name)/'wish.zip'
        s.wish_admin(self.data,'export-wish',identity,out)
        self.assertEqual(out.stat().st_mode&0o777,0o600)
        with zipfile.ZipFile(out) as z:
            self.assertEqual(z.read('attachment-1.png'),PNG)
            self.assertEqual(json.loads(z.read('wish.json'))['status'],'pending_review')
        with self.assertRaises(FileExistsError):s.wish_admin(self.data,'export-wish',identity,out)
        public=Path(self.temp.name)/'public.txt'; public.write_text('公开建议',encoding='utf-8')
        command=[sys.executable,str(Path(s.__file__)),'approve-wish',identity,'--data',str(self.data),'--public-text-file',str(public)]
        result=json.loads(subprocess.check_output(command))
        self.assertEqual(result['public']['status'],'pending')
        command=[sys.executable,str(Path(s.__file__)),'set-wish-status',identity,'--data',str(self.data),'--status','completed']
        self.assertEqual(json.loads(subprocess.check_output(command))['public']['status'],'completed')

    def test_wish_options_origin_and_read_rate_limit(self):
        status,_,headers=self.request('OPTIONS','/api/wishes')
        self.assertEqual(status,204); self.assertIn('GET',headers['Access-Control-Allow-Methods'])
        self.assertEqual(self.request(path='/api/wishes',files=[],headers={'Origin':'https://evil.example'})[0],403)
        self.http.read_limiter=s.RateLimiter(1,10)
        self.assertEqual(self.request('GET','/api/wishes')[0],200)
        self.assertEqual(self.request('GET','/api/wishes')[0],429)

if __name__=='__main__':unittest.main()
