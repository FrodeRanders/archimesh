<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchJson } from '$lib/api/client.js';
  import { inboundTargets, inboundStatus, approveInbound, refreshInbound, inspectInbound, acceptInbound } from '$lib/api/inbound.js';
  let models = [], targets = [], target = '', status = null, inspection = null, busy = false, message = 'Choose a model and inbound target.';
  let cursor = '', pages = [], sequence = 0;
  const work = async action => {
    if (busy) return;
    busy = true;
    try { await action(); } catch (error) { message = error.message; } finally { busy = false; }
  };
  const load = async (more = false) => {
    if (!$selectedModelId || !target) return;
    const model = $selectedModelId, chosen = target;
    const loaded = await inboundStatus(model, chosen, more ? cursor : '');
    if (model !== $selectedModelId || chosen !== target) return;
    status = loaded; pages = more ? [...pages, ...loaded.bindings] : loaded.bindings; cursor = loaded.nextCursor || '';
  };
  const loadTarget = async () => { status = null; inspection = null; pages = []; cursor = ''; await load(); };
  const loadModel = async () => {
    const current = ++sequence, model = $selectedModelId;
    target = ''; targets = []; status = null; inspection = null; pages = []; cursor = '';
    if (!model) return;
    const available = await inboundTargets(model);
    if (current !== sequence || model !== $selectedModelId) return;
    targets = available; target = targets[0] || '';
    if (target) await loadTarget(); else message = 'No inbound target is approved for this model administrator.';
  };
  const approve = enabled => work(async () => {
    await approveInbound($selectedModelId, target, { enabled, expectedVersion: status.policyVersion, targetInstanceId: status.targetInstanceId, mappingVersion: status.mappingVersion });
    inspection = null; await load(); message = enabled ? 'Inbound synchronization approved for the mapped central fields. HEAD updates through ordinary accepted operations.' : 'Inbound updates paused. Server ownership checks remain active.';
  });
  const reconcile = element => work(async () => { await refreshInbound($selectedModelId, target, element); await load(); message = 'Reconciliation completed. Review the binding status for conflicts or errors.'; });
  const inspect = element => work(async () => { inspection = await inspectInbound($selectedModelId, target, element); message = 'Review baseline, current local values and central values before accepting central values into HEAD.'; });
  const accept = () => work(async () => { await acceptInbound($selectedModelId, target, { elementId: inspection.elementId, inspectionDigest: inspection.digest }); inspection = null; await load(); message = 'Central values verified in HEAD. Historical tags and the external repository are unchanged.'; });
  onMount(() => {
    work(async () => { models = await fetchJson('/models', { action: 'Readable models' }); if (!$selectedModelId && models.length) selectedModelId.set(models[0].modelId); await loadModel(); });
    const timer = setInterval(() => { if (!busy && target) load().catch(error => message = error.message); }, 5000);
    return () => clearInterval(timer);
  });
</script>
<PageHero eyebrow="Inbound synchronization" title="Keep linked HEAD models aligned with central records." description="Approve mapped Turbo EA-owned fields per model. Conflicting local changes require review; tags remain immutable." />
<StatusBanner message={message} />
<Panel title="Model and target" subtitle="Approval requires a named inbound administrator who is explicitly listed in the model administrator ACL.">
  <div class="controls">
    <label>Model <select value={$selectedModelId} disabled={busy} on:change={event => { selectedModelId.set(event.currentTarget.value); work(loadModel); }}><option value="">Choose model</option>{#each models as model}<option value={model.modelId}>{model.modelName || model.modelId}</option>{/each}</select></label>
    <label>Target <select bind:value={target} disabled={busy || !targets.length} on:change={() => work(loadTarget)}>{#each targets as choice}<option value={choice}>{choice}</option>{/each}</select></label>
    <button class="secondary" disabled={busy || !target} on:click={() => work(() => load())}>Refresh status</button>
  </div>
</Panel>
{#if status}
  <Panel title="Inbound policy" subtitle="SSE provides change signals; scheduled polling repairs missed events. Approval covers every bound card in this model within the listed mapping scope.">
    <p>Policy version {status.policyVersion} · {status.enabled ? 'Enabled' : 'Paused'} · mapping {status.mappingVersion}</p>
    {#each Object.entries(status.fields) as [type, fields]}<p><strong>{type}</strong>: {fields.join(', ')}</p>{/each}
    <div class="controls"><button disabled={busy || status.enabled} on:click={() => approve(true)}>Approve inbound synchronization</button><button class="secondary" disabled={busy || !status.enabled} on:click={() => approve(false)}>Pause inbound updates</button></div>
  </Panel>
  <Panel title="Linked occurrences" subtitle="Missing policy, changed permissions, drift, retirement and missing external cards remain visible. Reconciliation creates no remote cards or writes.">
    {#if !pages.length}<p>No bound cards in this model.</p>{:else}<div class="table-wrap"><table><thead><tr><th>Element</th><th>State</th><th>Diagnostic</th><th>Verified revision</th><th>Actions</th></tr></thead><tbody>{#each pages as binding}<tr><td>{binding.elementId}</td><td>{binding.state}</td><td>{binding.code || '—'}</td><td>{binding.verifiedRevision || '—'}</td><td><button class="secondary" disabled={busy || !status.enabled} on:click={() => reconcile(binding.elementId)}>Reconcile</button> <button class="secondary" disabled={busy} on:click={() => inspect(binding.elementId)}>Inspect</button></td></tr>{/each}</tbody></table></div>{/if}
    {#if cursor}<button class="secondary" disabled={busy} on:click={() => work(() => load(true))}>More bindings</button>{/if}
  </Panel>
  {#if inspection}<Panel title="Review incoming values" subtitle="Accepting central values changes HEAD through validated model operations and updates the baseline after durable acceptance.">
    <p>{inspection.elementId} · HEAD revision {inspection.revision}</p><div class="comparison"><div><h3>Last verified baseline</h3><pre>{JSON.stringify(inspection.base, null, 2)}</pre></div><div><h3>Current local values</h3><pre>{JSON.stringify(inspection.local, null, 2)}</pre></div><div><h3>Current central values</h3><pre>{JSON.stringify(inspection.remote, null, 2)}</pre></div></div>{#if inspection.observedConflict}<h3>Recorded conflict observation</h3><pre>{JSON.stringify(inspection.observedConflict, null, 2)}</pre>{/if}<button disabled={busy || !status.enabled} on:click={accept}>Accept these central values into HEAD</button>
  </Panel>{/if}
{/if}
<style>
  .controls { display:flex; flex-wrap:wrap; align-items:end; gap:1rem; margin-bottom:1rem; }
  .controls label { display:grid; gap:0.4rem; } .comparison { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:1rem; }
  pre { white-space:pre-wrap; overflow-wrap:anywhere; } .table-wrap { overflow-x:auto; }
  table { width:100%; border-collapse:collapse; } th,td { padding:0.65rem; text-align:left; border-bottom:1px solid var(--line); }
  @media(max-width:900px) { .comparison { grid-template-columns:1fr; } }
</style>
