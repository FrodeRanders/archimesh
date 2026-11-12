<script>
  export let title = '';
  export let subtitle = '';
</script>

<section class="panel">
  <header class="panel-header">
    <div>
      <h2>{title}</h2>
      {#if subtitle}<p>{subtitle}</p>{/if}
    </div>
    <slot name="actions" />
  </header>
  <div class="panel-body">
    <slot />
  </div>
</section>

<style>
  .panel {
    background: var(--panel);
    border-radius: 1.1rem;
    border: 1px solid var(--line);
    overflow: hidden;
    box-shadow: var(--shadow-soft);
  }
  .panel-header {
    display:flex; justify-content:space-between; gap:1rem; align-items:flex-start;
    padding: 0.95rem 1.15rem; border-bottom:1px solid var(--line);
    background: var(--bg-alt);
  }
  h2 { margin:0; font-size:0.9rem; text-transform:uppercase; letter-spacing:0.14rem; color: var(--text-muted); }
  p { margin:0.35rem 0 0; color: var(--text-soft); font-size:0.9rem; max-width: 62ch; }
  .panel-body { padding: 1.1rem 1.15rem 1.2rem; }
</style>
