<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import { fetchJson } from '$lib/api/client.js';
  import { loadAdminUiConfig } from '$lib/api/ui.js';
  import { initAuthStores } from '$lib/stores/auth.js';
  import { integrationOverview, integrationDocuments, integrationAction, integrationModelReview, integrationModelAction } from '$lib/api/integrations.js';
  let overview = null, busy = false, message = 'Integration operations require a named operator and server administrator access.';
  let kind = 'RECONCILIATION', rows = [], cursor = '', target = '', scanRequest = null, probe = null, resumeConfirmed = false;
  let models = [], model = '', attachment = null, attachConfirmed = false, archive = null, restore = null, restoreConfirmed = false;
  const kinds = ['RECONCILIATION', 'MODEL_SCOPE', 'INBOUND_STATE', 'PUBLICATION_JOB', 'PUBLICATION_RESOURCE', 'CENTRAL_EDIT', 'CENTRAL_LANE', 'CENTRAL_DELIVERY', 'CONFLICT', 'CHECKPOINT'];
  const work = async action => { if (busy) return; busy = true; try { await action(); } catch (error) { message = error.message; } finally { busy = false; } };
  const loadRows = async (more = false) => { const result = await integrationDocuments(kind, more ? cursor : ''); rows = more ? [...rows, ...result.items] : result.items; cursor = result.nextCursor || ''; };
  const refresh = async () => { overview = await integrationOverview(); if (!target) target = overview.targets[0]?.key || ''; await loadRows(); };
  const pause = () => work(async () => { overview = await integrationAction('pause', { expectedVersion: overview.controlVersion }); probe = null; message = 'Integrations paused. Model editing remains available and ownership guards stay active.'; });
  const inspectResume = () => work(async () => { probe = await integrationAction('probe'); resumeConfirmed = false; message = 'Configured API versions and metamodels verified. Review the target identities before resuming.'; });
  const resume = () => work(async () => { overview = await integrationAction('resume', { expectedVersion: probe.details.controlVersion, digest: probe.digest }); probe = null; message = 'Integration workers resumed. Restored model scopes remain paused until separately reviewed.'; });
  const scan = () => work(async () => { scanRequest ||= { requestId: crypto.randomUUID(), targetKey: target }; const result = await integrationAction('reconcile', scanRequest); scanRequest = null; kind = 'RECONCILIATION'; await loadRows(); message = `Full scan ${result.id} queued. Completion means scopes were inspected; inspect per-model conflicts and delivery results.`; });
  const reviewModel = () => work(async () => { attachment = await integrationModelReview(model); attachConfirmed = false; });
  const pauseModel = () => work(async () => { await integrationModelAction(model, 'pause', { expectedVersion: attachment.details.scopeVersion }); attachment = await integrationModelReview(model); message = 'This model’s integrations are paused.'; });
  const attachModel = () => work(async () => { await integrationModelAction(model, 'attach', { expectedVersion: attachment.details.scopeVersion, digest: attachment.digest }); attachment = null; message = 'Identity attachment verified. Reapprove inbound/publication policies and review field baselines in their administration pages.'; await refresh(); });
  const download = () => work(async () => { const data = await integrationAction('archive/export'); const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })); const link = document.createElement('a'); link.href = url; link.download = 'archimesh-integration-v1.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); message = 'Integration archive exported. Store it with the matching model packages; it includes retained field values and proposals.'; });
  const loadArchive = event => { const file = event.currentTarget.files?.[0]; archive = null; restore = null; restoreConfirmed = false; if (!file) return; work(async () => { if (file.size > 8388608) throw new Error('Integration archive exceeds the 8 MiB limit.'); archive = JSON.parse(await file.text()); message = 'Archive loaded locally. Preview validates its digest, instance, targets and destination before restore.'; }); };
  const previewRestore = () => work(async () => { restore = await integrationAction('archive/preview', archive); restoreConfirmed = false; });
  const restoreArchive = () => work(async () => { await integrationAction('archive/restore', { archive, digest: restore.digest }); archive = null; restore = null; message = 'Integration metadata restored atomically and paused. Review model attachment and reapprove policies before reconciliation.'; await refresh(); });
  onMount(() => {
    work(async () => { initAuthStores(); await loadAdminUiConfig(); await refresh(); models = await fetchJson('/models', { action: 'Models' }); model = models[0]?.modelId || ''; });
    const timer = setInterval(() => { if (!busy && overview && rows.length <= 50) work(async () => { overview = await integrationOverview(); await loadRows(); }); }, 5000);
    return () => clearInterval(timer);
  });
