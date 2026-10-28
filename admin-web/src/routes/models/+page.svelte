<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import {
    compactModel,
    createModelCheckpoint,
    fetchModelCheckpoint,
    createModel,
    deleteModel,
    fetchModelWindow,
    fetchOverview,
    rebuildModel,
    renameModel
  } from '$lib/api/models.js';

  let overview = [];
  let selectedWindow = null;
  let pageStatus = 'Loading models...';
  let newModelId = '';
  let newModelName = '';
  let renameValue = '';
  let compactRetain = '0';
  let deleteForce = false;
  let checkpoint = null;
  let compactionResult = null;
  let maintenanceBusy = false;

  $: visibleCompaction = compactionResult?.modelId === $selectedModelId
    && Number(compactionResult.retainRevisions) === Number(compactRetain || 0) ? compactionResult : null;

  const refresh = async () => {
    pageStatus = 'Refreshing model lifecycle data...';
    try {
      overview = await fetchOverview(100);
      if (!$selectedModelId && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      if ($selectedModelId) {
        [selectedWindow, checkpoint] = await Promise.all([
          fetchModelWindow($selectedModelId, 25), fetchModelCheckpoint($selectedModelId)
        ]);
        renameValue = selectedWindow?.modelName || '';
      } else {
        selectedWindow = null;
        checkpoint = null;
      }
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const submitCreate = async () => {
    if (!newModelId.trim()) {
      pageStatus = 'New model id is required.';
      return;
    }
    pageStatus = `Creating ${newModelId}...`;
    try {
      await createModel(newModelId.trim(), newModelName.trim());
      selectedModelId.set(newModelId.trim());
      newModelId = '';
      newModelName = '';
      await refresh();
      pageStatus = `Model ${$selectedModelId} created`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRename = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Renaming ${$selectedModelId}...`;
    try {
      await renameModel($selectedModelId, renameValue.trim());
      await refresh();
      pageStatus = `Model ${$selectedModelId} renamed`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRebuild = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Rebuilding ${$selectedModelId}...`;
    try {
      await rebuildModel($selectedModelId);
      await refresh();
      pageStatus = `Model ${$selectedModelId} rebuilt`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitCheckpoint = async () => {
    if (!$selectedModelId) return;
    maintenanceBusy = true;
    pageStatus = `Creating recovery checkpoint for ${$selectedModelId}...`;
    try {
      const result = await createModelCheckpoint($selectedModelId);
      compactionResult = null;
      await refresh();
      pageStatus = `Checkpoint created at revision ${result.revision}. Existing checkpoints are immutable.`;
    } catch (err) { pageStatus = err.message; }
    finally { maintenanceBusy = false; }
  };

  const submitCompact = async (dryRun) => {
    if (!$selectedModelId) return;
    maintenanceBusy = true;
    pageStatus = dryRun ? 'Previewing payload pruning...' : 'Pruning checkpoint-covered payloads...';
    try {
      const result = await compactModel($selectedModelId, compactRetain || '0', dryRun);
      compactionResult = result;
      if (!dryRun) await refresh();
      pageStatus = result.note;
    } catch (err) { pageStatus = err.message; }
    finally { maintenanceBusy = false; }
  };

  const submitDelete = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Deleting ${$selectedModelId}...`;
    try {
      const target = $selectedModelId;
      const result = await deleteModel(target, deleteForce);
      if (result.deleted) {
        selectedModelId.set('');
      }
      await refresh();
      pageStatus = result.message || `Delete completed for ${target}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Models"
  title="Create, rename, maintain, or delete models."
  description="Choose a model on the left, then use the panels on the right to change only that model."
>
  <button class="secondary" on:click={refresh}>Refresh Models</button>
</PageHero>

<StatusBanner message={pageStatus} />

<div class="top-grid">
  <Panel title="Create Model" subtitle="Create a new empty model before anyone joins it.">
    <div class="stack">
      <label>
        <span>Model ID</span>
        <input bind:value={newModelId} placeholder="demo-model" />
      </label>
      <label>
        <span>Model Name</span>
        <input bind:value={newModelName} placeholder="Demo Model" />
      </label>
      <div class="actions">
        <button class="primary" on:click={submitCreate}>Create</button>
      </div>
    </div>
  </Panel>

  <Panel title="Action Groups" subtitle="Each panel below changes one aspect of the selected model.">
    <div class="stack help">
      <div class="line"><strong>Name</strong><span>Update the displayed model name.</span></div>
      <div class="line"><strong>Maintenance</strong><span>Rebuild or compact persisted state.</span></div>
      <div class="line"><strong>Delete</strong><span>Remove the selected model and its data.</span></div>
    </div>
  </Panel>
</div>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="Choose the model you want to change."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Selected Model Status" subtitle="Current state for the selected model.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Name</strong><span>{selectedWindow.modelName || 'n/a'}</span></div>
        <div class="line"><strong>Access</strong><span>{#if selectedWindow.accessSummary?.aclConfigured}<StatusPill mode="acl" label="acl" />{:else}<StatusPill mode="open" label="open" />{/if}</span></div>
        <div class="line"><strong>Snapshot Head</strong><span>{selectedWindow.snapshotHeadRevision}</span></div>
        <div class="line"><strong>Persisted Head</strong><span>{selectedWindow.persistedHeadRevision}</span></div>
        <div class="line"><strong>Last Commit</strong><span>{selectedWindow.lastCommitRevision}</span></div>
        <div class="line"><strong>Recent Activity</strong><span>{selectedWindow.recentActivity?.length || 0}</span></div>
        <div class="line"><strong>Recent Op Batches</strong><span>{selectedWindow.recentOpBatches?.length || 0}</span></div>
        <div class="line"><strong>Integrity</strong><span>{String(selectedWindow.integrity?.consistent ?? false)}</span></div>
      </div>
    {:else}
      <div class="empty">Select a model to see focused status.</div>
    {/if}
  </Panel>

  <div class="grid">
    <Panel title="Rename Model" subtitle="Change the display name for the selected model.">
      <div class="stack">
        <label>
          <span>Model name</span>
          <input bind:value={renameValue} placeholder="Selected model name" />
        </label>
        <div class="actions">
          <button class="primary" on:click={submitRename} disabled={!$selectedModelId}>Save Name</button>
        </div>
      </div>
    </Panel>

    <Panel title="Maintenance" subtitle="Create a recovery checkpoint, preview pruning, then reclaim covered operation payloads. Acceptance receipts and merge metadata are retained.">
      <div class="stack">
        <div class="line"><strong>Latest checkpoint</strong><span>{checkpoint ? `Revision ${checkpoint.revision}` : 'No checkpoint yet'}</span></div>
        {#if checkpoint}
          <div class="line"><strong>Checkpoint created</strong><span>{checkpoint.createdAt}</span></div>
        {/if}
        <label>
          <span>Recent revisions to retain as operation payloads</span>
          <input type="number" min="0" bind:value={compactRetain} />
        </label>
        <div class="actions">
          <button class="secondary" on:click={submitRebuild} disabled={!$selectedModelId || maintenanceBusy}>Rebuild State</button>
          <button class="secondary" on:click={submitCheckpoint} disabled={!$selectedModelId || maintenanceBusy}>Create Checkpoint</button>
          <button class="secondary" on:click={() => submitCompact(true)} disabled={!$selectedModelId || maintenanceBusy}>Preview Pruning</button>
          <button class="primary" on:click={() => submitCompact(false)} disabled={!$selectedModelId || !checkpoint || maintenanceBusy}>Prune Covered Payloads</button>
        </div>
        {#if visibleCompaction}
          <div class="line"><strong>Run</strong><span>{visibleCompaction.dryRun ? 'Preview only' : 'Applied'}</span></div>
          <div class="line"><strong>Eligible batches before run</strong><span>{visibleCompaction.eligibleBatchCount}</span></div>
          <div class="line"><strong>Pruned batches / operations</strong><span>{visibleCompaction.prunedBatchCount} / {visibleCompaction.deletedOpCount}</span></div>
          <div class="line"><strong>Pruned through revision</strong><span>{visibleCompaction.prunedThroughRevision}</span></div>
          <p class="help">{visibleCompaction.note}</p>
        {/if}
        <p class="help">Pruning runs in bounded batches and pauses before unpublished changes. Repeat a run to reclaim remaining eligible payloads. Individual operations pruned from history remain available only in a separate archive.</p>
      </div>
    </Panel>
  </div>

  <Panel title="Delete Model" subtitle="This permanently removes the selected model.">
    <div class="stack">
      <label class="checkbox">
        <input type="checkbox" bind:checked={deleteForce} />
        <span>Force delete even when sessions are active</span>
      </label>
      <div class="actions">
        <button class="danger" on:click={submitDelete} disabled={!$selectedModelId}>Delete Model</button>
      </div>
    </div>
  </Panel>
</SplitView>

<style>
  .top-grid,
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    color: var(--text-soft);
  }
  .checkbox {
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
    gap: 0.7rem;
  }
  .checkbox input {
    width: auto;
    flex: 0 0 auto;
  }
  .checkbox span {
    flex: 0 1 auto;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }
  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
  }
  .help .line {
    border-bottom: none;
    padding-bottom: 0;
  }
  .empty { color: var(--text-muted); }
  @media (max-width: 1000px) {
    .top-grid,
    .grid {
      grid-template-columns: 1fr;
    }
  }
</style>
