<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchOverview, fetchModelWindow } from '$lib/api/models.js';

  let overview = [];
  let selectedWindow = null;
  let pageStatus = 'Loading operations...';
  let copiedAt = '';

  const refresh = async () => {
    pageStatus = 'Refreshing recent operations...';
    try {
      overview = await fetchOverview(100);
      if (!$selectedModelId && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      if ($selectedModelId) {
        selectedWindow = await fetchModelWindow($selectedModelId, 50);
      } else {
        selectedWindow = null;
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

  const copyOperations = async () => {
    if (!selectedWindow) {
      pageStatus = 'Select a model first.';
      return;
    }
    const payload = {
      modelId: selectedWindow.modelId,
      recentActivity: selectedWindow.recentActivity || [],
      recentOpBatches: selectedWindow.recentOpBatches || []
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(payload, null, 2));
      copiedAt = new Date().toLocaleTimeString();
      pageStatus = `Operations copied at ${copiedAt}`;
    } catch (err) {
      pageStatus = `Copy failed: ${err.message}`;
    }
  };

  const opBatchSummary = (batch) => {
    const range = batch?.assignedRevisionRange;
    const from = range?.from ?? '?';
    const to = range?.to ?? '?';
    const opCount = Array.isArray(batch?.ops) ? batch.ops.length : 0;
    return `${from}..${to} (${opCount} ops)`;
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Operations"
  title="Inspect recent model activity and committed op batches."
  description="Choose a model on the left, then inspect the server-side activity trail and the most recent persisted batches on the right."
>
  <button class="secondary" on:click={refresh}>Refresh Operations</button>
  <button class="ghost" on:click={copyOperations} disabled={!selectedWindow}>Copy Operation JSON</button>
</PageHero>

<StatusBanner message={pageStatus} />

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="Choose the model whose recent operations you want to inspect."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Selected Model Operation Summary" subtitle="High-level view of recent activity and persisted batches.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Recent activity events</strong><span>{selectedWindow.recentActivity?.length || 0}</span></div>
        <div class="line"><strong>Recent op batches</strong><span>{selectedWindow.recentOpBatches?.length || 0}</span></div>
        {#if copiedAt}
          <div class="line"><strong>Last copy</strong><span>{copiedAt}</span></div>
        {/if}
      </div>
    {:else}
      <div class="empty">Select a model to inspect recent server-side operations.</div>
    {/if}
  </Panel>

  <div class="grid">
    <Panel title="Recent Activity" subtitle="Operator-facing events emitted by the Archimesh service for the selected model.">
      {#if selectedWindow?.recentActivity?.length}
        <div class="list">
          {#each selectedWindow.recentActivity as event}
            <div class="entry">
              <div class="entry-head">
                <strong>{event.type}</strong>
                <span>{event.at}</span>
              </div>
              <div class="entry-body">
                <div><strong>Model</strong> {event.modelId}</div>
                <code>{event.details}</code>
              </div>
            </div>
          {/each}
        </div>
      {:else}
        <div class="empty">No recent activity recorded for this model.</div>
      {/if}
    </Panel>

    <Panel title="Recent Op Batches" subtitle="Most recent committed batches as persisted by the server.">
      {#if selectedWindow?.recentOpBatches?.length}
        <div class="list">
          {#each selectedWindow.recentOpBatches as batch}
            <div class="entry">
              <div class="entry-head">
                <strong>{batch.opBatchId || 'opBatch'}</strong>
                <span>{opBatchSummary(batch)}</span>
              </div>
              <div class="entry-body">
                <div><strong>Base revision</strong> {batch.baseRevision}</div>
                <div><strong>Timestamp</strong> {batch.timestamp || 'n/a'}</div>
                <div class="ops">
                  {#if Array.isArray(batch.ops) && batch.ops.length}
                    {#each batch.ops as op}
                      <code>{op.type}</code>
                    {/each}
                  {:else}
                    <span class="empty-inline">No ops in batch</span>
                  {/if}
                </div>
              </div>
            </div>
          {/each}
        </div>
      {:else}
        <div class="empty">No recent op batches are available for this model.</div>
      {/if}
    </Panel>
  </div>
</SplitView>

<style>
  .grid,
  .stack,
  .list {
    display: grid;
    gap: 1rem;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid var(--line);
  }
  .entry {
    display: grid;
    gap: 0.55rem;
    padding: 0.95rem;
    border-radius: 0.95rem;
    border: 1px solid var(--line);
    background: var(--bg-alt);
  }
  .entry-head {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: baseline;
  }
  .entry-head span,
  .entry-body,
  .empty,
  .empty-inline {
    color: var(--text-muted);
  }
  .entry-body {
    display: grid;
    gap: 0.45rem;
  }
  .ops {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  code {
    color: var(--accent);
    border: 1px solid var(--line);
    background: var(--bg-alt);
    border-radius: 999px;
    padding: 0.2rem 0.55rem;
  }
  @media (max-width: 1000px) {
    .grid {
      grid-template-columns: 1fr;
    }
  }
</style>
