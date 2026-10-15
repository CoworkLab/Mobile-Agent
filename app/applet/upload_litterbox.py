import http.client
import mimetypes
import os

def multipart_encode(fields, files):
    boundary = '----------ThIs_Is_THe_BoUnDaRy_$'
    body = []
    for key, value in fields.items():
        body.append(f'--{boundary}\r\n'.encode('utf-8'))
        body.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode('utf-8'))
        body.append(f'{value}\r\n'.encode('utf-8'))
    for key, filepath in files.items():
        filename = os.path.basename(filepath)
        content_type = mimetypes.guess_type(filepath)[0] or 'application/octet-stream'
        body.append(f'--{boundary}\r\n'.encode('utf-8'))
        body.append(f'Content-Disposition: form-data; name="{key}"; filename="{filename}"\r\n'.encode('utf-8'))
        body.append(f'Content-Type: {content_type}\r\n\r\n'.encode('utf-8'))
        with open(filepath, 'rb') as f:
            while True:
                chunk = f.read(8192)
                if not chunk:
                    break
                body.append(chunk)
        body.append(b'\r\n')
    body.append(f'--{boundary}--\r\n'.encode('utf-8'))
    return boundary, b''.join(body)

print("Preparing upload to litterbox.catbox.moe...")
fields = {'reqtype': 'bupload', 'time': '72h'}
files = {'fileToUpload': './.build-outputs/app-debug.apk'}
boundary, body = multipart_encode(fields, files)

conn = http.client.HTTPSConnection('litterbox.catbox.moe', timeout=300)
headers = {
    'Content-Type': f'multipart/form-data; boundary={boundary}',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
}
print("Sending request to litterbox...")
conn.request('POST', '/resources/api.php', body, headers)
res = conn.getresponse()
print("Status:", res.status)
data = res.read().decode('utf-8')
print("Response:", data)
