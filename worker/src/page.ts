// Docs for agents, and a tiny page for humans.

import { SCOPES, text, type Ctx, type Item } from "./common.ts";
import { list } from "./items.ts";

export function agentHelp(ctx: Ctx): string {
  // Above any one realm, the owner gets the realm routes spelled out with a placeholder id.
  const B = ctx.realm ? ctx.base : `${ctx.base}/realms/<realm>`;
  const auth = ctx.scope === "owner" ? ` -H "Authorization: Bearer $REALM_TOKEN"` : "";
  const exp = ctx.link?.expires ? `Expires ${new Date(ctx.link.expires).toISOString()}.` : "Does not expire.";
  const uses = ctx.link?.max_uses != null ? ` ${ctx.link.max_uses - ctx.link.uses} uses left.` : "";
  const picked = ctx.link?.selection ? " This link sees only the items picked for it." : "";
  const out = [
    `# realm — ${ctx.realm ? `"${ctx.realm.name}", ` : ""}${ctx.scope} access`,
    ``,
    `realm is a personal dump of notes and files, kept in realms.${picked} ${ctx.link ? exp + uses : ""}`,
    `Base URL: ${B}`,
    `No login needed beyond this URL${auth ? " plus the bearer token" : ""}. All responses are JSON unless noted.`,
    `curl -s${auth} "${B}/info"   # what this access allows, without spending a use`,
    ``,
  ];
  if (ctx.scope === "owner" && !ctx.realm) {
    out.push(
      `## Realms`,
      `curl -s${auth} "${ctx.base}/realms"   → {"realms": [{id, name, created, count, updated}]}`,
      `curl -s${auth} -X POST "${ctx.base}/realms" -H 'content-type: application/json' -d '{"name":"Trip"}'`,
      `curl -s${auth} -X PATCH "${ctx.base}/realms/<realm>" -H 'content-type: application/json' -d '{"name":"Holiday"}'`,
      `curl -s${auth} -X DELETE "${ctx.base}/realms/<realm>"   # deletes everything in it`,
      `The routes below work per realm. Without /realms/<realm>, they use the first realm.`,
      ``,
    );
  }
  const item = `Item: {id, kind: note|link|image|audio|video|file, text, name, mime, size, realm, src, created(epoch ms)}`;
  if (SCOPES.read.includes(ctx.scope)) {
    out.push(
      `## Find things`,
      `curl -s${auth} "${B}/items?limit=20"                   # newest first`,
      `curl -s${auth} "${B}/items?q=invoice+march"            # full-text search over text and file names`,
      `curl -s${auth} "${B}/items?kind=image,file&limit=50"   # filter by kind`,
      `curl -s${auth} "${B}/items?before=<id>"                # next page (response has "next")`,
      `→ {"items": [Item...], "next": "<id>"|null}`,
      item,
      ``,
      `## Fetch an item`,
      `curl -s${auth} "${B}/items/<id>"                       # metadata`,
      `curl -sLo out.bin${auth} "${B}/items/<id>/raw"         # file bytes (or note text). Supports Range.`,
      ``,
    );
  }
  if (SCOPES.write.includes(ctx.scope)) {
    out.push(
      `## Add things`,
      `curl -s${auth} -X POST "${B}/items" --data-binary "some text"          # a note`,
      `curl -s${auth} -T ./report.pdf "${B}/files/report.pdf"                 # a file (≤ 95 MB), ?text=caption optional`,
      `curl -s${auth} -F file=@a.png -F file=@b.png -F text=caption "${B}/items"  # form upload`,
      ``,
      `Bigger files: multipart, parts of equal size (e.g. 32 MiB, last may be smaller):`,
      `curl -s${auth} -X POST "${B}/uploads" -H 'content-type: application/json' -d '{"name":"big.mkv"}'   → {"upload": U}`,
      `curl -s${auth} -T part1 "${B}/uploads/U/1"     → {"part":1,"etag":E1}   (repeat for 2..N)`,
      `curl -s${auth} -X POST "${B}/uploads/U/complete" -H 'content-type: application/json' -d '{"parts":[{"part":1,"etag":"E1"}]}'`,
      ``,
    );
  }
  if (SCOPES.modify.includes(ctx.scope)) {
    out.push(
      `## Edit / remove`,
      `curl -s${auth} -X PATCH "${B}/items/<id>" -H 'content-type: application/json' -d '{"text":"new text"}'`,
      `curl -s${auth} -X DELETE "${B}/items/<id>"`,
      ``,
    );
  }
  if (ctx.scope === "owner") {
    out.push(
      `## Share links`,
      `curl -s${auth} -X POST "${B}/links" -H 'content-type: application/json' -d '{"scope":"drop","ttl":86400,"label":"bob"}'`,
      `curl -s${auth} -X POST "${B}/links" -H 'content-type: application/json' -d '{"items":["<id>","<id>"],"uses":3}'   # just these items`,
      `scope: drop | read | full · items: ids to share only those (read only; one item downloads at the link itself)`,
      `ttl seconds (omit = forever) · uses (omit = unlimited) · max_bytes upload budget`,
      `curl -s${auth} "${B}/links"  ·  curl -s${auth} -X DELETE "${B}/links/<token>"`,
      `curl -s${auth} -X PATCH "${B}/links/<token>" -H 'content-type: application/json' -d '{"items":["<id>"]}'   # change what it shares`,
      ``,
    );
  }
  return out.join("\n");
}

