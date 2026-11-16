<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchJson } from '$lib/api/client.js';
  import { fetchModelTags } from '$lib/api/models.js';
  import { publicationTargets, publicationStatus, publicationProfiles, validatePublicationProfile, explainPublication, approvePublication, runPublication, inspectPublication, adoptPublication } from '$lib/api/publication.js';

  let models = [], targets = [], tags = [], snapshot = null, target = '', state = null;
  let mode = 'DISABLED', ref = '', elements = [], relationships = [], stewards = [];
  let reviewed = null, inspected = null, remoteId = '', busy = false, message = 'Choose a model and an approved publication target.';
  let availableProfiles = [], profilePin = null, profileChoice = '', validation = null, explanation = null;
  $: pinnedProfileAvailable = !profilePin || availableProfiles.some(p => p.pin.profileId === profilePin.profileId && p.pin.version === profilePin.version && p.pin.digest === profilePin.digest && p.definition.enabled);
  let pendingRun = null, previewSignature = '', loadSequence = 0;
  $: spec = { mode, ref: mode === 'TAG' ? ref : 'HEAD', elements: [...elements].sort(), relationships: [...relationships].sort(), stewards: stewards.filter(id => elements.includes(id) || relationships.includes(id)).sort(), ...(profilePin ? { profile: profilePin } : {}) };
  $: signature = JSON.stringify([$selectedModelId, target, spec]);
  $: if (signature !== previewSignature) { reviewed = null; explanation = null; }

  const work = async (action) => {
    if (busy) return;
    busy = true;
    try { await action(); } catch (error) { message = error.message; } finally { busy = false; }
  };
  const readStatus = async () => {
    if (!$selectedModelId || !target) return;
    const model = $selectedModelId, chosen = target;
    const loaded = await publicationStatus(model, chosen);
    if (model === $selectedModelId && chosen === target) state = loaded;
  };
  const readSnapshot = async () => {
    if (!$selectedModelId || mode === 'TAG' && !ref) { snapshot = null; return; }
    const model = $selectedModelId, source = mode === 'TAG' ? ref : 'HEAD';
    const loaded = await fetchJson(`/models/${encodeURIComponent(model)}/snapshot?ref=${encodeURIComponent(source)}`, { action: 'Publication source snapshot' });
    if (model === $selectedModelId && source === (mode === 'TAG' ? ref : 'HEAD')) snapshot = loaded;
  };
  const loadTarget = async () => {
    state = null; inspected = null; reviewed = null; pendingRun = null; validation = null; explanation = null; availableProfiles = []; profilePin = null; profileChoice = "";
    if (!target) return;
    await readStatus();
    const catalog = await publicationProfiles($selectedModelId, target); availableProfiles = catalog.profiles;
    const policy = state?.policy?.policy;
    mode = policy?.mode === 'HEAD' ? 'TAG' : policy?.mode || 'DISABLED'; ref = policy?.ref === 'HEAD' ? '' : policy?.ref || '';
    profilePin = policy?.profile || null; profileChoice = profilePin ? `${profilePin.profileId}@${profilePin.version}` : "";
    elements = policy?.elements || []; relationships = policy?.relationships || []; stewards = policy?.stewards || [];
    await readSnapshot(); message = 'Review the source, selection and stewardship before approving a policy.';
  };
  const loadModel = async () => {
    const sequence = ++loadSequence; const model = $selectedModelId;
    target = ''; targets = []; state = null; reviewed = null; inspected = null; snapshot = null; pendingRun = null;
    if (!model) return;
    const [available, revisions] = await Promise.all([publicationTargets(model), fetchModelTags(model)]);
    if (sequence !== loadSequence || model !== $selectedModelId) return;
    targets = available; tags = revisions; target = targets[0] || '';
    if (target) await loadTarget(); else message = 'No publication target is enabled for this model administrator.';
  };
  const preview = () => work(async () => {
    const candidate = spec; const before = signature;
    const explained = await explainPublication($selectedModelId, target, candidate); const result = explained.preview;
    if (before !== signature) return;
    previewSignature = before; reviewed = result; explanation = explained;
    message = 'Snapshot projection ready. Live API and baseline checks run before any remote write.';
  });
  const chooseProfile = () => {
    profilePin = availableProfiles.find(p => `${p.pin.profileId}@${p.pin.version}` === profileChoice)?.pin || null; validation = null;
  };
  const validateProfile = () => work(async () => { validation = await validatePublicationProfile($selectedModelId, target, profilePin); message = validation.valid ? 'Profile mappings and live target capabilities verified. Model approval is still required.' : 'The profile exceeds current target capabilities; review the diagnostics.'; });
  const approve = () => work(async () => {
    if (!reviewed) return;
    await approvePublication($selectedModelId, target, { policy: reviewed.policy, expectedVersion: state?.policy?.version || 0, previewDigest: reviewed.digest });
    reviewed = null; await readStatus(); message = 'Publication policy approved. Disabled pauses further publication; Only the approved immutable tag is reconciled by the worker.';
  });
  const run = () => work(async () => {
    pendingRun ||= { requestId: crypto.randomUUID(), previewDigest: null };
    await runPublication($selectedModelId, target, pendingRun); pendingRun = null;
    await readStatus(); message = 'Publication queued. Refresh status to inspect its outcome.';
  });
  const inspect = (resource) => work(async () => {
    inspected = await inspectPublication($selectedModelId, target, resource.canonicalId, remoteId || resource.resourceId);
    message = 'Review the current remote values before approving the baseline for the next reconciliation.';
  });
  const adopt = () => work(async () => {
    await adoptPublication($selectedModelId, target, { canonicalId: inspected.canonicalId, resourceId: inspected.resourceId, remoteDigest: inspected.remoteDigest });
    inspected = null; await readStatus(); message = 'Remote baseline approved without a remote write. Queue reconciliation to apply approved model-owned values.';
  });
  const name = (id) => snapshot?.elements?.find(e => e.id === id)?.name || snapshot?.relationships?.find(e => e.id === id)?.name || id;
  onMount(() => {
    work(async () => {
      models = await fetchJson('/models', { action: 'Readable models' });
      if (!$selectedModelId && models.length) selectedModelId.set(models[0].modelId);
      await loadModel();
    });
    const timer = setInterval(() => { if (!busy && target) readStatus().catch(error => message = error.message); }, 5000);
    return () => clearInterval(timer);
  });
