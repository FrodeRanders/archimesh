<script>
  export let mode = 'open';
  export let label = '';
</script>

<span class:open={mode === 'open'} class:acl={mode === 'acl'} class="pill">{label || mode}</span>

<style>
  .pill {
    display:inline-flex; align-items:center; gap:0.35rem; padding:0.22rem 0.65rem; border-radius:999px;
    font-size:0.72rem; letter-spacing:0.08rem; text-transform:uppercase; border:1px solid var(--line-strong);
  }
  .open { color:var(--good); border-color: var(--good-line); background: var(--good-soft); }
  .acl { color:var(--accent); border-color: var(--accent-line); background: var(--accent-soft); }
</style>
