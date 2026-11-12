<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchModelWindow, fetchOverview } from '$lib/api/models.js';

  let overview = [];
  let selectedWindow = null;
  let pageStatus = 'Loading sessions...';
  let copiedAt = '';

  const writableCount = (window) => (window?.activeSessions || []).filter((session) => session.writable).length;
  const readonlyCount = (window) => (window?.activeSessions || []).filter((session) => !session.writable).length;

  const refresh = async () => {
    pageStatus = 'Refreshing session diagnostics...';
    try {
      overview = await fetchOverview(100);
      if (!$selectedModelId && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      selectedWindow = $selectedModelId ? await fetchModelWindow($selectedModelId, 25) : null;
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const copySessions = async () => {
    if (!selectedWindow) {
      pageStatus = 'Select a model first.';
      return;
    }
    const payload = {
      modelId: selectedWindow.modelId,
      modelName: selectedWindow.modelName,
      activeSessionCount: selectedWindow.activeSessionCount,
      activeSessions: selectedWindow.activeSessions || []
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(payload, null, 2));
      copiedAt = new Date().toLocaleTimeString();
      pageStatus = `Session payload copied at ${copiedAt}`;
    } catch (err) {
      pageStatus = `Copy failed: ${err.message}`;
    }
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Sessions"
  title="Live websocket sessions."
  description="Choose a model, then inspect who is connected, which ref they joined, and whether they can write."
>
  <button class="secondary" on:click={refresh}>Refresh Sessions</button>
  <button class="ghost" on:click={copySessions} disabled={!selectedWindow}>Copy Session JSON</button>
</PageHero>

<StatusBanner message={pageStatus} />

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="Pick the model whose live Archimesh state you want to inspect."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Selected Model Session Summary" subtitle="Current live session counts for the selected model.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Name</strong><span>{selectedWindow.modelName || 'n/a'}</span></div>
        <div class="line"><strong>Total sessions</strong><span>{selectedWindow.activeSessionCount || 0}</span></div>
        <div class="line"><strong>Writable HEAD sessions</strong><span>{writableCount(selectedWindow)}</span></div>
        <div class="line"><strong>Read-only tag sessions</strong><span>{readonlyCount(selectedWindow)}</span></div>
        <div class="line"><strong>Snapshot head</strong><span>{selectedWindow.snapshotHeadRevision ?? 0}</span></div>
        <div class="line"><strong>Latest tag</strong><span>{selectedWindow.tagSummary?.latestTagName || 'none'}</span></div>
        {#if copiedAt}
          <div class="line"><strong>Last copy</strong><span>{copiedAt}</span></div>
        {/if}
      </div>
    {:else}
      <div class="empty">Select a model to inspect active sessions.</div>
    {/if}
  </Panel>

  <Panel title="Active Sessions" subtitle="Each card shows who joined, what they joined, and whether they can write.">
    {#if !selectedWindow || !selectedWindow.activeSessions || selectedWindow.activeSessions.length === 0}
      <div class="empty">No active joined sessions for the selected model.</div>
    {:else}
      <div class="cards">
        {#each selectedWindow.activeSessions as session}
          <article class="session-card">
            <div class="card-top">
              <strong>{session.userId || 'anonymous'}</strong>
              {#if session.writable}
                <StatusPill mode="open" label="writable" />
              {:else}
                <StatusPill mode="acl" label="read-only" />
              {/if}
            </div>
            <div class="meta-row"><span>WebSocket</span><code>{session.websocketSessionId}</code></div>
            <div class="meta-row"><span>Joined ref</span><code>{session.ref || 'HEAD'}</code></div>
            <div class="meta-row"><span>Roles</span><span>{session.normalizedRoles?.length ? session.normalizedRoles.join(', ') : 'none'}</span></div>
          </article>
        {/each}
      </div>
    {/if}
  </Panel>
</SplitView>

<style>
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  .line,
  .meta-row {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid var(--line);
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 0.9rem;
  }
  .session-card {
    padding: 0.95rem;
    border-radius: 0.9rem;
    border: 1px solid var(--line);
    background: var(--bg-alt);
  }
  .card-top {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: center;
    margin-bottom: 0.8rem;
  }
  code {
    color: var(--accent);
    word-break: break-all;
  }
  .empty { color: var(--text-muted); }
</style>
