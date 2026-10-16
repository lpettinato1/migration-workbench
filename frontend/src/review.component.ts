import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  type OnChanges,
  type OnDestroy,
  Output,
  type SimpleChanges,
  ViewChild,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DiffComponent, EditorComponent } from './editor.component';
import { api, download } from './api';
import type { FileResult, GitDetails, OperationState, Report, RootInfo } from './model';
import { changed, fileKey, filePath, groupFiles, reviewedPaths, statusLabel } from './model';

type OperationKind = 'review' | 'apply' | 'commit' | 'push';

@Component({
  selector: 'mw-review',
  standalone: true,
  imports: [FormsModule, DiffComponent, EditorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './review.component.html',
})
export class ReviewComponent implements OnChanges, OnDestroy {
  @ViewChild('operationDialog') operationDialog?: ElementRef<HTMLDialogElement>;
  @Input({ required: true }) jobId = '';
  @Input({ required: true }) reports: Report[] = [];
  @Input({ required: true }) roots: RootInfo[] = [];
  @Output() edit = new EventEmitter<void>();
  @Output() rerun = new EventEmitter<void>();
  @Output() busyChange = new EventEmitter<boolean>();
  @Output() pendingCommitChange = new EventEmitter<boolean>();
  readonly operationStates = signal<OperationState[]>([]);
  readonly operationsLoaded = signal(false);
  readonly operationBusy = signal(false);
  readonly operationError = signal('');
  readonly operationMessage = signal('');
  readonly uncertainMutation = signal(false);
  readonly confirmation = signal<{ kind: OperationKind; root: string } | null>(null);
  readonly remoteOptions = signal<Record<string, string[]>>({});
  readonly gitDetailsLoading = signal(false);
  readonly remote = signal('');
  readonly commitMessage = signal('chore: migrazione configurazioni');
  readonly operationLabels: Record<OperationKind, string> = {
    review: 'Conferma la verifica',
    apply: 'Applica le modifiche',
    commit: 'Crea il commit',
    push: 'Esegui il push',
  };
  readonly stageLabels: Record<OperationState['stage'], string> = {
    PREVIEW: 'Anteprima',
    REVIEWED: 'Verifica confermata',
    APPLIED: 'Modifiche applicate',
    COMMITTED: 'Commit creato',
    PUSHED: 'Push completato',
  };
  private operationRevision = 0;
  private operationsEpoch = 0;
  readonly selectedRoot = signal('');
  readonly selectedKey = signal('');
  readonly filter = signal('changed');
  readonly search = signal('');
  readonly tab = signal<'diff' | 'before' | 'after' | 'patch' | 'resolved'>('diff');
  readonly reviewed = signal<Set<string>>(new Set());
  readonly collapsed = signal<Set<string>>(new Set());
  readonly path = filePath;
  readonly status = statusLabel;
  readonly changed = changed;
  readonly key = fileKey;

  ngOnChanges(changes: SimpleChanges) {
    if (!changes['jobId']) return;
    this.operationRevision++;
    this.operationDialog?.nativeElement.close();
    this.confirmation.set(null);
    this.operationStates.set([]);
    this.reviewed.set(new Set());
    this.operationsLoaded.set(false);
    this.remoteOptions.set({});
    this.operationError.set('');
    this.operationMessage.set('');
    void this.refreshOperations();
  }
  ngOnDestroy() {
    this.operationRevision++;
    this.operationDialog?.nativeElement.close();
  }
  operation(report: Report) {
    return this.operationStates().find((item) => item.root === report.root);
  }
  protectedPendingCommit() {
    return this.operationStates().some(
      (item) =>
        item.stage === 'APPLIED' && !!this.reports.find((report) => report.root === item.root)?.git,
    );
  }
  configurationLocked() {
    return this.operationBusy() || this.protectedPendingCommit() || this.uncertainMutation();
  }
  hasApplied() {
    return this.operationStates().some((item) =>
      ['APPLIED', 'COMMITTED', 'PUSHED'].includes(item.stage),
    );
  }
  private updateOperations(operations: OperationState[]) {
    this.operationStates.set(operations);
    this.uncertainMutation.set(false);
    this.reviewed.update((current) => {
      const next = new Set(current);
      for (const operation of operations)
        if (operation.stage !== 'PREVIEW') {
          const report = this.reports.find((report) => report.root === operation.root);
          report?.files.filter(changed).forEach((file) => next.add(fileKey(report.root, file)));
        }
      return next;
    });
    this.pendingCommitChange.emit(this.protectedPendingCommit());
  }
  async refreshOperations() {
    if (!this.jobId) return false;
    const revision = this.operationRevision;
    const epoch = ++this.operationsEpoch;
    try {
      const result = await api<{ operations: OperationState[]; busy?: boolean }>(
        `jobs/${encodeURIComponent(this.jobId)}/operations`,
      );
      if (revision !== this.operationRevision || epoch !== this.operationsEpoch) return false;
      if (result.busy) {
        this.operationStates.set(result.operations);
        this.operationsLoaded.set(false);
        this.uncertainMutation.set(true);
        this.pendingCommitChange.emit(true);
        this.operationError.set(
          'Un’operazione è ancora in corso nel motore. Aggiorna lo stato prima di proseguire.',
        );
        return false;
      }
      this.updateOperations(result.operations);
      this.operationsLoaded.set(true);
      const report = this.activeReport();
      if (report?.git) void this.loadGitDetails(report);
      return true;
    } catch (error) {
      if (revision === this.operationRevision && epoch === this.operationsEpoch) {
        this.operationsLoaded.set(false);
        this.operationError.set(
          error instanceof Error ? error.message : 'Impossibile leggere lo stato delle operazioni.',
        );
      }
      return false;
    }
  }
  private async loadGitDetails(report: Report) {
    if (!report.git || Object.hasOwn(this.remoteOptions(), report.root)) return;
    const revision = this.operationRevision;
    this.gitDetailsLoading.set(true);
    try {
      const details = await api<GitDetails>('git/details', { root: report.root });
      if (revision === this.operationRevision)
        this.remoteOptions.update((options) => ({ ...options, [report.root]: details.remotes }));
    } catch (error) {
      if (revision === this.operationRevision)
        this.operationError.set(
          error instanceof Error ? error.message : 'Impossibile leggere i remote Git.',
        );
    } finally {
      if (revision === this.operationRevision) this.gitDetailsLoading.set(false);
    }
  }
  canOperate(kind: OperationKind, report: Report) {
    if (
      !this.operationsLoaded() ||
      this.uncertainMutation() ||
      this.operationBusy() ||
      this.errors(report) ||
      report.files.some((file) => file.status === 'ERROR') ||
      !this.changes(report)
    )
      return false;
    const stage = this.operation(report)?.stage;
    if (kind === 'review') return stage === 'PREVIEW' && this.isReviewed(report);
    if (kind === 'apply') return stage === 'REVIEWED';
    if (kind === 'commit')
      return stage === 'APPLIED' && !!report.git && !!this.commitMessage().trim();
    return (
      stage === 'COMMITTED' &&
      !!report.git &&
      !!this.remote() &&
      (this.remoteOptions()[report.root] || []).includes(this.remote())
    );
  }
  openConfirmation(kind: OperationKind, report: Report) {
    if (!this.canOperate(kind, report)) return;
    this.operationError.set('');
    this.confirmation.set({ kind, root: report.root });
    this.operationDialog?.nativeElement.showModal();
  }
  confirmationReport() {
    const confirmation = this.confirmation();
    return this.reports.find((report) => report.root === confirmation?.root);
  }
  cancelConfirmation(event?: Event) {
    if (this.operationBusy()) {
      event?.preventDefault();
      return;
    }
    this.operationDialog?.nativeElement.close();
    this.confirmation.set(null);
  }
  async performConfirmed() {
    const confirmation = this.confirmation(),
      report = this.confirmationReport();
    if (!confirmation || !report || !this.canOperate(confirmation.kind, report)) return;
    const revision = this.operationRevision,
      kind = confirmation.kind;
    let body: Record<string, unknown> = { root: report.root, confirmed: true };
    try {
      if (kind === 'review')
        body = { ...body, reviewedPaths: reviewedPaths(report, this.reviewed()) };
      if (kind === 'commit') body = { ...body, message: this.commitMessage().trim() };
      if (kind === 'push') body = { ...body, remote: this.remote() };
    } catch (error) {
      this.operationError.set(
        error instanceof Error ? error.message : 'Verifica i dati dell’operazione.',
      );
      return;
    }
    this.operationsEpoch++;
    this.operationBusy.set(true);
    this.busyChange.emit(true);
    this.operationError.set('');
    this.operationMessage.set('');
    try {
      const result = await api<OperationState>(
        `jobs/${encodeURIComponent(this.jobId)}/${kind}`,
        body,
      );
      if (revision !== this.operationRevision) return;
      this.operationsEpoch++;
      this.updateOperations([
        ...this.operationStates().filter((item) => item.root !== result.root),
        result,
      ]);
      const messages: Record<OperationKind, string> = {
        review: 'Verifica confermata. Puoi ora applicare le modifiche.',
        apply: 'Modifiche applicate alla cartella selezionata.',
        commit: `Commit creato: ${result.commit}`,
        push: `Push completato sul remote ${result.remote}.`,
      };
      this.operationMessage.set(messages[kind]);
      this.operationDialog?.nativeElement.close();
      this.confirmation.set(null);
    } catch (error) {
      if (revision !== this.operationRevision) return;
      const message = error instanceof Error ? error.message : 'Operazione non riuscita.';
      const refreshed = await this.refreshOperations();
      if (revision !== this.operationRevision) return;
      if (!refreshed) {
        this.uncertainMutation.set(true);
        this.pendingCommitChange.emit(true);
        this.operationError.set(
          message +
            ' Impossibile verificare lo stato finale: aggiorna lo stato prima di iniziare una nuova migrazione.',
        );
        return;
      }
      const stages: OperationState['stage'][] = [
        'PREVIEW',
        'REVIEWED',
        'APPLIED',
        'COMMITTED',
        'PUSHED',
      ];
      const expected: Record<OperationKind, OperationState['stage']> = {
        review: 'REVIEWED',
        apply: 'APPLIED',
        commit: 'COMMITTED',
        push: 'PUSHED',
      };
      const recovered = this.operation(report);
      if (!recovered || stages.indexOf(recovered.stage) < stages.indexOf(expected[kind])) {
        this.operationError.set(message);
        return;
      }
      this.operationError.set('');
      this.operationMessage.set(
        'Il servizio ha completato l’operazione. Stato recuperato: ' +
          this.stageLabels[recovered.stage] +
          (recovered.commit ? ' · ' + recovered.commit : ''),
      );
      this.operationDialog?.nativeElement.close();
      this.confirmation.set(null);
    } finally {
      if (revision === this.operationRevision) {
        this.operationBusy.set(false);
        this.busyChange.emit(false);
      }
    }
  }

  activeReport() {
    return this.reports.find((report) => report.root === this.selectedRoot()) || this.reports[0];
  }

  name(root: string) {
    return this.roots.find((item) => item.root === root)?.name || root.split(/[\\/]/).pop() || root;
  }

  rootGroup(root: string) {
    return this.roots.find((item) => item.root === root)?.group.trim() || 'Senza gruppo';
  }

  groups() {
    const groups = new Map<string, Report[]>();
    for (const report of this.reports) {
      const group = this.rootGroup(report.root);
      const reports = groups.get(group);
      if (reports) reports.push(report);
      else groups.set(group, [report]);
    }
    return [...groups.entries()];
  }

  changes(report: Report) {
    return report.files.filter(changed).length;
  }

  warnings(report: Report) {
    return report.diagnostics.filter((item) => item.severity === 'WARNING').length;
  }

  errors(report: Report) {
    return report.diagnostics.filter((item) => item.severity === 'ERROR').length;
  }

  countChanges() {
    return this.reports.reduce((sum, report) => sum + this.changes(report), 0);
  }

  countErrors() {
    return this.reports.reduce((sum, report) => sum + this.errors(report), 0);
  }

  countWarnings() {
    return this.reports.reduce((sum, report) => sum + this.warnings(report), 0);
  }

  isReviewed(report: Report) {
    const operation = this.operation(report);
    if (operation && operation.stage !== 'PREVIEW') return true;
    const files = report.files.filter(changed);
    return (
      files.length > 0 && files.every((file) => this.reviewed().has(fileKey(report.root, file)))
    );
  }

  selectReport(report: Report) {
    if (this.operationBusy()) return;
    this.selectedRoot.set(report.root);
    this.selectedKey.set('');
    this.tab.set('diff');
    this.remote.set('');
    void this.loadGitDetails(report);
  }

  visibleFiles(report: Report) {
    const query = this.search().toLocaleLowerCase();
    const statuses: Record<string, FileResult['status'][]> = {
      changed: ['MODIFIED', 'CREATED', 'DELETED', 'RENAMED'],
      attention: ['SKIPPED', 'ERROR'],
      unchanged: ['UNCHANGED'],
    };
    const selected = statuses[this.filter()];
    return report.files.filter(
      (file) =>
        (!query || filePath(file).toLocaleLowerCase().includes(query)) &&
        (!selected || selected.includes(file.status)),
    );
  }

  fileGroups(report: Report) {
    return [...groupFiles(this.visibleFiles(report)).entries()];
  }

  activeFile(report: Report) {
    const visible = this.visibleFiles(report);
    return visible.find((file) => fileKey(report.root, file) === this.selectedKey()) || visible[0];
  }

  selectFile(report: Report, file: FileResult) {
    this.selectedKey.set(fileKey(report.root, file));
    if (this.tab() === 'resolved') this.tab.set('diff');
  }

  mark(report: Report, file: FileResult) {
    if (
      this.operationBusy() ||
      !this.operationsLoaded() ||
      this.operation(report)?.stage !== 'PREVIEW'
    )
      return;
    const key = fileKey(report.root, file);
    this.reviewed.update((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  toggleGroup(group: string) {
    this.collapsed.update((current) => {
      const next = new Set(current);
      next.has(group) ? next.delete(group) : next.add(group);
      return next;
    });
  }

  exportAll() {
    download(
      'migration-review.json',
      JSON.stringify(
        {
          schemaVersion: 1,
          exportedAt: new Date().toISOString(),
          reviewedFiles: [...this.reviewed()],
          operations: this.operationStates(),
          reports: this.reports,
        },
        null,
        2,
      ),
    );
  }

  exportReport(report: Report) {
    download(`${this.name(report.root)}-preview.json`, JSON.stringify(report, null, 2));
  }

  diagnosticFor(report: Report, file: FileResult) {
    return report.diagnostics.filter(
      (item) => item.path === file.beforePath || item.path === file.afterPath,
    );
  }
}
