import '@angular/compiler';
import {test,mock} from 'node:test';
import assert from 'node:assert/strict';
import type {OperationState,Report} from './model';
Object.defineProperty(globalThis,'location',{configurable:true,value:{hash:'#token=workflow-test'}});
const {ReviewComponent}=await import('./review.component');
const {AppComponent}=await import('./app.component');
const root='C:/repos/ordine-migration';
const file={beforePath:'deploy/Blue_42/values.yaml',afterPath:'deploy/Blue_42/values.yaml',status:'MODIFIED' as const,before:'app: old',after:'app: new',recipes:[],diff:'-old\n+new'};
const report:Report={root,schemaVersion:1,recipe:'it.Migration',resolvedRecipe:'',createdAt:'',files:[file],diagnostics:[],git:{head:'a'.repeat(40),branch:'migration/configurazioni',status:''},metrics:{discoveredFiles:1,totalMillis:1}};
function view(stage:OperationState['stage']){
 const component=new ReviewComponent();component.jobId='job-server-owned';component.reports=[report];component.operationsLoaded.set(true);
 component.operationStates.set([{root,stage,changedFiles:1,branch:report.git!.branch}]);component.remoteOptions.set({[root]:['origin']});
 return component;
}

test('la GUI invia la review solo dopo tutti i controlli e una conferma esplicita',async t=>{
 t.after(()=>mock.restoreAll());
 const requests:{path:string;body:unknown}[]=[];
 mock.method(globalThis,'fetch',async(path:string,options:RequestInit)=>{
  requests.push({path,body:JSON.parse(options.body as string)});
  return new Response(JSON.stringify({root,stage:'REVIEWED',changedFiles:1,branch:report.git!.branch}));
 });
 const component=view('PREVIEW');
 assert.equal(component.canOperate('review',report),false);
 assert.equal(component.canOperate('apply',report),false);
 component.mark(report,file);assert.equal(component.canOperate('review',report),true);
 await component.performConfirmed();assert.equal(requests.length,0);
 component.confirmation.set({kind:'review',root});await component.performConfirmed();
 assert.deepEqual(requests,[{path:'/api/jobs/job-server-owned/review',body:{root,confirmed:true,reviewedPaths:['deploy/Blue_42/values.yaml']}}]);
 assert.equal(component.operation(report)?.stage,'REVIEWED');
 assert.equal(component.canOperate('apply',report),true);
 component.mark(report,file);assert.equal(component.isReviewed(report),true);
});

test('un commit riuscito con risposta persa viene recuperato senza ripetere commit o push',async t=>{
 t.after(()=>mock.restoreAll());
 const paths:string[]=[];const commit='b'.repeat(40);
 mock.method(globalThis,'fetch',async(path:string)=>{
  paths.push(path);
  if(path.endsWith('/commit'))throw new TypeError('Risposta interrotta');
  return new Response(JSON.stringify({operations:[{root,stage:'COMMITTED',changedFiles:1,branch:report.git!.branch,commit}]}));
 });
 const component=view('APPLIED');component.confirmation.set({kind:'commit',root});
 await component.performConfirmed();
 assert.deepEqual(paths,['/api/jobs/job-server-owned/commit','/api/jobs/job-server-owned/operations']);
 assert.equal(component.operation(report)?.commit,commit);
 assert.equal(component.operationError(),'');assert.ok(component.operationMessage().includes(commit));
 assert.equal(component.canOperate('commit',report),false);assert.equal(component.confirmation(),null);
});

test('se non si conosce esito della scrittura tutte le azioni restano bloccate',async t=>{
 t.after(()=>mock.restoreAll());
 mock.method(globalThis,'fetch',async()=>{throw new TypeError('Servizio non raggiungibile');});
 const component=view('REVIEWED');component.confirmation.set({kind:'apply',root});
 await component.performConfirmed();
 assert.equal(component.uncertainMutation(),true);assert.equal(component.configurationLocked(),true);
 for(const kind of ['review','apply','commit','push'] as const)assert.equal(component.canOperate(kind,report),false);
 const directory=view('APPLIED');directory.reports=[{...report,git:null}];
 assert.equal(directory.configurationLocked(),false);assert.equal(directory.canOperate('commit',directory.reports[0]),false);
});

test('un commit ancora da completare impedisce reset e ricaricamento involontari',t=>{
 t.after(()=>mock.restoreAll());
 mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({toolVersion:'test',engineVersion:'test',baseDirectory:'C:/repos',exampleRoot:'',exampleRules:'',exampleRecipe:''})));
 const component=new AppComponent();component.stage.set('review');component.pendingCommit.set(true);
 component.newSession();assert.equal(component.stage(),'review');
 let prevented=false;
 const event={preventDefault(){prevented=true;},returnValue:'original'} as unknown as BeforeUnloadEvent;
 component.protectPendingOperation(event);assert.equal(prevented,true);assert.equal(event.returnValue,'');
 component.pendingCommit.set(false);component.newSession();assert.equal(component.stage(),'welcome');
});
test('una risposta persa non sblocca la configurazione se il motore sta ancora scrivendo',async t=>{
 t.after(()=>mock.restoreAll());
 mock.method(globalThis,'fetch',async(path:string)=>{
  if(path.endsWith('/apply'))throw new TypeError('Connessione interrotta');
  return new Response(JSON.stringify({busy:true,operations:[{root,stage:'REVIEWED',changedFiles:1,branch:report.git!.branch}]}));
 });
 const component=view('REVIEWED');component.confirmation.set({kind:'apply',root});
 await component.performConfirmed();
 assert.equal(component.uncertainMutation(),true);assert.equal(component.configurationLocked(),true);
 assert.equal(component.canOperate('apply',report),false);
});