</script>

<PageHero eyebrow="Publication" title="Publish an approved architecture selection to Turbo EA." description="Choose an immutable tag for managed publication. Draft changes remain local until a new tag is approved." />
<StatusBanner message={message} />
<Panel title="Model and target" subtitle="Publication requires a named publisher who is explicitly listed as a model administrator.">
  <div class="controls">
    <label>Model <select value={$selectedModelId} disabled={busy} on:change={event => { selectedModelId.set(event.currentTarget.value); work(loadModel); }}><option value="">Choose model</option>{#each models as model}<option value={model.modelId}>{model.modelName || model.modelId}</option>{/each}</select></label>
    <label>Target <select bind:value={target} disabled={busy || !targets.length} on:change={() => work(loadTarget)}>{#each targets as choice}<option value={choice}>{choice}</option>{/each}</select></label>
    <button class="secondary" disabled={busy || !target} on:click={() => work(async () => { await readStatus(); await readSnapshot(); })}>Refresh</button>
  </div>
</Panel>
{#if target}
  <Panel title="Publication policy" subtitle="Direct resources retain explicit stewardship. Derived assertions combine evidence only from current, compatible approved model contributions.">
    <div class="controls">
      <label>Mode <select bind:value={mode} disabled={busy} on:change={() => work(readSnapshot)}><option value="DISABLED">Disabled</option><option value="TAG">Approved tag</option></select></label>
      {#if mode === 'TAG'}<label>Immutable tag <select bind:value={ref} disabled={busy} on:change={() => work(readSnapshot)}><option value="">Choose tag</option>{#each tags as tag}<option value={tag.tagName}>{tag.tagName} · revision {tag.revision}</option>{/each}</select></label>{/if}
      <span>Source revision: {snapshot?.headRevision ?? '—'}</span>
    </div>
    <div class="controls">
      <label>Publication profile <select bind:value={profileChoice} disabled={busy} on:change={chooseProfile}><option value="">Legacy explicit selection</option>{#if profilePin && !availableProfiles.some(p => `${p.pin.profileId}@${p.pin.version}` === profileChoice)}<option value={profileChoice}>Pinned version unavailable: {profileChoice}</option>{/if}{#each availableProfiles as entry}<option value={`${entry.pin.profileId}@${entry.pin.version}`} disabled={!entry.definition.enabled}>{entry.definition.name} · {entry.pin.version}</option>{/each}</select></label>
      {#if profilePin}<button class="secondary" disabled={busy || !pinnedProfileAvailable} on:click={validateProfile}>Validate live capabilities</button>{/if}
    </div>
    {#if profilePin}<p>Immutable pin: <code>{profilePin.profileId} · {profilePin.version} · {profilePin.digest}</code>. A profile narrows the explicit selection below.</p>{/if}
    {#if !pinnedProfileAvailable}<p role="alert">The pinned definition is disabled, unavailable or has changed. Restore the exact configuration or explicitly review a new immutable version.</p>{/if}
    {#if validation}<pre>{JSON.stringify(validation, null, 2)}</pre>{/if}
    <div class="selection">
      <div><h3>Semantic elements</h3>{#each snapshot?.elements || [] as element}<div class="choice"><label><input type="checkbox" bind:group={elements} value={element.id} disabled={busy} />{element.name || element.id} <small>{element.archimateType}</small></label>{#if elements.includes(element.id)}<label><input type="checkbox" bind:group={stewards} value={element.id} disabled={busy} />Steward</label>{/if}</div>{/each}</div>
      <div><h3>Semantic relationships</h3>{#each snapshot?.relationships || [] as relationship}<div class="choice"><label><input type="checkbox" bind:group={relationships} value={relationship.id} disabled={busy} />{relationship.name || relationship.id} <small>{relationship.archimateType}</small></label>{#if relationships.includes(relationship.id)}<label><input type="checkbox" bind:group={stewards} value={relationship.id} disabled={busy} />Steward</label>{/if}</div>{/each}</div>
    </div>
    <div class="controls"><button disabled={busy || mode === 'TAG' && !ref} on:click={preview}>Preview selection</button><button disabled={busy || !reviewed || !pinnedProfileAvailable || reviewed?.plan?.diagnostics?.some(d => d.severity === 'CONFLICT')} on:click={approve}>Approve policy</button></div>
    {#if explanation}<p>{explanation.counts.direct} direct resources · {explanation.counts.derived} derived assertions · {explanation.counts.existingBindings} existing bindings · {explanation.counts.proposedCreations} proposed creations.</p>
      {#each explanation.withdrawals as withdrawal}<p>Withdraw own contribution: <code>{withdrawal.canonicalId}</code>. Remote retirement requires separate review; other model contributions remain.</p>{/each}
      {#each explanation.contributions.filter(c => c.status === 'WITHDRAWN' || c.status === 'QUARANTINED') as contribution}<p>{contribution.status}: <code>{contribution.canonicalId}</code></p>{/each}
      {#each explanation.resources as resource}<p><code>{resource.entityId}</code>: {resource.operation} · {resource.state || 'PENDING'} {resource.code || ''}</p>{/each}
    {/if}
    {#if reviewed}{#each reviewed.plan.diagnostics || [] as diagnostic}<p>{diagnostic.severity}: <code>{diagnostic.entityId}</code> · {diagnostic.code}</p>{/each}<p>Projection at revision {reviewed.plan.revision}; mapping {reviewed.plan.mappingVersion}. Only mapped ArchiMesh-owned fields are included.</p>{#each reviewed.plan.resources as resource}<div class="projection"><strong>{name(resource.identity.model.entityId)}</strong> → {resource.remoteType}{#each Object.entries(resource.fields) as [field, value]}<div><code>{field}</code>: {JSON.stringify(value)}</div>{/each}<small>{resource.identity.canonical.canonicalId} · {resource.provenance?.assertion ? 'shared assertion contribution' : resource.provenance?.referenceOnly ? 'reference only' : resource.steward ? 'steward' : 'contributor'}</small>{#if resource.provenance}<details><summary>Source evidence and rule</summary><pre>{JSON.stringify(resource.provenance, null, 2)}</pre></details>{/if}</div>{/each}{/if}
  </Panel>
  <Panel title="Reconciliation status" subtitle="Ambiguous writes require verification. Remote resources are retained when a model contribution is withdrawn.">
    <div class="controls"><span>Policy version {state?.policy?.version || 0} · {state?.policy?.policy?.mode || 'DISABLED'}</span><strong>{state?.job?.state || 'No job'}</strong><span>{state?.job?.code || ''}</span><button disabled={busy || state?.policy?.policy?.mode === 'DISABLED'} on:click={run}>{pendingRun ? 'Retry queue request' : 'Queue reconciliation'}</button></div>
    {#if state?.resources?.length}<table><thead><tr><th>Entity</th><th>State</th><th>Diagnostic</th><th>Review</th></tr></thead><tbody>{#each state.resources as resource}<tr><td>{name(resource.entityId)}</td><td>{resource.state}</td><td>{resource.code || '—'}</td><td><button class="secondary" disabled={busy || resource.state?.startsWith('REFERENCE_')} on:click={() => inspect(resource)}>Inspect baseline</button></td></tr>{/each}</tbody></table>{/if}
    <label>Remote resource ID for an ambiguous creation <input bind:value={remoteId} disabled={busy} placeholder="Supply the verified remote ID when no mapping is available" /></label>
    {#if inspected}<h3>Remote baseline review</h3><p>{inspected.canonicalId} · {inspected.resourceId}</p><div class="selection"><div><strong>This model’s desired contribution</strong><pre>{JSON.stringify(inspected.desired, null, 2)}</pre></div><div><strong>Current remote values</strong><pre>{JSON.stringify(inspected.remote, null, 2)}</pre></div></div><button disabled={busy} on:click={adopt}>Approve this baseline</button>{/if}
  </Panel>
{/if}

<style>
  .controls { display:flex; flex-wrap:wrap; align-items:end; gap:1rem; margin-bottom:1rem; }
  label { display:flex; gap:0.5rem; align-items:center; } select, input { max-width:100%; }
  .controls label { display:grid; } .selection { display:grid; grid-template-columns:1fr 1fr; gap:1.5rem; }
  .choice { display:flex; flex-wrap:wrap; justify-content:space-between; gap:0.5rem; padding:0.6rem 0; border-bottom:1px solid var(--line); }
  small { color:var(--text-muted); } .projection { padding:1rem 0; border-top:1px solid var(--line); }
  table { width:100%; border-collapse:collapse; margin-bottom:1rem; } th, td { text-align:left; padding:0.6rem; border-bottom:1px solid var(--line); }
  pre { white-space:pre-wrap; overflow-wrap:anywhere; } @media(max-width:800px) { .selection { grid-template-columns:1fr; } }
</style>
