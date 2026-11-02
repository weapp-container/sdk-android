import {createServer} from 'node:http';
import {createHash} from 'node:crypto';

/** Authored loopback transport peer. It supplies bytes, not native API responses. */
export function makeNetworkFixtureServer(){
 const sockets=new Set();let slow=0;
 const server=createServer(async(request,response)=>{
  const port=server.address()?.port;if(request.headers.host!==`127.0.0.1:${port}`){response.writeHead(403).end();return;}
  const path=new URL(request.url,'http://127.0.0.1').pathname;
  const json=value=>{response.writeHead(200,{'content-type':'application/json','x-native-probe':'actual-http'});response.end(JSON.stringify(value));};
  if(path==='/json')return json({query:new URL(request.url,'http://127.0.0.1').searchParams.get('sample')});
  if(path==='/download'){response.writeHead(200,{'content-type':'application/octet-stream'});response.end('native-network-download\n');return;}
  if(path==='/upload'){
   const chunks=[];let size=0;for await(const chunk of request){size+=chunk.length;if(size>2097152){response.writeHead(413).end();request.destroy();return;}chunks.push(chunk);}
   return json({multipart:(request.headers['content-type']??'').startsWith('multipart/form-data;'),containsFile:Buffer.concat(chunks).includes(Buffer.from('native-upload-body')),bytes:size});
  }
  if(path==='/stats')return json({sockets:sockets.size,slowRequests:slow});
  if(path==='/slow'){slow++;const timer=setTimeout(()=>json({late:true}),10000);response.once('close',()=>{clearTimeout(timer);slow--;});return;}
  response.writeHead(404).end();
 });
 server.on('upgrade',(request,socket)=>{
  if(request.headers.host!==`127.0.0.1:${server.address().port}`||request.url!=='/socket'||request.headers['sec-websocket-version']!=='13'||typeof request.headers['sec-websocket-key']!=='string'){socket.destroy();return;}
  const accept=createHash('sha1').update(request.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);sockets.add(socket);socket.once('close',()=>sockets.delete(socket));socket.once('end',()=>socket.end());socket.on('error',()=>{});socket.setTimeout(20000,()=>socket.destroy());let pending=Buffer.alloc(0);
  const frame=(opcode,payload)=>{const header=payload.length<126?Buffer.from([0x80|opcode,payload.length]):Buffer.from([0x80|opcode,126,payload.length>>8,payload.length&255]);socket.write(Buffer.concat([header,payload]));};
  socket.on('data',chunk=>{pending=Buffer.concat([pending,chunk]);while(pending.length>=2){const opcode=pending[0]&15,final=pending[0]&128,masked=pending[1]&128;let length=pending[1]&127,offset=2;if(!final||!masked||length===127){socket.destroy();return;}if(length===126){if(pending.length<4)return;length=pending.readUInt16BE(2);offset=4;}if(length>65535||pending.length<offset+4+length)return;const mask=pending.subarray(offset,offset+4),data=Buffer.from(pending.subarray(offset+4,offset+4+length));for(let i=0;i<data.length;i++)data[i]^=mask[i%4];pending=pending.subarray(offset+4+length);if(opcode===8){frame(8,data);socket.end();return;}if(opcode===9)frame(10,data);else if(opcode===1||opcode===2)frame(opcode,data);else{socket.destroy();return;}}});
 });
 server.on('close',()=>{for(const socket of sockets)socket.destroy();});
 return server;
}
