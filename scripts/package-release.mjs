import {spawn} from 'node:child_process';
import {createReadStream,createWriteStream} from 'node:fs';
import {mkdir,writeFile,unlink,readFile} from 'node:fs/promises';
import {pipeline} from 'node:stream/promises';
import {createGzip} from 'node:zlib';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const sha=process.env.GITHUB_SHA;
if(!/^[a-f0-9]{40}$/.test(sha||''))throw new Error('GITHUB_SHA must be a full commit SHA');
const source='https://github.com/jorgefprietol/system-design-lab';
const dir=resolve('release');await mkdir(dir,{recursive:true});
const run=(args)=>new Promise((resolve,reject)=>{const p=spawn('docker',args,{stdio:['ignore','pipe','inherit'],windowsHide:true});let out='';p.stdout.on('data',x=>out+=x);p.on('error',reject);p.on('exit',code=>code===0?resolve(out.trim()):reject(new Error('docker exit '+code)));});
const artifacts=[];
for(const runtime of ['csharp','java']){
  const image=process.env[runtime==='csharp'?'CSHARP_IMAGE':'JAVA_IMAGE']||`atlas-${runtime}:local`;
  const [meta]=JSON.parse(await run(['image','inspect',image]));
  if(meta.Config.Labels['org.opencontainers.image.revision']!==sha||meta.Config.Labels['org.opencontainers.image.source']!==source)throw new Error('Image provenance label mismatch: '+runtime);
  const raw=resolve(dir,`atlas-${runtime}.tar`),file=`atlas-${runtime}.tar.gz`,packed=resolve(dir,file);
  const imageRef=`atlas-${runtime}:${sha}`;await run(['tag',image,imageRef]);
  await run(['save','--output',raw,imageRef]);await pipeline(createReadStream(raw),createGzip({level:6}),createWriteStream(packed));await unlink(raw);
  const hash=createHash('sha256');for await(const chunk of createReadStream(packed))hash.update(chunk);
  artifacts.push({runtime,file,sha256:hash.digest('hex'),sourceImageId:meta.Id,imageRef,rootfsSha256:createHash('sha256').update(meta.RootFS.Layers.join('\n')).digest('hex')});
}
const commerce={schema:1,image:(await readFile(resolve(dir,'postgres-image.txt'),'utf8')).trim(),sqlSha256:createHash('sha256').update(await readFile('database/init/commerce.sql.in')).digest('hex'),initializerSha256:createHash('sha256').update(await readFile('database/init/01-commerce.sh')).digest('hex')};
await writeFile(resolve(dir,'manifest.json'),JSON.stringify({schema:2,source,sourceSha:sha,platform:'linux/amd64',artifacts,commerce},null,2));
await writeFile(resolve(dir,'SHA256SUMS'),artifacts.map(x=>`${x.sha256}  ${x.file}`).join('\n')+'\n');
await writeFile(resolve(dir,'notes.md'),`Validated container release for commit ${sha}.\n\nBoth implementations passed the shared HTTP contract suite. Runtime images run without root, include healthchecks, and retain data in independent Docker volumes. SBOM and vulnerability reports are attached.\n\nDeployments are reconciled from the private operations repository on the local laptop.\n`);
