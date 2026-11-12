<script>
  import Panel from '$lib/components/Panel.svelte';
  import { authRoles, authToken, authUiConfig, authUser } from '$lib/stores/auth.js';

  export let title = 'Identity Input';
  export let subtitle = 'Inputs are shown according to the server identity mode.';
</script>

<Panel {title} {subtitle}>
  {#if !$authUiConfig.loaded}
    <div class="message">Loading server identity mode...</div>
  {:else if !$authUiConfig.authorizationEnabled}
    <div class="message">
      <strong>Authorization disabled.</strong>
      <span>The server is not enforcing identity, so these inputs are currently ignored.</span>
    </div>
  {:else if $authUiConfig.identityMode === 'oidc'}
    <div class="field-grid single">
      <label>
        <span>Bearer Token</span>
        <textarea rows="4" bind:value={$authToken} placeholder="JWT token for oidc mode"></textarea>
      </label>
    </div>
  {:else if $authUiConfig.identityMode === 'bootstrap'}
    <div class="field-grid">
      <label>
        <span>Bootstrap User</span>
        <input bind:value={$authUser} placeholder="admin-user" />
      </label>
      <label>
        <span>Bootstrap Roles</span>
        <input bind:value={$authRoles} placeholder="admin,model_writer" />
      </label>
    </div>
  {:else}
    <div class="message">
      <strong>Proxy identity mode.</strong>
      <span>The server expects trusted forwarded identity headers from a reverse proxy. Browser-side auth inputs are not used.</span>
    </div>
  {/if}
</Panel>

<style>
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
  .message {
    display: grid;
    gap: 0.3rem;
    padding: 0.95rem 1rem;
    border-radius: 0.9rem;
    background: var(--bg-alt);
    color: var(--text-soft);
  }
  .message strong {
    color: var(--text);
  }
  @media (max-width: 1000px) {
    .field-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