// Material-symbol paths (24px viewBox) so the page uses the same icons as the app.
const ICON: Record<string, string> = {
  attach:
    "M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6h-1.5z",
  send: "M3.4 20.4l17.45-7.48a1 1 0 0 0 0-1.84L3.4 3.6a.993.993 0 0 0-1.39.91L2 9.12c0 .5.37.93.87.99L17 12 2.87 13.88c-.5.07-.87.5-.87 1l.01 4.61c0 .71.73 1.2 1.39.91z",
  file: "M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z",
  image:
    "M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-4.86 8.86l-3 3.87L9 13.14 6 17h12l-3.86-5.14z",
  audio:
    "M12 3v9.28a4.39 4.39 0 0 0-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z",
  video:
    "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4zm2 14H4V10h16v8z",
  search:
    "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
  error:
    "M11 15h2v2h-2zm0-8h2v6h-2zm1-5C6.47 2 2 6.5 2 12a10 10 0 0 0 20 0c0-5.5-4.5-10-10-10zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
};
const svg = (name: string) => `<svg viewBox="0 0 24 24" aria-hidden=true><path d="${ICON[name]}"/></svg>`;

const FILTERS: [string, string][] = [
  ["", "All"],
  ["note", "Notes"],
  ["link", "Links"],
  ["image", "Images"],
  ["audio,video", "Audio & video"],
  ["file", "Files"],
];

