import {spawn} from 'node:child_process';
import {mkdir, writeFile, readFile} from 'node:fs/promises';
import {randomBytes, createHash} from 'node:crypto';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import net from 'node:net';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const runRoot = resolve(root, 'tests/results', 'horizontal-' + new Date().toISOString().replaceAll(':', '-'));
const taskProject = 'atlas-h-test-' + randomBytes(6).toString('hex');
const secretDir = resolve(runRoot, 'keys');
await mkdir(secretDir, {recursive:true,mode:0o700});
// Secrets cross the Docker API as files, avoiding dependence on host drive sharing.
for (const file of ['app-password','admin-password']) await writeFile(resolve(secretDir, file), randomBytes(32).toString('hex'), {mode:0o600});
function execute(args, input, options={}) {
  return new Promise((done, reject) => {
    const child = spawn('docker', args, {windowsHide:true,cwd:root,env:{...process.env,ATLAS_SECRET_DIR:secretDir},stdio:['pipe','pipe','pipe'],...options});
    let output = ''; child.stdout.on('data', data => output += data); child.stderr.on('data', data => output += data);
    child.on('error', reject); child.on('exit', code => code === 0 ? done(output) : reject(new Error(`docker exit ${code}: ${output}`)));
    child.stdin.end(input);
  });
}
async function freePort() {
  const socket = net.createServer(); await new Promise(done => socket.listen(0,'127.0.0.1',done));
  const port = socket.address().port; await new Promise(done => socket.close(done)); return port;
}
const csharpPort = await freePort(), javaPort = await freePort();
const bases = [`http://127.0.0.1:${csharpPort}`, `http://127.0.0.1:${javaPort}`];
const composePath = resolve(runRoot, 'compose.json');
const config = JSON.parse(await execute(['compose','--file',resolve(root,'compose.yaml'),'--file',resolve(root,'compose.commerce.yaml'),'config','--format','json']));
config.name = taskProject;
for (const [name, volume] of Object.entries(config.volumes)) { volume.name = `${taskProject}-${name}`; delete volume.external; }
for (const [name, network] of Object.entries(config.networks)) { network.name = `${taskProject}-${name}`; delete network.external; network.ipam = {config:[{subnet:process.env.ATLAS_TEST_SUBNET || '10.203.76.0/24'}]}; }
for (const [index, runtime] of ['csharp','java'].entries()) {
  delete config.services[runtime].build;
  config.services[runtime].ports = [{target:8080,published:String(index ? javaPort : csharpPort),host_ip:'127.0.0.1',protocol:'tcp'}];
  config.services[runtime].environment.LAB_INSTANCE = `test-${runtime}`;
  config.services[runtime].environment.LAB_COMMERCE_PEER_URL = bases[1-index] + '/commerce';
}
await writeFile(composePath, JSON.stringify(config, null, 2));
const compose = args => execute(['compose','--file',composePath,'--project-name',taskProject,...args]);
const checks = []; let failure, databaseContainer, concurrency, postgresUid;
async function check(name, test) { await test(); checks.push(name); console.log('PASS horizontal: ' + name); }
async function request(index, route, body, expected=200) {
  const response = await fetch(bases[index] + '/api/commerce/' + route, {signal:AbortSignal.timeout(15000),...(body === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});
  const result = await response.json(); assert.equal(response.status, expected, JSON.stringify(result)); return result;
}
async function ready() {
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    try { for (const index of [0,1]) await request(index,'health'); return; } catch { await new Promise(done => setTimeout(done,500)); }
  }
  throw new Error('Horizontal readiness timeout');
}
const state = index => request(index,'state');
const sql = statement => execute(['exec','--interactive',databaseContainer,'psql','--username','atlas_admin','--dbname','atlas_commerce','--set','ON_ERROR_STOP=1','--tuples-only','--no-align'], statement);
try {
  for (const role of ['app','admin']) {
    const volume = config.volumes[`commerce-${role}-secrets`].name;
    await execute(['volume','create',volume]);
    const helper = (await execute(['create','--user','0:0','--network','none','--read-only','--cap-drop','ALL','--cap-add','CHOWN','--security-opt','no-new-privileges:true','--volume',`${volume}:/secrets`,'--entrypoint','sh',config.services['commerce-db'].image,'-c',`test -f /secrets/${role}-password && chown 0:0 /secrets/${role}-password && chmod 0444 /secrets/${role}-password && test "$(wc -c < /secrets/${role}-password)" -eq 64`])).trim();
    assert.match(helper,/^[a-f0-9]{64}$/);
    try {
      await execute(['cp',resolve(secretDir,`${role}-password`),`${helper}:/secrets/${role}-password`]);
      await execute(['start','--attach',helper]);
    } finally { await execute(['rm','--force',helper]); }
  }
  await compose(['up','--detach','--no-build','--wait','--wait-timeout','300']);
  databaseContainer = (await compose(['ps','--quiet','commerce-db'])).trim();
  assert.match(databaseContainer, /^[a-f0-9]{12,64}$/);
  await ready();
  await check('two distinct C# and Java processes share schema version 1', async () => {
    const a = await request(0,'health'), b = await request(1,'health');
    assert.equal(a.implementation,'csharp'); assert.equal(b.implementation,'java'); assert.notEqual(a.instance,b.instance);
    assert.equal(a.schemaVersion,1); assert.equal(b.schemaVersion,1); assert.equal(a.revision,b.revision);
    if(process.env.GITHUB_SHA) assert.equal(a.revision,process.env.GITHUB_SHA);
    const expectedBootstrap = createHash('sha256').update(await readFile(resolve(root,'database/init/commerce.sql.in'))).digest('hex');
    assert.equal(a.bootstrapSha256,expectedBootstrap); assert.equal(b.bootstrapSha256,expectedBootstrap);
    postgresUid = (await execute(['exec',databaseContainer,'sh','-c',"awk '/^Uid:/ {print $2}' /proc/1/status"])).trim();
    assert.match(postgresUid,/^[0-9]+$/); assert.notEqual(postgresUid,'0');
    for (const runtime of ['csharp','java']) {
      const container = (await compose(['ps','--quiet',runtime])).trim();
      await execute(['exec',container,'sh','-c','test -r /run/secrets/app/app-password && test ! -e /run/secrets/admin/admin-password']);
      const [metadata] = JSON.parse(await execute(['inspect',container]));
      assert.equal(metadata.Mounts.find(mount=>mount.Destination==='/run/secrets/app').RW,false);
    }
    assert.deepEqual(await state(0), await state(1));
  });
  await check('both instances serve the shared storefront and assets', async () => {
    for (const base of bases) for (const path of ['/commerce','/commerce.js','/style.css']) {
      const response = await fetch(base + path); assert.equal(response.status,200); assert.ok((await response.text()).length > 100);
    }
  });
  await check('malformed and invalid requests cannot consume inventory', async () => {
    const before = await state(0);
    for (const index of [0,1]) {
      for (const quantity of [0,1.5,101]) await request(index,'orders',{key:'invalid',product:'book',quantity},422);
      await request(index,'orders',{key:'bad\u0000key',product:'book',quantity:1},422);
      await request(index,'orders',{key:'unknown',product:'absent',quantity:1},404);
      const response = await fetch(bases[index] + '/api/commerce/orders',{method:'POST',body:'{broken'}); assert.equal(response.status,400);
    }
    assert.deepEqual(await state(1),before);
  });
  const identical = {key:'same-key',product:'keyboard',quantity:2}; let receipt;
  await check('30 concurrent cross-instance retries create one durable order', async () => {
    const results = await Promise.all(Array.from({length:30},(_,i) => request(i % 2,'orders',identical)));
    receipt = results[0]; for (const result of results) assert.deepEqual(result,receipt);
    const after = await state(1); assert.equal(after.orderCount,1); assert.equal(after.unitsSold,2);
    assert.equal(after.products.find(product => product.id === 'keyboard').stock,6);
  });
  await check('changed payload with a committed key conflicts across nodes', async () => {
    const before = await state(0);
    assert.equal((await request(1,'orders',{...identical,quantity:1},409)).error,'idempotency_conflict');
    assert.equal((await request(0,'orders',{...identical,product:'book'},409)).error,'idempotency_conflict');
    assert.deepEqual(await state(1),before);
  });
  await check('20 buyers on two instances sell exactly 5 units and reject 15', async () => {
    const results = await Promise.all(Array.from({length:20},async (_,i) => {
      const response = await fetch(bases[i % 2] + '/api/commerce/orders',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key:'buyer-'+i,product:'book',quantity:1}),signal:AbortSignal.timeout(15000)});
      return {status:response.status,body:await response.json()};
    }));
    concurrency = {buyers:20,accepted:results.filter(x=>x.status===200).length,rejected:results.filter(x=>x.status===409).length};
    assert.deepEqual(concurrency,{buyers:20,accepted:5,rejected:15});
    assert.ok(results.filter(x=>x.status===409).every(x=>x.body.error==='out_of_stock'));
    const after = await state(0); assert.equal(after.products.find(p=>p.id==='book').stock,0); assert.equal(after.orderCount,6); assert.equal(after.unitsSold,7);
  });
  await check('read snapshots and successful receipts agree between implementations', async () => {
    assert.deepEqual(await state(0),await state(1)); assert.deepEqual(await request(1,'orders',identical),receipt);
  });
  await check('rejected orders roll back the idempotency reservation and inventory', async () => {
    await request(0,'orders',{key:'recover-after-failure',product:'book',quantity:1},409);
    assert.equal((await sql("SELECT count(*) FROM shop.idempotency_keys WHERE key='recover-after-failure';")).trim(),'0');
    const recovered = await request(1,'orders',{key:'recover-after-failure',product:'keyboard',quantity:1});
    assert.equal(recovered.quantity,1); assert.equal(recovered.totalCents,6500);
  });
  await check('the API database role cannot update inventory directly', async () => {
    await assert.rejects(execute(['exec',databaseContainer,'sh','-c','PGPASSWORD="$(cat /run/secrets/app/app-password)" psql -h 127.0.0.1 -U atlas_app -d atlas_commerce -v ON_ERROR_STOP=1 -c "UPDATE shop.products SET stock=999"']), /permission denied/);
  });
  await check('one API can continue ordering while the other instance is stopped', async () => {
    await compose(['stop','--timeout','5','csharp']);
    await request(1,'orders',{key:'surviving-node',product:'keyboard',quantity:1});
    await compose(['start','csharp']); await ready();
    assert.deepEqual(await state(0),await state(1));
  });
  await check('restarting both APIs preserves stock and cross-node idempotency', async () => {
    const before = await state(0); await compose(['restart','--timeout','5','csharp','java']); await ready();
    assert.deepEqual(await state(0),before); assert.deepEqual(await state(1),before);
    assert.deepEqual(await request(0,'orders',identical),receipt); assert.deepEqual(await state(1),before);
  });
  await check('database outage returns 503 and a restart restores durable state', async () => {
    const before = await state(0); await compose(['stop','--timeout','15','commerce-db']);
    for (const index of [0,1]) {
      assert.equal((await request(index,'health',undefined,503)).error,'commerce_unavailable');
      assert.equal((await request(index,'orders',{key:'db-outage',product:'keyboard',quantity:1},503)).error,'commerce_unavailable');
    }
    await compose(['start','commerce-db']); await ready();
    assert.deepEqual(await state(1),before); assert.deepEqual(await request(1,'orders',identical),receipt);
    const recovered = await request(0,'orders',{key:'db-outage',product:'keyboard',quantity:1});
    assert.deepEqual(await request(1,'orders',{key:'db-outage',product:'keyboard',quantity:1}),recovered);
  });
  await mkdir(resolve(root,'release'),{recursive:true});
  await writeFile(resolve(root,'release/postgres-image.txt'),config.services['commerce-db'].image + '\n');
} catch (error) { failure = String(error.stack || error); console.error(error); process.exitCode = 1; }
finally {
  try { await writeFile(resolve(runRoot,'containers.log'),await compose(['logs','--no-color'])); } catch { /* preserve the original failure */ }
  await writeFile(resolve(runRoot,'report.json'),JSON.stringify({date:new Date().toISOString(),sourceSha:process.env.GITHUB_SHA || null,postgresImage:config.services['commerce-db'].image,postgresUid,total:checks.length,success:!failure,checks,concurrency,failure},null,2));
  try { await compose(['down','--volumes','--remove-orphans','--timeout','5']); } catch (error) { console.error(error); process.exitCode = 1; }
}
console.log(`${checks.length} horizontal checks completed. Report: ${runRoot}`);
