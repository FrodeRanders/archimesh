<script>
  export let message = '';

  const classify = (value) => {
    const text = String(value || '').toLowerCase();
    if (!text.trim()) return 'info';
    if (text.includes('http 401') || text.includes('http 403') || text.includes('unauthorized') || text.includes('forbidden') || text.includes('failed') || text.includes('error') || text.includes('missing') || text.includes('invalid') || text.includes('expired')) {
      return 'error';
    }
    if (text.includes('loading') || text.includes('refreshing') || text.includes('creating') || text.includes('saving') || text.includes('deleting') || text.includes('compacting') || text.includes('rebuilding') || text.includes('importing') || text.includes('exporting')) {
      return 'working';
    }
    return 'info';
  };

  $: tone = classify(message);
</script>

{#if message}
  <div class:tone-error={tone === 'error'} class:tone-working={tone === 'working'} class="banner" role={tone === 'error' ? 'alert' : 'status'}>
    <strong>{tone === 'error' ? 'Attention' : tone === 'working' ? 'Working' : 'Status'}</strong>
    <span>{message}</span>
  </div>
{/if}

<style>
  .banner {
    display: grid;
    gap: 0.2rem;
    padding: 0.95rem 1rem;
    border-radius: 1rem;
    border: 1px solid var(--line);
    background: var(--bg-alt);
    color: var(--text);
  }
  .banner strong {
    font-size: 0.8rem;
    letter-spacing: 0.08rem;
    text-transform: uppercase;
    color: var(--text-muted);
  }
  .banner span {
    color: var(--text-soft);
  }
  .banner.tone-error {
    border-color: var(--danger-line);
    background: var(--danger-soft);
  }
  .banner.tone-error strong,
  .banner.tone-error span {
    color: var(--danger);
  }
  .banner.tone-working {
    border-color: var(--accent-line);
    background: var(--accent-soft);
  }
  .banner.tone-working strong,
  .banner.tone-working span {
    color: var(--accent);
  }
</style>
