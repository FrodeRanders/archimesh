<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusBanner from '$lib/components/StatusBanner.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import { fetchAuditConfig } from '$lib/api/models.js';

  let config = null;
  let pageStatus = 'Loading audit configuration...';
  let copiedAt = '';

  const refresh = async () => {
    pageStatus = 'Refreshing audit configuration...';
    try {
      config = await fetchAuditConfig();
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const copyConfig = async () => {
    if (!config) {
      pageStatus = 'No audit configuration loaded.';
      return;
    }
    try {
      await navigator.clipboard.writeText(JSON.stringify(config, null, 2));
      copiedAt = new Date().toLocaleTimeString();
      pageStatus = `Audit configuration copied at ${copiedAt}`;
    } catch (err) {
      pageStatus = `Copy failed: ${err.message}`;
    }
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Audit"
  title="Audit settings and operator guidance."
  description="Inspect the active audit configuration, then read the guidance next to it."
>
  <button class="secondary" on:click={refresh}>Refresh Audit Settings</button>
  <button class="ghost" on:click={copyConfig} disabled={!config}>Copy Audit JSON</button>
</PageHero>

<StatusBanner message={pageStatus} />

<SplitView>
  <svelte:fragment slot="sidebar">
    <Panel title="Current Audit Settings" subtitle="Effective audit-related runtime values exposed by the server.">
      {#if config}
        <div class="stack">
          <div class="line"><strong>Identity mode</strong><span>{config.identityMode}</span></div>
          <div class="line"><strong>Authorization enabled</strong><span>{String(config.authorizationEnabled)}</span></div>
          <div class="line"><strong>WebSocket verbose audit</strong><span>{String(config.websocketAuditVerbose)}</span></div>
          <div class="line column">
            <strong>WebSocket audit actions</strong>
            <div class="chips">
              {#if config.websocketAuditActions.length === 0}
                <span class="empty">disabled</span>
              {:else}
                {#each config.websocketAuditActions as action}
                  <code>{action}</code>
                {/each}
              {/if}
            </div>
          </div>
          {#if copiedAt}
            <div class="line"><strong>Last copy</strong><span>{copiedAt}</span></div>
          {/if}
        </div>
      {:else}
        <div class="empty">Audit configuration is not available yet.</div>
      {/if}
    </Panel>
  </svelte:fragment>

  <div class="grid">
    <Panel title="What These Settings Mean" subtitle="How to interpret the current audit settings.">
      <div class="stack">
        <div class="guide">
          <strong>`admin_audit`</strong>
          <span>Machine-readable JSON for diagnostics access and mutating admin actions. Route this separately if operators depend on it.</span>
        </div>
        <div class="guide">
          <strong>`ws_audit`</strong>
          <span>Lifecycle-oriented websocket trail. Keep the action filter narrow unless you need broad session visibility.</span>
        </div>
        <div class="guide">
          <strong>Verbose mode</strong>
          <span>`app.audit.websocket.verbose=true` also emits accepted websocket messages and increases log volume noticeably.</span>
        </div>
      </div>
    </Panel>

    <Panel title="Retention and Shipping" subtitle="Operational guidance that follows from the current settings.">
      <div class="stack">
        <div class="guide">
          <strong>Retention</strong>
          <span>Keep audit retention shorter than ordinary application logs if dashboard polling is frequent.</span>
        </div>
        <div class="guide">
          <strong>Shipping</strong>
          <span>Use the example Vector configuration if you want `admin_audit` and `ws_audit` split into dedicated sinks.</span>
        </div>
        <div class="guide">
          <strong>Noise control</strong>
          <span>Disable websocket audit entirely by clearing `app.audit.websocket.actions`, or keep verbose mode off in ordinary operation.</span>
        </div>
      </div>
    </Panel>
  </div>
</SplitView>

<style>
  .grid {
    display: grid;
    gap: 1rem;
  }
  .stack {
    display: grid;
    gap: 0.85rem;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid var(--line);
  }
  .line.column {
    display: grid;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 0.55rem;
    margin-top: 0.5rem;
  }
  code {
    color: var(--accent);
    border: 1px solid var(--line);
    background: var(--bg-alt);
    border-radius: 999px;
    padding: 0.2rem 0.55rem;
  }
  .guide {
    display: grid;
    gap: 0.25rem;
    padding: 0.9rem;
    border-radius: 0.9rem;
    border: 1px solid var(--line);
    background: var(--bg-alt);
  }
  .guide strong {
    color: var(--text);
  }
  .guide span,
  .empty {
    color: var(--text-muted);
  }
</style>
