<script>
  import { onMount } from 'svelte';
  import { get } from 'svelte/store';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import AuthInputPanel from '$lib/components/AuthInputPanel.svelte';
  import { authSummary, authToken, authUiConfig, pollSeconds } from '$lib/stores/auth.js';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchModelWindow, fetchOverview } from '$lib/api/models.js';
  import { describeTokenIdentity, describeTokenStatus, refreshAuthDiagnostics } from '$lib/api/auth.js';
  import { safe } from '$lib/api/client.js';

  let overview = [];
  let selectedWindow = null;
  let status = 'Loading overview...';
  let loading = false;
  let timer = null;
  let limit = 25;
  let accessFilter = 'all';

  const filterOverview = (rows) => rows.filter((row) => {
    if (accessFilter === 'acl') return Boolean(row.accessSummary?.aclConfigured);
    if (accessFilter === 'open') return !row.accessSummary?.aclConfigured;
    return true;
  });

  const refresh = async () => {
    loading = true;
    status = 'Refreshing overview...';
    try {
      const payload = await fetchOverview(limit);
      overview = filterOverview(payload || []);
      const currentSelection = get(selectedModelId);
      if (!currentSelection && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      const modelId = get(selectedModelId);
      selectedWindow = modelId ? await fetchModelWindow(modelId, limit) : null;
      status = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      status = err.message;
    } finally {
      loading = false;
    }
  };

  const restartPolling = () => {
    if (timer) clearInterval(timer);
    const seconds = Math.max(0, Math.min(300, parseInt(get(pollSeconds) || '4', 10) || 0));
    pollSeconds.set(String(seconds));
    if (seconds > 0) timer = setInterval(refresh, seconds * 1000);
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const runAuthCheck = async () => {
    try {
      await refreshAuthDiagnostics();
      status = `Auth resolved at ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      status = err.message;
    }
  };

  const handlePollChange = () => {
    restartPolling();
    status = get(pollSeconds) === '0'
      ? 'Auto-refresh disabled.'
      : `Auto-refresh every ${get(pollSeconds)}s.`;
  };

  $: tokenStatus = describeTokenStatus($authToken);
  $: tokenIdentity = describeTokenIdentity($authToken);
  $: inputModeSummary =
    !$authUiConfig.loaded ? 'Loading server identity mode...'
      : !$authUiConfig.authorizationEnabled ? 'Authorization disabled; browser auth inputs are ignored.'
      : $authUiConfig.identityMode === 'oidc' ? tokenStatus
      : $authUiConfig.identityMode === 'bootstrap' ? 'Bootstrap user and role headers are active for this browser session.'
      : 'Trusted proxy identity mode; browser auth inputs are ignored.';
  $: inputDetailSummary =
    !$authUiConfig.loaded ? 'Waiting for /admin-ui/config'
      : !$authUiConfig.authorizationEnabled ? 'No token or bootstrap fields are required.'
      : $authUiConfig.identityMode === 'oidc' ? tokenIdentity
      : $authUiConfig.identityMode === 'bootstrap' ? 'Use Bootstrap User and Bootstrap Roles when the server runs in bootstrap mode.'
      : 'Identity must be provided by the configured reverse proxy.';

  onMount(async () => {
    restartPolling();
    await refresh();
    return () => {
      if (timer) clearInterval(timer);
    };
  });
</script>

<PageHero
  eyebrow="Overview"
  title="Server overview and model summary."
  description="Set shared auth and refresh options here, then inspect one selected model at a time."
>
  <button class="secondary" on:click={runAuthCheck}>Check Identity</button>
  <button class="primary" on:click={refresh} disabled={loading}>{loading ? 'Refreshing...' : 'Refresh Overview'}</button>
</PageHero>

<StatusBanner message={status} />

<div class="top-grid">
  <Panel title="Shared Controls" subtitle="These settings affect the whole overview route.">
    <div class="field-grid">
      <label>
        <span>Poll Seconds</span>
        <input type="number" min="0" max="300" bind:value={$pollSeconds} on:change={handlePollChange} />
      </label>
      <label>
        <span>Overview Limit</span>
        <input type="number" min="1" max="200" bind:value={limit} />
      </label>
      <label>
        <span>Access Filter</span>
        <select bind:value={accessFilter} on:change={refresh}>
          <option value="all">all</option>
          <option value="open">open</option>
          <option value="acl">acl</option>
        </select>
      </label>
    </div>
    <div class="hint-grid">
      <div><strong>Input mode</strong><span>{inputModeSummary}</span></div>
      <div><strong>Mode details</strong><span>{inputDetailSummary}</span></div>
      <div><strong>Resolved identity</strong><span>{$authSummary}</span></div>
    </div>
  </Panel>

  <AuthInputPanel
    title="Identity Input"
    subtitle="Only the input type relevant to the running server mode is shown."
  />
</div>

<div class="top-grid guidance-grid">
  <Panel title="What This Page Shows" subtitle="Use this page to choose a model and check its current server-side state.">
    <div class="hint-grid compact">
      <div><strong>Select</strong><span>Pick a model in the left navigator.</span></div>
      <div><strong>Inspect</strong><span>Read the current summary on the right.</span></div>
      <div><strong>Go deeper</strong><span>Use the other tabs for changes, tags, access, or live sessions.</span></div>
    </div>
  </Panel>
</div>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="The current selection stays active across tabs."
      emptyMessage="No models match the current filter."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Selected Model" subtitle="Current server-side summary for the selected model.">
    {#if selectedWindow}
      <div class="summary-stack">
        <div class="summary-row"><span>Model</span><strong>{selectedWindow.modelId}</strong></div>
        <div class="summary-row"><span>Name</span><strong>{selectedWindow.modelName || 'n/a'}</strong></div>
        <div class="summary-row"><span>Access</span><span>{#if selectedWindow.accessSummary?.aclConfigured}<StatusPill mode="acl" label="acl" />{:else}<StatusPill mode="open" label="open" />{/if}</span></div>
        <div class="summary-row"><span>Tags</span><strong>{safe(selectedWindow.tagSummary?.tagCount || 0)}</strong></div>
        <div class="summary-row"><span>Latest Tag</span><strong>{selectedWindow.tagSummary?.latestTagName || 'none'}</strong></div>
        <div class="summary-row"><span>Sessions</span><strong>{safe(selectedWindow.activeSessionCount || 0)}</strong></div>
        <div class="summary-row"><span>Snapshot Head</span><strong>{safe(selectedWindow.snapshotHeadRevision)}</strong></div>
        <div class="summary-row"><span>Persisted Head</span><strong>{safe(selectedWindow.persistedHeadRevision)}</strong></div>
        <div class="summary-row"><span>Last Commit</span><strong>{safe(selectedWindow.lastCommitRevision)}</strong></div>
        <div class="summary-row"><span>Consistency</span><strong>{String(selectedWindow.integrity?.consistent ?? false)}</strong></div>
      </div>
    {:else}
      <p class="empty">Select a model to see its focused status.</p>
    {/if}
  </Panel>
</SplitView>

<style>
  .top-grid { display:grid; grid-template-columns: 1.3fr 0.9fr; gap:1rem; }
  .field-grid { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:0.9rem; }
  .guidance-grid { grid-template-columns: 1fr; }
  label { display:flex; flex-direction:column; gap:0.45rem; color:var(--text-soft); }
  .hint-grid { margin-top:1rem; display:grid; gap:0.7rem; }
  .hint-grid div { display:grid; gap:0.18rem; padding:0.8rem 0.9rem; border-radius:0.9rem; background:var(--bg-alt); }
  .hint-grid strong { font-size:0.8rem; letter-spacing:0.08rem; text-transform:uppercase; color:var(--text-muted); }
  .compact { margin-top:0; }
  .summary-stack { display:grid; gap:0.7rem; }
  .summary-row { display:flex; justify-content:space-between; gap:1rem; align-items:center; padding-bottom:0.45rem; border-bottom:1px solid var(--line); }
  .summary-row span:first-child { color:var(--text-muted); }
  .empty { color:var(--text-muted); }
  @media (max-width: 1000px) {
    .top-grid, .field-grid { grid-template-columns:1fr; }
  }
</style>
