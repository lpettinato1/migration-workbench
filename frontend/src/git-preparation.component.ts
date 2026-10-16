import {ChangeDetectionStrategy, Component, EventEmitter, Input, type OnChanges, type OnDestroy, Output, type SimpleChanges, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {api} from './api';
import type {GitDetails, RootInfo} from './model';

@Component({selector:'mw-git-preparation',standalone:true,imports:[FormsModule],changeDetection:ChangeDetectionStrategy.OnPush,templateUrl:'./git-preparation.component.html'})
export class GitPreparationComponent implements OnChanges,OnDestroy {
  @Input({required:true}) root!:RootInfo;
  @Input() disabled=false;
  @Output() prepared=new EventEmitter<RootInfo>();
  @Output() busyChange=new EventEmitter<boolean>();
  readonly opened=signal(false);readonly loading=signal(false);readonly preparing=signal(false);readonly confirmation=signal(false);
  readonly error=signal('');readonly details=signal<GitDetails|null>(null);
  source='';branch='migration/configurazioni';worktree='';
  private revision=0;
  private static sequence=0;
  readonly referenceListId=`git-refs-${++GitPreparationComponent.sequence}`;
  ngOnChanges(changes:SimpleChanges){
    if(!changes['root'])return;
    this.revision++;this.details.set(null);this.confirmation.set(false);this.source=this.root.git?.branch||'HEAD';
    const clean=this.root.root.replace(/[\\/]+$/,'');
    const position=Math.max(clean.lastIndexOf('/'),clean.lastIndexOf('\\'));
    const separator=clean.includes('\\')?'\\':'/';
    this.worktree=`${clean.slice(0,position)}${separator}${this.root.name}-migration`;
    if(this.opened())void this.load();
  }
  ngOnDestroy(){this.revision++;}
  toggle(){if(this.disabled||this.preparing())return;this.opened.update(open=>!open);if(this.opened()&&!this.details())void this.load();}
  private async load(){
    const revision=this.revision;this.loading.set(true);this.error.set('');
    try{const details=await api<GitDetails>('git/details',{root:this.root.root});if(revision===this.revision)this.details.set(details);}
    catch(error){if(revision===this.revision)this.error.set(error instanceof Error?error.message:'Impossibile leggere i branch del repository.');}
    finally{if(revision===this.revision)this.loading.set(false);}
  }
  async prepare(){
    if(this.disabled||this.preparing()||!this.confirmation())return;
    const revision=this.revision;
    this.preparing.set(true);this.error.set('');this.busyChange.emit(true);
    try{const result=await api<Omit<RootInfo,'group'>>('git/prepare',{repository:this.root.root,source:this.source.trim(),branch:this.branch.trim(),worktree:this.worktree.trim(),confirmed:true});if(revision===this.revision)this.prepared.emit({...result,group:this.root.group});}
    catch(error){if(revision===this.revision)this.error.set(error instanceof Error?error.message:'La preparazione del repository non è riuscita.');}
    finally{this.preparing.set(false);this.busyChange.emit(false);}
  }
}
