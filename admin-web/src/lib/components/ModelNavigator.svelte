<script>
  import Panel from '$lib/components/Panel.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';

  export let rows = [];
  export let selectedId = '';
  export let title = 'Models';
  export let subtitle = 'Select a model to focus this route.';
  export let emptyMessage = 'No models available.';
  export let onSelect = () => {};

  let filter = '';

  $: normalized = String(filter || '').trim().toLowerCase();
  $: visibleRows = rows.filter((row) => {
    if (!normalized) return true;
    return [row.modelId, row.modelName, row.tagSummary?.latestTagName]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(normalized));
  });
</script>

<Panel {title} {subtitle}>
  <div class="navigator">
    <label class="search">
      <span>Filter</span>
      <input bind:value={filter} placeholder="model id, name, tag..." />
    </label>

    <div class="items">
      {#if visibleRows.length === 0}
        <div class="empty">{emptyMessage}</div>
      {:else}
        {#each visibleRows as row}
          <button
            class:selected={row.modelId === selectedId}
            class="item"
            on:click={() => onSelect(row)}
          >
            <div class="item-top">
              <strong>{row.modelId}</strong>
              {#if row.accessSummary?.aclConfigured}
                <StatusPill mode="acl" label="acl" />
              {:else}
                <StatusPill mode="open" label="open" />
              {/if}
            </div>
            <div class="item-name">{row.modelName || 'Unnamed model'}</div>
            <div class="item-meta">
              <span>{row.activeSessionCount || 0} sessions</span>
              <span>{row.tagSummary?.tagCount || 0} tags</span>
              <span>head {row.snapshotHeadRevision ?? row.persistedHeadRevision ?? 0}</span>
            </div>
          </button>
        {/each}
      {/if}
    </div>
  </div>
</Panel>

<style>
  .navigator {
    display: grid;
    gap: 1rem;
  }

  .search {
    display: grid;
    gap: 0.4rem;
    color: var(--text-soft);
  }

  .items {
    display: grid;
    gap: 0.65rem;
  }

  .item {
    width: 100%;
    display: grid;
    gap: 0.55rem;
    text-align: left;
    padding: 0.9rem 1rem;
    border-radius: 1rem;
    background: var(--bg-alt);
    border-color: var(--line);
  }

  .item:hover,
  .item.selected {
    border-color: var(--accent-line);
    background: var(--accent-soft);
  }

  .item-top {
    display: flex;
    justify-content: space-between;
    gap: 0.8rem;
    align-items: center;
  }

  .item-name {
    color: var(--text-soft);
  }

  .item-meta {
    display: flex;
    gap: 0.8rem;
    flex-wrap: wrap;
    color: var(--text-muted);
    font-size: 0.84rem;
  }

  .empty {
    color: var(--text-muted);
    padding: 0.4rem 0;
  }
</style>