export async function htmlPage(ctx: Ctx): Promise<Response> {
  const canRead = SCOPES.read.includes(ctx.scope);
  const canWrite = SCOPES.write.includes(ctx.scope);
  let feed = "";
  if (canRead && ctx.scope !== "owner") {
    const q = ctx.url.searchParams.get("q") || "";
    const kind = ctx.url.searchParams.get("kind") || "";
    const sub = new URL(`${ctx.base}/items`);
    if (q) sub.searchParams.set("q", q);
    if (kind) sub.searchParams.set("kind", kind);
    const { items } = (await (await list({ ...ctx, url: sub })).json()) as { items: Item[] };
    const href = (k: string) =>
      "?" + new URLSearchParams({ ...(q ? { q } : {}), ...(k ? { kind: k } : {}) }).toString();
    feed =
      `<form class=search>${svg("search")}<input name=q value="${esc(q)}" placeholder="Search the dump" autocomplete=off>` +
      (kind ? `<input type=hidden name=kind value="${esc(kind)}">` : "") +
      `</form>` +
      `<nav class=chips>${FILTERS.map(([k, l]) => `<a href="${href(k)}"${k === kind ? " class=on" : ""}>${l}</a>`).join("")}</nav>` +
      (items.length
        ? `<div class=feed>${items.map((i) => itemHtml(ctx, i)).join("")}</div>`
        : `<p class=empty>${q || kind ? "Nothing matches." : "Nothing here yet."}</p>`);
  }
  const composer = canWrite
    ? `<section id=drop class=composer>
<textarea id=t rows=3 placeholder="Type or paste a note, or drop files anywhere"></textarea>
<div class=bar><label class=icon title="Add files">${svg("attach")}<input type=file id=f multiple hidden></label>
<span class=hint>Ctrl+Enter to send</span><button id=go class=send title=Send>${svg("send")}</button></div>
<div id=log></div></section>`
    : "";
  const sub =
    ctx.scope === "drop"
      ? "Drop notes and files here"
      : ctx.link?.selection
        ? "Shared with you"
        : ctx.scope === "read"
          ? "Read-only"
          : "Shared dump";
  const name = ctx.realm ? esc(ctx.realm.name) : "";
  const exp = ctx.link?.expires ? ` · link expires <time data-t="${ctx.link.expires}"></time>` : "";

  const body = `<!doctype html><html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>realm${name ? "/" + name : ""}</title><style>
:root{color-scheme:dark;--bg:#0e0e11;--card:#18181d;--card2:#1f1f26;--line:#2a2a33;--text:#e7e7ea;--mute:#8a8a96;--a:#9d8cff;--on-a:#0e0e11;--tile:#2a2547;--on-tile:#e4deff;--err:#ff7a85}
@media (prefers-color-scheme:light){:root{color-scheme:light;--bg:#fafafb;--card:#f1f1f4;--card2:#eaeaee;--line:#dadae0;--text:#1b1b1f;--mute:#6b6b76;--a:#5b48e8;--on-a:#fff;--tile:#e8e4ff;--on-tile:#1e1650;--err:#d93a4a}}
*{box-sizing:border-box}html{background:var(--bg)}
body{font:15px/22px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--text);max-width:680px;margin:0 auto;padding:40px 16px 64px}
svg{width:22px;height:22px;fill:currentColor;flex:none}
header{margin:0 4px 20px}h1{margin:0;font-size:20px;font-weight:600;letter-spacing:.02em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
h1 span{color:var(--mute)}header p{margin:4px 0 0;color:var(--mute);font-size:13px}
.composer{background:var(--card);border-radius:22px;padding:6px;outline:2px solid transparent;transition:outline-color .15s}
body.over .composer{outline-color:var(--a)}
textarea{display:block;width:100%;min-height:72px;max-height:50vh;resize:none;border:0;outline:0;background:none;color:inherit;font:inherit;padding:12px 14px 4px}
textarea::placeholder,input::placeholder{color:var(--mute)}
.bar{display:flex;align-items:center;height:48px}
.icon{display:grid;place-items:center;width:48px;height:48px;border-radius:50%;color:var(--mute);cursor:pointer}.icon:hover{color:var(--text);background:var(--card2)}
.hint{flex:1;text-align:right;padding-right:10px;color:var(--mute);font-size:12px}
.send{display:grid;place-items:center;width:40px;height:40px;margin:4px;border:0;border-radius:50%;background:var(--a);color:var(--on-a);cursor:pointer}.send svg{width:18px;height:18px}.send:hover{filter:brightness(1.1)}
#log:empty{display:none}#log{display:grid;gap:4px;padding:4px 6px 6px}
.up{display:flex;align-items:center;gap:12px;padding:6px;border-radius:14px;background:var(--card2)}
.ring{width:42px;height:42px;border-radius:10px;background:var(--tile);color:var(--on-tile);display:grid;place-items:center;flex:none}
.ring circle{fill:none;stroke-width:2.5}.ring .tr{stroke:currentColor;opacity:.15}.ring .pr{stroke:currentColor;stroke-linecap:round;transition:stroke-dashoffset .2s;transform:rotate(-90deg);transform-origin:center}
.up .t{min-width:0}.up b{display:block;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.up small{color:var(--mute);font-size:12px}
.up.err small,.up.err .ring{color:var(--err)}
.search{display:flex;align-items:center;gap:10px;height:48px;margin:28px 0 10px;padding:0 16px;border-radius:24px;background:var(--card);color:var(--mute)}
.search input{flex:1;height:100%;border:0;outline:0;background:none;color:var(--text);font:inherit}
.chips{display:flex;gap:6px;overflow-x:auto;margin-bottom:18px;scrollbar-width:none}
.chips a{flex:none;height:32px;line-height:32px;padding:0 14px;border-radius:16px;background:var(--card);color:var(--text);text-decoration:none;font-size:13px}
.chips a.on{background:var(--tile);color:var(--on-tile)}
.feed{display:grid;gap:14px}
.card{background:var(--card);border-radius:16px;overflow:hidden}.card>p{margin:0;padding:10px 14px;white-space:pre-wrap;overflow-wrap:anywhere}
.card p a{color:var(--a)}
.file{display:flex;align-items:center;gap:12px;padding:10px;color:inherit;text-decoration:none}.file:hover{background:var(--card2)}
.file .tile{width:42px;height:42px;border-radius:10px;background:var(--tile);color:var(--on-tile);display:grid;place-items:center;flex:none}
.file b{display:block;font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.file small{color:var(--mute);font-size:12px}.file span{min-width:0}
.card.pic{max-width:420px}.card img{display:block;width:100%;max-height:360px;object-fit:cover;background:var(--card2)}
.meta{display:block;margin:4px 0 0 8px;color:var(--mute);font-size:11px;opacity:.85}
.empty{color:var(--mute);text-align:center;margin:48px 0}
</style>
<header><h1>${name ? `<span>realm/</span>${name}` : "realm"}</h1><p>${sub}${exp}</p></header>${composer}${feed}
<script>
const B=${JSON.stringify(ctx.base)},P=32*1024*1024,R=${canRead},$=id=>document.getElementById(id);
for(const t of document.querySelectorAll('time[data-t]')){const d=new Date(+t.dataset.t);t.textContent=d.toLocaleString([], {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})}
function size(n){const u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<3){n/=1024;i++}return (i?n.toFixed(1):n)+' '+u[i]}
const C=2*Math.PI*11;
function row(name){const d=document.createElement('div');d.className='up';
  d.innerHTML='<div class=ring><svg viewBox="0 0 28 28" width=28 height=28><circle class=tr cx=14 cy=14 r=11 /><circle class=pr cx=14 cy=14 r=11 stroke-dasharray="'+C+'" stroke-dashoffset="'+C+'"/></svg></div><div class=t><b></b><small>waiting…</small></div>';
  d.querySelector('b').textContent=name;$('log').append(d);const pr=d.querySelector('.pr'),s=d.querySelector('small');
  return{set(t,f){s.textContent=t;if(f!=null)pr.style.strokeDashoffset=C*(1-Math.min(1,f))},
    done(t){this.set(t,1);d.querySelector('.ring').innerHTML=${JSON.stringify(svg("check"))}},
    fail(t){d.classList.add('err');s.textContent=t;d.querySelector('.ring').innerHTML=${JSON.stringify(svg("error"))}}}}
// XHR rather than fetch: fetch can't report upload progress.
function send(method,url,body,onp,type){return new Promise((ok,no)=>{const x=new XMLHttpRequest();x.open(method,url);if(type)x.setRequestHeader('content-type',type);
  x.upload.onprogress=e=>onp&&onp(e.loaded);x.onload=()=>{let j={};try{j=JSON.parse(x.responseText)}catch{}x.status<300?ok(j):no(new Error(j.error||'HTTP '+x.status))};x.onerror=()=>no(new Error('network error'));x.send(body)})}
async function up(f){const r=row(f.name),of=' of '+size(f.size);
  try{
    if(f.size<=95e6){await send('PUT',B+'/files/'+encodeURIComponent(f.name),f,n=>r.set(size(n)+of,n/f.size),f.type||'application/octet-stream')}
    else{const {upload}=await send('POST',B+'/uploads',JSON.stringify({name:f.name,type:f.type}),null,'application/json');const parts=[];
      for(let i=0;i*P<f.size;i++){const o=i*P;parts.push(await send('PUT',B+'/uploads/'+upload+'/'+(i+1),f.slice(o,o+P),n=>r.set(size(o+n)+of,(o+n)/f.size)))}
      await send('POST',B+'/uploads/'+upload+'/complete',JSON.stringify({parts}),null,'application/json')}
    r.done('sent · '+size(f.size));return true
  }catch(e){r.fail(e.message);return false}}
let busy=false;
async function go(files){if(busy)return;busy=true;let ok=true;const t=$('t').value.trim();
  if(t){const r=row(t.split('\\n')[0].slice(0,60));try{await send('POST',B+'/items',t,null,'text/plain;charset=utf-8');$('t').value='';r.done('note sent')}catch(e){ok=false;r.fail(e.message)}}
  for(const f of files)ok=(await up(f))&&ok;
  busy=false;if(ok&&R&&(t||files.length))setTimeout(()=>location.reload(),700)}
if($('drop')){
  $('go').onclick=()=>go([]);$('f').onchange=e=>{go([...e.target.files]);e.target.value=''};
  let n=0;const b=document.body;
  addEventListener('dragenter',e=>{e.preventDefault();n++;b.classList.add('over')});addEventListener('dragover',e=>e.preventDefault());
  addEventListener('dragleave',()=>{if(--n<=0){n=0;b.classList.remove('over')}});
  addEventListener('drop',e=>{e.preventDefault();n=0;b.classList.remove('over');if(e.dataTransfer.files.length)go([...e.dataTransfer.files])});
  $('t').onpaste=e=>{const fs=[...e.clipboardData.files];if(fs.length){e.preventDefault();go(fs)}};
  $('t').oninput=e=>{e.target.style.height='auto';e.target.style.height=e.target.scrollHeight+'px'};
  $('t').onkeydown=e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey))go([])}}
</script></html>`;
  return text(body, 200, "text/html; charset=utf-8");
}

