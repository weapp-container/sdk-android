import {createHash} from 'node:crypto';
import {mkdirSync,readFileSync,existsSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';

// Probe compiler inputs only. Production Gradle resolves the SDK's ordinary Maven dependency.
const inputs=[
 {file:'okhttp-4.12.0.jar',url:'https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar',sha256:'b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0'},
 {file:'okio-jvm-3.6.0.jar',url:'https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar',sha256:'67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412'},
];
export async function networkDependencies(directory){
 mkdirSync(directory,{recursive:true});
 for(const input of inputs){const path=join(directory,input.file);if(!existsSync(path)){const response=await fetch(input.url,{redirect:'error',signal:AbortSignal.timeout(30000)});if(!response.ok)throw Error(`Dependency HTTP ${response.status}`);const bytes=Buffer.from(await response.arrayBuffer());if(bytes.length>2097152)throw Error('Dependency exceeds probe input limit');writeFileSync(path,bytes);}if(createHash('sha256').update(readFileSync(path)).digest('hex')!==input.sha256)throw Error(`Probe dependency mismatch: ${input.file}`);}
 return inputs.map(input=>({...input,path:join(directory,input.file)}));
}
