# Imported object placement and resynchronization — 2026-11-13

Archi's log recorded a successful import of “Find stuck orders”, followed by a
`[CreateElement, CreateViewObject]` batch when the existing element was dragged
from the tree into a view. The server rejected that semantic recreation with
`EXTERNAL_FIELD_OWNED`. Placing a committed element should submit notation only;
the central-field guard correctly remained active.

The capture adapter previously ignored remote notifications entirely. Its known
semantic IDs were seeded when attached, but were not updated by later imports or
snapshots. Remote containment/ID changes now maintain those identity sets without
submitting local operations. A regression imports an ApplicationComponent after
capture starts, then places that same object in two views and checks that each
placement emits only CreateViewObject and retains one semantic element.

The same log exposed two follow-on client errors:

- The import dialog's success callback closed the shell, then tried to update its
  disposed buttons. Both the asynchronous completion and button update now guard
  disposed controls.
- A cold snapshot closes diagram editors. Their transient null model selection
  detached the session during the projection rebuild, leading to zero applied
  snapshot operations and incomplete local contents. The workbench selection
  bridge now ignores these transient notifications under RemoteApplyGuard, while
  ordinary selection changes still detach normally. Projection reset also removes
  relationships from model containment before clearing inverse endpoint references,
  avoiding validation of relationships whose elements are being replaced.

Server verification found that the model itself retained all 27 elements,
49 semantic relationships, two views and 13 connections, including the imported
application. An export was saved before installing the fix. The integrity report
had a separate false-positive bug: it checked diagram connection `sourceId` and
`targetId`, while the snapshot contract uses `sourceViewObjectId` and
`targetViewObjectId`. The checker and regression fixtures now use the actual
contract and still detect genuinely missing endpoints. No stored data repair,
model restore or authorization relaxation was needed.

Validation: all 80 client tests and 66 service tests passed; the packaged plugin
was installed with its checksum matched to the tested artifact. The deployed
server reported zero integrity issues and an identical model snapshot after
restart. The old plugin and local projection were backed up under
`.run/client-fixes/20261113-163332/`; the local model cache was preserved while
Archi's rebuildable runtime cache was refreshed.

After restarting Archi, open the shared model from the server and retry placing
the application in a view. If the model is already connected, Tools → Resynchronize
Model requests a fresh snapshot without resetting the shared model or its history.
