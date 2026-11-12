<script>
  import Header from '$lib/components/Header.svelte';
  import { loadAdminUiConfig } from '$lib/api/ui.js';
  import { initAuthStores } from '$lib/stores/auth.js';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { onMount } from 'svelte';

  onMount(() => {
    initAuthStores();
    selectedModelId.init();
    loadAdminUiConfig();
  });
</script>

<Header />
<main class="page">
  <slot />
</main>

<style>
  :global(:root) {
    color-scheme: light;
    --bg: #f3f6fa;
    --bg-alt: #f7f9fc;
    --panel: #ffffff;
    --panel-strong: #ffffff;
    --accent: #945208;
    --accent-soft: #fff2d5;
    --accent-line: #d3a34c;
    --text: #1e293b;
    --text-soft: #45556c;
    --text-muted: #576a82;
    --line: #dce3eb;
    --line-strong: #b6c2d1;
    --control-line: #8393a9;
    --danger: #b42318;
    --danger-soft: #fff1f0;
    --danger-line: #e7a49e;
    --good: #166534;
    --good-soft: #eefaf1;
    --good-line: #a9d5b5;
    --shadow-soft: 0 10px 30px rgba(30, 41, 59, 0.06);
  }
  :global(html, body) { margin:0; min-height:100%; }
  :global(body) {
    font-family: 'Manrope', 'Segoe UI', sans-serif;
    background:
      radial-gradient(circle at top left, rgba(245, 158, 11, 0.06), transparent 30%),
      linear-gradient(180deg, #fafbfd 0%, var(--bg) 100%);
    color: var(--text);
  }
  :global(*) { box-sizing: border-box; }
  :global(a) { color: inherit; }
  :global(button), :global(input), :global(select), :global(textarea) {
    font: inherit;
  }
  :global(button) {
    border:1px solid var(--control-line);
    background:var(--panel);
    color:var(--text);
    padding:0.65rem 0.95rem;
    border-radius:0.8rem;
    cursor:pointer;
    font-weight: 600;
    transition: border-color 120ms ease, background 120ms ease, transform 120ms ease;
  }
  :global(button:hover) {
    border-color: var(--accent-line);
    background: var(--bg-alt);
    transform: translateY(-1px);
  }
  :global(button.primary) {
    background: var(--accent-soft);
    border-color: var(--accent-line);
    color: var(--accent);
  }
  :global(button.secondary) {
    background: var(--bg-alt);
    color: var(--text-soft);
  }
  :global(button.ghost) {
    background: transparent;
    color: var(--text-muted);
    border-color: var(--line);
  }
  :global(button.danger) {
    background: var(--danger-soft);
    border-color: var(--danger-line);
    color: var(--danger);
  }
  :global(button:disabled) {
    opacity: 0.55;
    cursor: default;
    transform: none;
  }
  :global(input), :global(select), :global(textarea) {
    width:100%;
    border:1px solid var(--control-line);
    background:var(--panel);
    color:var(--text);
    padding:0.7rem 0.85rem;
    border-radius:0.8rem;
  }
  :global(input:focus), :global(select:focus), :global(textarea:focus) {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px rgba(148, 82, 8, 0.12);
  }
  :global(button:focus-visible), :global(a:focus-visible), :global(summary:focus-visible) {
    outline: 2px solid var(--accent);
    outline-offset: 3px;
  }
  :global(input::placeholder), :global(textarea::placeholder) { color: var(--text-muted); opacity: 1; }
  :global(input[type="checkbox"]), :global(input[type="radio"]) {
    width: auto;
    padding: 0;
    accent-color: var(--accent);
  }
  :global(table) { width:100%; border-collapse: collapse; }
  :global(th), :global(td) { padding:0.7rem 0.45rem; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
  :global(th) { color:var(--text-muted); font-size:0.78rem; text-transform:uppercase; letter-spacing:0.08rem; }
  .page {
    max-width: 1480px;
    margin: 0 auto;
    padding: 1.6rem 1.8rem 2rem;
    display:flex;
    flex-direction:column;
    gap:1.25rem;
  }
  @media (max-width: 900px) {
    .page { padding: 1rem; }
  }
</style>
