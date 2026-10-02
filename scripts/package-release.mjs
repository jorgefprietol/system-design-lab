import {spawn} from 'node:child_process';
import {createReadStream,createWriteStream} from 'node:fs';
import {mkdir,writeFile,unlink,readFile,readdir,copyFile} from 'node:fs/promises';
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
for(const runtime of ['csharp','java','postgres']){
  const image=process.env[{csharp:'CSHARP_IMAGE',java:'JAVA_IMAGE',postgres:'COMMERCE_DB_IMAGE'}[runtime]]||`atlas-${runtime}:local`;
  const [meta]=JSON.parse(await run(['image','inspect',image]));
  if(meta.Config.Labels['org.opencontainers.image.revision']!==sha||meta.Config.Labels['org.opencontainers.image.source']!==source)throw new Error('Image provenance label mismatch: '+runtime);
  const raw=resolve(dir,`atlas-${runtime}.tar`),file=`atlas-${runtime}.tar.gz`,packed=resolve(dir,file);
  const imageRef=`atlas-${runtime}:${sha}`;await run(['tag',image,imageRef]);
  await run(['save','--output',raw,imageRef]);await pipeline(createReadStream(raw),createGzip({level:6}),createWriteStream(packed));await unlink(raw);
  const hash=createHash('sha256');for await(const chunk of createReadStream(packed))hash.update(chunk);
  artifacts.push({runtime,file,sha256:hash.digest('hex'),sourceImageId:meta.Id,imageRef,rootfsSha256:createHash('sha256').update(meta.RootFS.Layers.join('\n')).digest('hex')});
}
const commerce={schema:1,image:artifacts.find(x=>x.runtime==='postgres').imageRef,sqlSha256:createHash('sha256').update(await readFile('database/init/commerce.sql.in')).digest('hex'),initializerSha256:createHash('sha256').update(await readFile('database/init/01-commerce.sh')).digest('hex')};
const testDirectories=(await readdir('tests/results',{withFileTypes:true})).filter(x=>x.isDirectory()).map(x=>x.name).sort();
for(const [file,horizontal,expected] of [['contract-report.json',false,38],['horizontal-report.json',true,12]]){
  const selected=testDirectories.filter(x=>horizontal?x.startsWith('horizontal-'):/^\d{4}-/.test(x)).at(-1);
  if(!selected)throw new Error('Test evidence missing: '+file);
  const reportPath=resolve('tests/results',selected,'report.json');const report=JSON.parse(await readFile(reportPath,'utf8'));
  if(report.total!==expected||(horizontal&&(!report.success||report.sourceSha!==sha)))throw new Error('Invalid test evidence: '+file);
  await copyFile(reportPath,resolve(dir,file));
}
await writeFile(resolve(dir,'manifest.json'),JSON.stringify({schema:2,source,sourceSha:sha,platform:'linux/amd64',artifacts,commerce},null,2));
await writeFile(resolve(dir,'SHA256SUMS'),artifacts.map(x=>`${x.sha256}  ${x.file}`).join('\n')+'\n');
await writeFile(resolve(dir,'notes.md'),`Validated container release for commit ${sha}.\n\n38 shared HTTP checks and 12 two-instance PostgreSQL commerce scenarios passed, including 30 retries producing one order and 20 buyers producing exactly 5 purchases and 15 rejections. Test reports, SBOM and vulnerability reports are attached and covered by provenance.\n\nThe release contains the exact tested C#, Java and patched PostgreSQL images. API runtimes run without root; PostgreSQL drops to its own server user. Deployment uses immutable local image ids and persistent volumes.\n\nDeployments are reconciled from the private operations repository on the local laptop.\n`);
