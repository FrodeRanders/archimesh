<script>
  import { page } from '$app/stores';

  const tabs = [
    { label: 'Overview', href: '/admin-ui/' },
    { label: 'Models', href: '/admin-ui/models' },
    { label: 'Versions', href: '/admin-ui/versions' },
    { label: 'Operations', href: '/admin-ui/operations' },
    { label: 'Publication', href: '/admin-ui/publication' },
    { label: 'Inbound sync', href: '/admin-ui/inbound' },
    { label: 'Access', href: '/admin-ui/access' },
    { label: 'Sessions', href: '/admin-ui/sessions' },
    { label: 'Audit', href: '/admin-ui/audit' }
  ];

  const contextByPath = {
    '/admin-ui/': 'Server overview and model focus',
    '/admin-ui/models': 'Model lifecycle and maintenance',
    '/admin-ui/versions': 'Tags, export, and import',
    '/admin-ui/operations': 'Recent model activity and op batches',
    '/admin-ui/publication': 'Approved architecture publication to Turbo EA',
    '/admin-ui/inbound': 'Central changes into linked model HEADs',
    '/admin-ui/access': 'Identity, ACLs, and diagnostics',
    '/admin-ui/sessions': 'Live Archimesh sessions',
    '/admin-ui/audit': 'Admin and websocket audit trails'
  };

  $: currentPath = $page.url.pathname;
  $: context = contextByPath[currentPath] || 'Admin console';
</script>

<header class="header">
  <div class="brand">
    <div class="mark">AC</div>
    <div class="copy">
      <div class="title">Archimesh Admin</div>
      <div class="subtitle">{context}</div>
    </div>
  </div>

  <nav class="tabs" aria-label="Admin sections">
    {#each tabs as tab}
      <a class:active={currentPath === tab.href} href={tab.href}>{tab.label}</a>
    {/each}
  </nav>
</header>

<style>
  .header {
    position: sticky;
    top: 0;
    z-index: 10;
    display: grid;
    grid-template-columns: minmax(220px, 1fr) auto;
    align-items: center;
    gap: 1rem;
    padding: 1rem 1.8rem;
    border-bottom: 1px solid var(--line);
    background: rgba(8, 11, 20, 0.9);
    backdrop-filter: blur(14px);
  }
  .brand { display:flex; align-items:center; gap:0.9rem; }
  .mark {
    width: 42px; height: 42px; border-radius: 12px; display:grid; place-items:center;
    background: linear-gradient(135deg, #d97706, #f59e0b);
    color:#140f07; font-weight:700; letter-spacing:0.08rem;
  }
  .title { font-size: 0.92rem; text-transform: uppercase; letter-spacing: 0.16rem; color: var(--text-muted); }
  .subtitle { font-size: 0.95rem; color: var(--text); }
  .tabs { display:flex; gap:0.8rem; flex-wrap:wrap; justify-content:flex-end; }
  .tabs a {
    text-decoration:none;
    color: var(--text-muted);
    padding:0.45rem 0.85rem;
    border-radius:999px;
    border:1px solid transparent;
    background: rgba(255,255,255,0.02);
    transition: border-color 120ms ease, background 120ms ease, color 120ms ease;
  }
  .tabs a.active { color: var(--text); border-color: rgba(245, 158, 11, 0.35); background: rgba(245, 158, 11, 0.12); }
  .tabs a:hover { color: var(--text); border-color: rgba(255,255,255,0.08); }
  @media (max-width: 900px) {
    .header { grid-template-columns: 1fr; }
    .tabs { justify-content:flex-start; }
  }
</style>
