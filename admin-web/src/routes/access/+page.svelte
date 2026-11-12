<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import AuthInputPanel from '$lib/components/AuthInputPanel.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { authSummary, authToken, authUiConfig } from '$lib/stores/auth.js';
  import { describeTokenIdentity, describeTokenStatus, refreshAuthDiagnostics } from '$lib/api/auth.js';
  import { fetchModelAcl, fetchOverview, saveModelAcl } from '$lib/api/models.js';

  let overview = [];
  let accessSummary = null;
  let authStatus = '';
  let pageStatus = 'Loading access controls...';
  let adminUsers = '';
  let writerUsers = '';
  let readerUsers = '';

  const formatUsers = (values) => (Array.isArray(values) ? values.join('\n') : '');
  const parseUsers = (raw) => Array.from(new Set(
    String(raw || '')
      .split(/[\n,]/)
      .map((value) => value.trim())
      .filter(Boolean)
  ));

  const loadOverview = async () => {
    overview = await fetchOverview(100);
    if (!$selectedModelId && overview.length) {
      selectedModelId.set(overview[0].modelId);
    }
    accessSummary = overview.find((row) => row.modelId === $selectedModelId)?.accessSummary || null;
  };

  const loadAcl = async () => {
    if (!$selectedModelId) {
      adminUsers = '';
      writerUsers = '';
      readerUsers = '';
      accessSummary = null;
      return;
    }
    const acl = await fetchModelAcl($selectedModelId);
    adminUsers = formatUsers(acl.adminUsers);
    writerUsers = formatUsers(acl.writerUsers);
    readerUsers = formatUsers(acl.readerUsers);
    accessSummary = overview.find((row) => row.modelId === $selectedModelId)?.accessSummary || null;
  };

  const refreshAll = async () => {
    pageStatus = 'Refreshing access data...';
    try {
      await loadOverview();
      await loadAcl();
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const runAuthCheck = async () => {
    try {
      await refreshAuthDiagnostics();
      authStatus = `Resolved ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      authStatus = err.message;
    }
  };

  const persistAcl = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Saving ACL for ${$selectedModelId}...`;
    try {
      await saveModelAcl($selectedModelId, {
        adminUsers: parseUsers(adminUsers),
        writerUsers: parseUsers(writerUsers),
        readerUsers: parseUsers(readerUsers)
      });
      await refreshAll();
      pageStatus = `ACL updated for ${$selectedModelId}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await loadAcl();
    pageStatus = `Selected ${row.modelId}`;
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

  onMount(refreshAll);
</script>

<PageHero
  eyebrow="Access"
  title="Identity checks and model access lists."
  description="Verify who you are, then edit access for one selected model."
>
  <button class="secondary" on:click={runAuthCheck}>Check Identity</button>
  <button class="primary" on:click={refreshAll}>Refresh Access</button>
</PageHero>

<StatusBanner message={pageStatus} />

<div class="grid top-grid">
  <Panel title="Current Identity" subtitle="Who the server currently sees for this browser session.">
    <div class="stack">
      <div class="line"><strong>Resolved identity</strong><span>{$authSummary}</span></div>
      <div class="line"><strong>Input mode</strong><span>{inputModeSummary}</span></div>
      <div class="line"><strong>Mode details</strong><span>{inputDetailSummary}</span></div>
      {#if authStatus}
        <div class="line"><strong>Last auth check</strong><span>{authStatus}</span></div>
      {/if}
    </div>
  </Panel>

  <AuthInputPanel
    title="Identity Input"
    subtitle="Only the input type relevant to the running server mode is shown."
  />
</div>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="Choose the model whose access list you want to edit."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Access List" subtitle="These entries apply only to the selected model.">
    <div class="stack">
      <div class="line">
        <strong>Selected model</strong>
        <span>{$selectedModelId || 'none'}</span>
      </div>
      <div class="line">
        <strong>Access mode</strong>
        <span>
          {#if accessSummary?.aclConfigured}
            <StatusPill mode="acl" label="acl" />
          {:else}
            <StatusPill mode="open" label="open" />
          {/if}
        </span>
      </div>
      <div class="field-grid single">
        <label>
          <span>Model admins</span>
          <textarea rows="5" bind:value={adminUsers} placeholder="owner-user&#10;backup-admin"></textarea>
        </label>
        <label>
          <span>Writers</span>
          <textarea rows="5" bind:value={writerUsers} placeholder="editor-a&#10;editor-b"></textarea>
        </label>
        <label>
          <span>Readers</span>
          <textarea rows="5" bind:value={readerUsers} placeholder="viewer-a&#10;viewer-b"></textarea>
        </label>
      </div>
      <div class="actions">
        <button class="primary" on:click={persistAcl} disabled={!$selectedModelId}>Save Access List</button>
      </div>
    </div>
  </Panel>
</SplitView>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.9rem;
  }
  .field-grid.single {
    grid-template-columns: 1fr;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    color: var(--text-soft);
  }
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid var(--line);
  }
  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
  }
  @media (max-width: 1000px) {
    .grid,
    .field-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