</script>
<PageHero eyebrow="Integration operations" title="Pause, inspect and recover enterprise integration." description="Worker controls and integration backups are independent of model editing and model history. Retained uncertain writes are never replayed blindly." />
<StatusBanner message={message} />
{#if overview}
  <Panel title="Runtime and targets" subtitle="Configuration is deployed on the server. API/metamodel changes require a reviewed configuration migration, followed by fresh policy approval.">
    <p>{overview.instanceId} · {overview.paused ? 'PAUSED' : 'RUNNING'} · observed {overview.observedAt}</p>
    <div class="controls"><button class="secondary" disabled={busy} on:click={() => work(refresh)}>Refresh diagnostics</button><button disabled={busy || overview.paused} on:click={pause}>Pause integrations</button><button disabled={busy || !overview.paused} on:click={inspectResume}>Inspect targets for resume</button></div>
    <div class="table-wrap"><table><thead><tr><th>Target</th><th>Instance</th><th>API / mapping</th></tr></thead><tbody>{#each overview.targets as item}<tr><td>{item.key}</td><td>{item.instanceId}</td><td>{item.apiVersion} / {item.mappingVersion}</td></tr>{/each}</tbody></table></div>
    <details><summary>Configured mappings, authority and access scopes</summary><pre>{JSON.stringify(overview.configuration, null, 2)}</pre></details>
    {#if probe}<pre>{JSON.stringify(probe.details.targets, null, 2)}</pre><label><input type="checkbox" bind:checked={resumeConfirmed} disabled={busy} /> I have verified these target identities and approve resuming workers.</label><button disabled={busy || !resumeConfirmed} on:click={resume}>Resume integrations</button>{/if}
  </Panel>
  <Panel title="Counts and full reconciliation" subtitle="A full scan rereads every bound card and schedules currently approved publication policies. It reports conflicts; it does not resolve them or replay uncertain writes.">
    <div class="table-wrap"><table><thead><tr><th>Document kind</th><th>State</th><th>Count</th><th>Oldest verification</th><th>Next retry</th></tr></thead><tbody>{#each overview.counts.documents as item}<tr><td>{item.kind}</td><td>{item.state}</td><td>{item.count}</td><td>{item.oldestVerifiedAt ? new Date(item.oldestVerifiedAt).toISOString() : '—'}</td><td>{item.nextRetryAt ? new Date(item.nextRetryAt).toISOString() : '—'}</td></tr>{/each}</tbody></table></div>
    <div class="controls"><label>Target <select bind:value={target} disabled={busy || !!scanRequest}>{#each overview.targets as item}<option value={item.key}>{item.key}</option>{/each}</select></label><button disabled={busy || overview.paused || !target} on:click={scan}>{scanRequest ? 'Retry same scan request' : 'Start full reconciliation'}</button></div>
    <p>Authenticated monitoring endpoint: <code>/admin/integrations/metrics</code>. Verification age measures the last successful readback, not source-event latency.</p>
  </Panel>
  <Panel title="Durable diagnostics" subtitle="Status pages omit field values. Use the publication, inbound and central-change pages for scoped review and conflict resolution.">
    <label>Record kind <select bind:value={kind} disabled={busy} on:change={() => work(() => loadRows())}>{#each kinds as name}<option value={name}>{name}</option>{/each}</select></label>
    {#each rows as row}<pre>{JSON.stringify(row, null, 2)}</pre>{/each}{#if cursor}<button class="secondary" disabled={busy} on:click={() => work(() => loadRows(true))}>More records</button>{/if}
  </Panel>
  <Panel title="Review model attachment" subtitle="Overwrite and restore invalidate old work. Review local entities, canonical identities and fresh remote values before attaching the model’s current generation. Incoming field baselines then need a new review. Compatible mapping version changes require global pause and are listed in the review.">
    <label>Model <select bind:value={model} disabled={busy} on:change={() => { attachment = null; attachConfirmed = false; }}>{#each models as item}<option value={item.modelId}>{item.modelName || item.modelId}</option>{/each}</select></label><button disabled={busy || !model} on:click={reviewModel}>Inspect model attachment</button>
    {#if attachment}<pre>{JSON.stringify(attachment.details, null, 2)}</pre><button class="secondary" disabled={busy} on:click={pauseModel}>Pause this model</button><label><input type="checkbox" bind:checked={attachConfirmed} disabled={busy || !attachment.details.canAttach} /> I reviewed the model and remote identities and approve this attachment and any listed mapping version changes.</label><button disabled={busy || !attachConfirmed || !attachment.details.canAttach} on:click={attachModel}>Attach reviewed model generation</button>{/if}
  </Panel>
  <Panel title="Integration archive" subtitle="Export requires pause. Restore requires an empty integration scope, matching instance/configuration and explicit preview. Model packages are restored separately. Unknown remote outcomes and their fences are retained.">
    <button disabled={busy || !overview.paused} on:click={download}>Export integration metadata</button>
    <label>Integration archive <input type="file" accept="application/json,.json" disabled={busy || !overview.paused} on:change={loadArchive} /></label><button class="secondary" disabled={busy || !overview.paused || !archive} on:click={previewRestore}>Preview paused restore</button>
    {#if restore}<pre>{JSON.stringify(restore.details, null, 2)}</pre><label><input type="checkbox" bind:checked={restoreConfirmed} disabled={busy} /> I approve restoring this metadata into the reviewed empty destination, paused.</label><button disabled={busy || !restoreConfirmed} on:click={restoreArchive}>Restore integration metadata paused</button>{/if}
  </Panel>
{/if}
<style>
  .controls { display:flex; flex-wrap:wrap; gap:1rem; align-items:end; margin:1rem 0; } label { display:block; margin:0.7rem 0; } button { margin:0.5rem 0.5rem 0.5rem 0; }
  pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:0.7rem; background:var(--bg-alt); border-radius:0.6rem; } .table-wrap { overflow-x:auto; } table { width:100%; border-collapse:collapse; } th,td { text-align:left; vertical-align:top; padding:0.6rem; border-bottom:1px solid var(--line); }
</style>
