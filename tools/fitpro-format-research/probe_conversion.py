from pathlib import Path
from PIL import Image
import urllib.request, urllib.parse, json,re,io,concurrent.futures
root=Path(__file__).parent
text=(root.parent/'jadx_out/sources/xfkj/fitpro/utils/Constant.java').read_text()
token=urllib.parse.unquote(re.search(r'\bTOKEN\s*=\s*"([^"]+)"',text)[1])
def convert(name,color,font):
 im=Image.new('RGB',(240,286),color); out=io.BytesIO();im.save(out,format='BMP');bmp=out.getvalue();boundary='FitproProbeBoundary7076'
 data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.bmp"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+bmp+f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="font"\r\n\r\n{font}\r\n--{boundary}--\r\n'.encode())
 req=urllib.request.Request('https://tomato.gulaike.com/api/v1/convert/8bit',data=data,headers={'Authorization':token,'Content-Type':'multipart/form-data; boundary='+boundary})
 with urllib.request.urlopen(req,timeout=45) as r: j=json.load(r)
 if not j.get('success'):return name,j
 req=urllib.request.Request(j['data'],headers={'Authorization':token})
 with urllib.request.urlopen(req,timeout=30) as r: b=r.read()
 (root/(name+'.bin')).write_bytes(b);return name,len(b),b[:24].hex()
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 for result in pool.map(lambda a:convert(*a), [('black-empty','black','empty.bin'),('red-empty','red','empty.bin'),('black-font','black','font.bin')]):print(result,flush=True)