function itemHtml(ctx: Ctx, i: Item): string {
  const raw = `${ctx.base}/items/${i.id}/raw`;
  const ext = (i.name || "").split(".").pop()!;
  const icon = i.kind === "image" ? "image" : i.kind === "audio" ? "audio" : i.kind === "video" ? "video" : "file";
  let inner = "";
  const pic = i.kind === "image" && (i.size || 0) <= 15 * 1024 * 1024;
  if (pic) inner += `<a href="${raw}"><img src="${raw}" alt="${esc(i.name || "")}" loading=lazy></a>`;
  else if (i.name) {
    inner +=
      `<a class=file href="${raw}?dl"><span class=tile>${svg(icon)}</span><span><b>${esc(i.name)}</b>` +
      `<small>${fmtSize(i.size || 0)}${ext && ext.length <= 5 && ext !== i.name ? " · " + esc(ext.toUpperCase()) : ""}</small></span></a>`;
  }
  if (i.text) inner += `<p>${linkify(esc(i.text))}</p>`;
  const src = i.src && i.src !== "api" ? ` · ${esc(i.src)}` : "";
  return `<article><div class="card${pic ? " pic" : ""}">${inner}</div><span class=meta><time data-t="${i.created}"></time>${src}</span></article>`;
}

function linkify(escaped: string): string {
  // Runs on escaped text, so stop at entities (&quot; etc.) and trailing punctuation.
  return escaped.replace(
    /https?:\/\/(?:(?!&(?:quot|#39|lt|gt);)[^\s<>"'])+(?<![.,;:!?)\]])/g,
    (u) => `<a href="${u}" rel=noreferrer target=_blank>${u}</a>`,
  );
}

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}

function fmtSize(n: number): string {
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  for (; n >= 1024 && i < u.length - 1; i++) n /= 1024;
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}
