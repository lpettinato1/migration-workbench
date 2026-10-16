import {ChangeDetectionStrategy, Component, HostListener, computed, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {EditorComponent} from './editor.component';
import {ReviewComponent} from './review.component';
import {GitPreparationComponent} from './git-preparation.component';
import {api, download} from './api';
import {descriptorIndex,parametersMap,parseRecipes,updateStep} from './model';
import type {Bootstrap, Descriptor, Job, RootInfo, Step} from './model';

@Component({selector:'mw-app',standalone:true,imports:[FormsModule,EditorComponent,ReviewComponent,GitPreparationComponent],changeDetection:ChangeDetectionStrategy.OnPush,templateUrl:'./app.component.html'})
export class AppComponent {
  readonly bootstrap=signal<Bootstrap|null>(null);
  readonly stage=signal<'welcome'|'configure'|'review'>('welcome');
  readonly pane=signal<'repositories'|'recipes'>('repositories');
  readonly recipeTab=signal<'guided'|'yaml'>('guided');
  readonly roots=signal<RootInfo[]>([]);
  readonly rootPath=signal('');
  readonly rulesPath=signal('');
  readonly yaml=signal('');
  readonly recipe=signal('');
  readonly parameters=signal<{name:string;value:string}[]>([]);
  readonly parsed=computed(()=>parseRecipes(this.yaml()));
  readonly steps=computed(()=>this.parsed().steps(this.recipe()));
  readonly selectedStepId=signal('');
  readonly selectedStep=computed(()=>this.steps().find(step=>step.id===this.selectedStepId()) || this.steps()[0]);
  readonly catalog=signal<Descriptor|null>(null);
  readonly catalogError=signal('');
  readonly selectedDescriptor=computed(()=>descriptorIndex(this.catalog()).get(this.selectedStep()?.name||''));
  readonly error=signal('');
  readonly notice=signal('');
  readonly busy=signal(false);
  readonly loading=signal(false);
  readonly job=signal<Job|null>(null);
  readonly operationBusy=signal(false);
  readonly pendingCommit=signal(false);
  readonly configurationLocked=computed(()=>this.operationBusy()||this.pendingCommit());
  private controller?:AbortController;
  private catalogTimer?:ReturnType<typeof setTimeout>;
  private revision=0;
  private sessionRevision=0;
  private loadingOperation=0;

  @HostListener('window:beforeunload',['$event'])
  protectPendingOperation(event:BeforeUnloadEvent){
    if(!this.configurationLocked())return;
    event.preventDefault();event.returnValue='';
  }
  constructor(){void this.initialize();}
  private async initialize(){try{this.bootstrap.set(await api<Bootstrap>('bootstrap'));}catch(error){this.fail(error);}}
  begin(){if(this.configurationLocked())return;this.stage.set('configure');this.pane.set('repositories');this.error.set('');}
  navigate(pane:'repositories'|'recipes'){if(this.configurationLocked())return;this.stage.set('configure');this.pane.set(pane);}
  newSession(){if(this.configurationLocked())return;this.sessionRevision++;this.loadingOperation++;this.loading.set(false);this.invalidate();if(this.catalogTimer)clearTimeout(this.catalogTimer);this.roots.set([]);this.yaml.set('');this.recipe.set('');this.rootPath.set('');this.rulesPath.set('');this.parameters.set([]);this.catalog.set(null);this.catalogError.set('');this.selectedStepId.set('');this.stage.set('welcome');this.error.set('');this.notice.set('');}
  private fail(error:unknown){if(!(error instanceof DOMException && error.name==='AbortError')) this.error.set(error instanceof Error ? error.message : 'Operazione non riuscita.');}
  invalidate(){this.revision++;this.controller?.abort();this.controller=undefined;this.busy.set(false);if(this.job()){this.job.set(null);this.notice.set('Configurazione aggiornata: esegui una nuova anteprima per rivedere le modifiche.');}}
  async addRoot(path=this.rootPath()){
    if(this.configurationLocked()||!path.trim())return false;
    const revision=this.revision, operation=++this.loadingOperation;
    this.loading.set(true);this.error.set('');
    try{
      const root=await api<Omit<RootInfo,'group'>>('inspect',{root:path.trim()});
      if(revision!==this.revision||this.configurationLocked())return false;
      if(this.roots().some(item=>item.root.toLocaleLowerCase()===root.root.toLocaleLowerCase())) {this.notice.set('Questa cartella è già nell’elenco.');return true;}
      this.invalidate();this.roots.update(items=>[...items,{...root,group:''}]);this.rootPath.set('');
      return true;
    }catch(error){if(revision===this.revision)this.fail(error);return false;}finally{if(operation===this.loadingOperation)this.loading.set(false);}
  }
  removeRoot(root:string){if(this.configurationLocked())return;this.invalidate();this.roots.update(items=>items.filter(item=>item.root!==root));}
  changeGroup(root:string,group:string){if(this.configurationLocked())return;this.invalidate();this.roots.update(items=>items.map(item=>item.root===root?{...item,group}:item));}
  preparedRoot(previous:string,replacement:RootInfo){this.invalidate();this.roots.update(items=>items.map(item=>item.root===previous?replacement:item));this.notice.set('Branch creato. La migrazione userà la nuova cartella di lavoro; genera un’anteprima prima di applicare le modifiche.');}
  async pick(kind:'directory'|'rules'){
    if(this.configurationLocked())return;
    const revision=this.revision;
    this.error.set('');
    try{const result=await api<{path:string|null}>('pick',{kind});if(revision!==this.revision||this.configurationLocked()||!result.path)return;if(kind==='directory')await this.addRoot(result.path);else{this.rulesPath.set(result.path);await this.loadRules();}}
    catch(error){if(revision===this.revision)this.fail(error);}
  }
  async loadRules(){
    const path=this.rulesPath().trim();if(this.configurationLocked()||!path)return false;
    const revision=this.revision, operation=++this.loadingOperation;
    this.loading.set(true);this.error.set('');
    try{const result=await api<{yaml:string;names:string[]}>('rules',{path});if(revision!==this.revision||this.configurationLocked()||path!==this.rulesPath().trim())return false;this.setYaml(result.yaml);this.recipe.set(result.names[0]||'');this.queueCatalog();this.recipeTab.set('guided');return true;}
    catch(error){if(revision===this.revision)this.fail(error);return false;}finally{if(operation===this.loadingOperation)this.loading.set(false);}
  }
  async example(){
    if(this.configurationLocked())return;
    const info=this.bootstrap();if(!info)return;
    const session=this.sessionRevision;
    this.begin();this.invalidate();this.roots.set([]);this.parameters.set([]);this.rulesPath.set(info.exampleRules);
    if(!await this.addRoot(info.exampleRoot)||session!==this.sessionRevision)return;
    if(!await this.loadRules()||session!==this.sessionRevision)return;
    if(this.parsed().names.includes(info.exampleRecipe))this.setRecipe(info.exampleRecipe);
    this.pane.set('recipes');
  }
  setYaml(value:string){if(this.configurationLocked())return;this.invalidate();this.yaml.set(value);const names=this.parsed().names;if(!names.includes(this.recipe()))this.recipe.set(names[0]||'');this.queueCatalog();}
  setRecipe(value:string){if(this.configurationLocked())return;this.invalidate();this.recipe.set(value);this.selectedStepId.set('');this.queueCatalog();}
  addParameter(){if(this.configurationLocked())return;this.invalidate();this.parameters.update(rows=>[...rows,{name:'',value:''}]);}
  setParameter(index:number,key:'name'|'value',value:string){if(this.configurationLocked())return;this.invalidate();this.parameters.update(rows=>rows.map((row,i)=>i===index?{...row,[key]:value}:row));this.queueCatalog();}
  removeParameter(index:number){if(this.configurationLocked())return;this.invalidate();this.parameters.update(rows=>rows.filter((_,i)=>i!==index));this.queueCatalog();}
  private queueCatalog(){if(this.catalogTimer)clearTimeout(this.catalogTimer);this.catalog.set(null);this.catalogError.set('');this.catalogTimer=setTimeout(()=>void this.refreshCatalog(),600);}
  async refreshCatalog(){
    if(!this.recipe()||this.parsed().error)return;
    const revision=this.revision;
    try{const descriptor=await api<Descriptor>('catalog',{yaml:this.yaml(),recipe:this.recipe(),parameters:parametersMap(this.parameters())});if(revision===this.revision)this.catalog.set(descriptor);}
    catch(error){if(revision===this.revision)this.catalogError.set(error instanceof Error?error.message:'Documentazione non disponibile.');}
  }
  optionKeys(step:Step){return [...new Set([...Object.keys(step.values),...(this.selectedDescriptor()?.options||[]).map(item=>item.name)])];}
  optionDescription(key:string){return this.selectedDescriptor()?.options?.find(option=>option.name===key)?.description||'Opzione definita nella ricetta YAML.';}
  optionLabel(key:string){return this.selectedDescriptor()?.options?.find(option=>option.name===key)?.displayName||key;}
  optionType(key:string){return this.selectedDescriptor()?.options?.find(option=>option.name===key)?.type||'';}
  scalar(value:unknown){return value===null||value===undefined?'':typeof value==='object'?JSON.stringify(value):String(value);}
  editable(value:unknown){return value===null||value===undefined||typeof value!=='object';}
  changeOption(step:Step,key:string,value:string){
    try{
      const old=step.values[key], type=this.optionType(key);
      let next:unknown=value;
      if(typeof old==='boolean'||type==='boolean'||type==='Boolean') {if(value!=='true'&&value!=='false')throw new Error('Per un valore booleano usa true oppure false.');next=value==='true';}
      else if(typeof old==='number'||['int','Integer','long','Long'].includes(type)){if(!value.trim()||!Number.isFinite(Number(value)))throw new Error('Inserisci un numero valido.');next=Number(value);}
      this.setYaml(updateStep(this.yaml(),step,key,next));this.error.set('');
    }catch(error){this.fail(error);}
  }
  saveRules(){download('migration-recipes.yml',this.yaml(),'application/yaml');}
  async preview(){
    if(this.configurationLocked())return;
    this.invalidate();this.error.set('');this.notice.set('');
    if(!this.roots().length){this.error.set('Aggiungi almeno una cartella da elaborare.');return;}
    if(!this.recipe()||this.parsed().error){this.error.set('Scegli una ricetta e correggi gli errori YAML prima di proseguire.');return;}
    const controller=new AbortController();this.controller=controller;
    const revision=this.revision;this.busy.set(true);
    try{
      const body={roots:this.roots().map(item=>item.root),yaml:this.yaml(),recipe:this.recipe(),parameters:parametersMap(this.parameters())};
      const {id}=await api<{id:string}>('preview',body,controller.signal);
      while(!controller.signal.aborted && revision===this.revision){
        const job=await api<Job>(`jobs/${encodeURIComponent(id)}`,undefined,controller.signal);
        if(revision!==this.revision)return;
        this.job.set(job);
        if(job.status==='COMPLETED'){this.stage.set('review');break;}
        if(job.status==='FAILED'){this.error.set(job.error||'L’anteprima non è stata completata.');break;}
        await new Promise(resolve=>setTimeout(resolve,500));
      }
    }catch(error){if(revision===this.revision)this.fail(error);}finally{if(revision===this.revision){this.busy.set(false);this.controller=undefined;}}
  }
}
