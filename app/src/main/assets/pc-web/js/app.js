/**
 * LanBridge PC 端（T22/T23/T23B/T26）：vanilla JS、零 CDN、ES Module。
 * - Ctrl+Enter 发送 / Enter 换行 + isComposing 输入法保护（T02C/T22）
 * - window 级 dragover/drop preventDefault 兜底（T02C）
 * - 1MB 分块上传 + 串行队列（并发=1，F-20 AC6）
 * - 断线指数退避重连 + 状态灯（F-10/T26）
 */
const $ = (id) => document.getElementById(id);
const CHUNK = 1 << 20; // 1MB
const MAX_BATCH = 500 * 1024 * 1024;

/* 全局错误可见化（v1.1.2）：http 局域网页面里 crypto.randomUUID 之类的异常
 * 以前只会静默吞掉——表现为"点了发送但什么都没发生"。现在一律弹顶部红条。 */
function showError(msg) {
  const b = $('banner');
  if (!b) return;
  b.textContent = '⚠ ' + msg + ' · 点击刷新页面（Ctrl+F5）';
  b.onclick = () => location.reload();
  b.style.background = '#C9302C';
  b.classList.remove('hidden');
}
window.addEventListener('error', (e) => showError('脚本错误：' + (e.message || e.type)));
window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason;
  showError('异步错误：' + ((r && r.message) || r));
});

/* UUID 生成：crypto.randomUUID 仅 HTTPS/localhost（安全上下文）可用，
 * 本应用经 http://192.168.43.x:8080 访问属非安全上下文，该 API 不存在，
 * 直接调用会抛 TypeError 导致 PC 端所有发送（文字/照片/文件）静默失败。
 * 统一走 uuid()：可用时用 randomUUID，否则回退到手写 GUID。 */
function uuid() {
  if (window.crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

let ws = null;
let retries = 0;
let lastPong = Date.now(); // 心跳存活时间戳（任意下行消息都算活）
const queue = [];      // 串行上传队列
let uploading = false;
const attaches = [];   // 驻留附件（≤9）

/* ---------- WebSocket ---------- */
function connect() {
  ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => { retries = 0; setStatus(true); $('banner').classList.add('hidden'); };
  ws.onmessage = (e) => {
    lastPong = Date.now(); // 任意消息都证明链路存活
    let m; try { m = JSON.parse(e.data); } catch { return; }
    switch (m.type) {
      case 'text': addBubble({ from: m.from === 'phone' ? 'in' : 'out', kind: 'text', text: m.text }); break;
      case 'file_meta': {
        // 手机端发来的文件：PC 主动拉取展示（图片/视频缩略图，其它显示文件卡）
        addBubble({
          from: m.from === 'phone' ? 'in' : 'out',
          kind: (m.kind === 'image' || m.kind === 'video') ? m.kind : 'file',
          id: m.id, name: m.name, size: m.size, mime: m.mime,
          url: `/api/files/${m.id}`,
        });
        break;
      }
      case 'status':
        if (m.conn === 'replaced') {
          setStatus(false);
          showBanner('已在其他标签页打开，本页面已断开 · 点击重新连接（输入内容保留）', connect);
        }
        break;
      case 'history': {
        // 历史同步（v1.1.9f）：以手机端会话为准整体重建，修复"发送和接收不一致"——
        // PC 刷新/断连期间漏掉的消息在重连后自动补齐，不再永久丢失
        messages.length = 0;
        selected.clear();
        if (selectionMode) exitSelection();
        document.querySelectorAll('#chat .row').forEach(r => r.remove());
        (m.items || []).forEach(it => addBubble({
          from: it.from === 'phone' ? 'in' : 'out',
          kind: it.kind || 'file',
          id: it.id, text: it.text, name: it.name, size: it.size, mime: it.mime,
          url: it.url,
        }));
        break;
      }
    }
  };
  ws.onclose = () => {
    setStatus(false);
    retries++;
    const delay = Math.min(1000 * 2 ** Math.min(retries, 5), 30000); // 指数退避上限 30s
    showBanner(`已断开，正在重连…（第 ${retries} 次）· 点击立即重试`, connect);
    setTimeout(() => { if (!ws || ws.readyState > 1) connect(); }, delay);
  };
}

// 标题栏显示服务端版本号：一眼确认加载的是不是新前端（装了新 APK 但页面没刷新时最有用）
function setStatus(on) {
  $('dot').className = `dot ${on ? 'on' : 'off'}`;
  $('connText').textContent = on ? '已连接' : '未连接';
  fetch('/api/info').then(r => r.json()).then(d => {
    $('peer').textContent = `${d.deviceName} · ${d.ip}`;
    const v = $('ver');
    if (v) v.textContent = d.version ? `v${d.version}` : '';
  }).catch(() => {});
}

function showBanner(text, onclick) {
  const b = $('banner');
  b.textContent = text;
  b.onclick = onclick;
  b.style.background = ''; // 恢复中性灰（showError 会置红）
  b.classList.remove('hidden');
}

/** 发送：连不上/抛错一律可见，不再静默吞掉 */
function send(obj) {
  try {
    if (ws && ws.readyState === 1) { ws.send(JSON.stringify(obj)); return true; }
  } catch (e) {
    showError('发送异常：' + (e && e.message));
    return false;
  }
  showError('未连接，消息未发出（看右上角状态灯，灰=没连上）');
  return false;
}

/* ---------- 气泡渲染 ---------- */
const messages = [];
function addBubble(m) {
  if (!m.id) m.id = uuid(); // 文本等无 id 消息统一补 id：否则 removeBubble/toggleSelect 以 id 为键会静默 no-op（A2）
  messages.push(m);
  render(m);
  const chat = $('chat');
  chat.scrollTop = chat.scrollHeight; // 新消息自动跟随
}

function fmtSize(n) {
  if (n >= 1 << 20) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1 << 10) return (n / 1024).toFixed(1) + ' KB';
  return n + ' B';
}

/* ---------- 文件类型图标（与安卓 ic_file_* 同源：页面形+白图形/字母徽标） ---------- */
const ICONS = {
  pdf:      { body:'#E5544B', fold:'#C23E36', badge:'PDF' },
  doc:      { body:'#4A89F3', fold:'#3568CE', badge:'W' },
  xls:      { body:'#3FA845', fold:'#2E8334', badge:'X' },
  ppt:      { body:'#ED6D48', fold:'#C95433', badge:'P' },
  txt:      { body:'#C3C9CF', fold:'#A5ACB4', g:'<path fill="#fff" d="M8.5,12.2 H15.5 V13.4 H8.5 Z"/><path fill="#fff" d="M8.5,14.8 H15.5 V16 H8.5 Z"/><path fill="#fff" d="M8.5,17.4 H13 V18.6 H8.5 Z"/>' },
  zip:      { body:'#F0954F', fold:'#CE7532', g:'<path fill="#fff" d="M11.55,8.6 H12.5 V9.6 H11.55 Z"/><path fill="#fff" d="M11.55,10.4 H12.5 V11.4 H11.55 Z"/><path fill="#fff" d="M11.55,12.2 H12.5 V13.2 H11.55 Z"/><path fill="#fff" d="M11.55,14 H12.5 V15 H11.55 Z"/><path fill="#fff" d="M10.4,15.7 H13.6 L12,18.4 Z"/>' },
  audio:    { body:'#C3C9CF', fold:'#A5ACB4', g:'<path fill="#fff" d="M8.4,12.4 H9.5 V15.4 H8.4 Z"/><path fill="#fff" d="M10.35,10.6 H11.45 V17.2 H10.35 Z"/><path fill="#fff" d="M12.3,9.4 H13.4 V18.4 H12.3 Z"/><path fill="#fff" d="M14.25,11.6 H15.35 V16.2 H14.25 Z"/>' },
  installer:{ body:'#7E57C2', fold:'#5E3FA0', g:'<path fill="#fff" d="M7.5,7.5 H16.5 V14 H7.5 Z"/><path fill="#fff" d="M9.6,16.8 H14.4 V18 H9.6 Z"/>' },
  ebook:    { body:'#C7772E', fold:'#A55F1E', g:'<path fill="#fff" d="M12,8 Q9,6.8 7,7.8 V16 Q9,15 12,16 Z"/><path fill="#fff" d="M12,8 Q15,6.8 17,7.8 V16 Q15,15 12,16 Z"/>' },
  webcode:  { body:'#16A2A2', fold:'#0E7E7E', g:'<g fill="none" stroke="#fff" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9,8.5 L6.5,12 L9,15.5"/><path d="M15,8.5 L17.5,12 L15,15.5"/><path d="M12,8 V16"/></g>' },
  design:   { body:'#D4537E', fold:'#B0365E', g:'<path fill="#fff" d="M12,6.5 L15.5,8.5 L12,10.5 L8.5,8.5 Z"/><path fill="#fff" d="M12,10 L15.5,12 L12,14 L8.5,12 Z"/><path fill="#fff" d="M12,13.5 L15.5,15.5 L12,17.5 L8.5,15.5 Z"/>' },
  torrent:  { body:'#2EA8C9', fold:'#1E86A6', g:'<path fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" d="M12,6 V11.5"/><path fill="#fff" d="M8.5,10 L12,13.5 L15.5,10 Z"/><path fill="#fff" d="M7.5,15 H16.5 V18 H7.5 Z"/>' },
  cad:      { body:'#3E5C6B', fold:'#2C4550', g:'<path fill="#fff" d="M7,17 L12,7 L17,17 Z"/><path fill="#3E5C6B" d="M10.2,15 L12,11.4 L13.8,15 Z"/>' },
  contact:  { body:'#3FA0D9', fold:'#2E7FB0', g:'<path fill="#fff" d="M12,6 a2.4,2.4 0 1 0 0.01,0 Z"/><path fill="#fff" d="M7.5,17 a4.5,4.5 0 0 1 9,0 Z"/>' },
  calendar: { body:'#5C6BC0', fold:'#4454A0', g:'<path fill="#fff" d="M7,8.5 H17 V18 H7 Z"/><path fill="#5C6BC0" d="M9.6,6.5 h1.4 v2 h-1.4 Z"/><path fill="#5C6BC0" d="M13,6.5 h1.4 v2 h-1.4 Z"/><path fill="#5C6BC0" d="M10,13 h4 v4 h-4 Z"/>' },
  generic:  { body:'#C3C9CF', fold:'#A5ACB4', badge:'?' }
};
const EXT_MAP = {
  pdf:'pdf', doc:'doc', docx:'doc', wps:'doc', rtf:'doc', odt:'doc',
  xls:'xls', xlsx:'xls', et:'xls', csv:'xls', ods:'xls',
  ppt:'ppt', pptx:'ppt', dps:'ppt', odp:'ppt',
  txt:'txt', md:'txt', log:'txt',
  zip:'zip', rar:'zip', '7z':'zip', tar:'zip', gz:'zip',
  mp3:'audio', wav:'audio', flac:'audio', aac:'audio', m4a:'audio', ogg:'audio', amr:'audio',
  exe:'installer', msi:'installer', dmg:'installer', deb:'installer', AppImage:'installer', pkg:'installer',
  epub:'ebook', mobi:'ebook', azw:'ebook', azw3:'ebook', kfx:'ebook',
  html:'webcode', htm:'webcode', css:'webcode', js:'webcode', ts:'webcode', jsx:'webcode', vue:'webcode', php:'webcode', json:'webcode', xml:'webcode',
  psd:'design', ai:'design', cdr:'design', sketch:'design', xd:'design',
  torrent:'torrent',
  dwg:'cad', dxf:'cad', skp:'cad', skb:'cad', '3ds':'cad', obj:'cad', fbx:'cad', stl:'cad', step:'cad', stp:'cad', igs:'cad', iges:'cad', dae:'cad', blend:'cad', max:'cad', c4d:'cad', rvt:'cad', sldprt:'cad', sldasm:'cad', ipt:'cad', iam:'cad', sat:'cad', scad:'cad',
  vcf:'contact', ics:'calendar'
};
function extOf(name) { const i = String(name || '').lastIndexOf('.'); return i < 0 ? '' : name.slice(i + 1).toLowerCase(); }
function fileIconKey(name) { return EXT_MAP[extOf(name)] || 'generic'; }
function fileIconSvg(name) {
  const I = ICONS[fileIconKey(name)];
  let extra = '';
  if (I.badge) {
    const fs = I.badge === 'PDF' ? 5.2 : 9;
    extra = `<text x="12.5" y="16.6" text-anchor="middle" font-family="Segoe UI,Microsoft YaHei,sans-serif" font-weight="700" font-size="${fs}" fill="#fff">${I.badge}</text>`;
  }
  return `<svg class="fileicon" viewBox="0 0 24 24" width="40" height="40"><path fill="${I.body}" d="M5.5,2.5 H14 L19.5,8 V21.5 H5.5 Z"/><path fill="${I.fold}" d="M14,2.5 V8 H19.5 Z"/>${I.g || ''}${extra}</svg>`;
}
// v1.1.9k 修复：此前 mediaKind 没有 text 分支，kind:'text' 的文字消息掉进兜底 'file'，
// 被渲染成「文件 0 B」空文件卡（v1.1.9d 网页重构引入的回归）。text 必须最先判断。
function mediaKind(m) { return m.kind === 'text' ? 'text' : m.kind === 'image' ? 'image' : m.kind === 'video' ? 'video' : 'file'; }
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c])); }
function download(m) { const src = m.url || m.data; if (!src) return; const a = document.createElement('a'); a.href = src; a.download = m.name || ''; a.click(); }
async function copyItem(m) {
  try {
    if (m.kind === 'image' && (m.data || m.url)) {
      const blob = await fetch(m.data || m.url).then(r => r.blob());
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
      toast('图片已复制到剪贴板'); return;
    }
    if ((m.kind === 'file' || m.kind === 'video') && (m.url || m.data)) { await navigator.clipboard.writeText(m.url || m.data); toast('下载链接已复制'); return; }
    if (m.kind === 'text') { await navigator.clipboard.writeText(m.text || ''); toast('文字已复制'); }
  } catch (e) { showError('复制失败：' + (e && e.message)); }
}
let _toastT = 0;
function toast(msg) {
  const b = $('banner'); b.textContent = msg; b.onclick = null; b.style.background = '#5C5C5C'; b.classList.remove('hidden');
  clearTimeout(_toastT); _toastT = setTimeout(() => b.classList.add('hidden'), 1800);
}
// 删除「传输副本」：服务端 FileStore 内的 $id.part，绝不删除用户源文件（PC 本地原文件不在服务端，不受影响）
function purgeFile(m) {
  if (!m || !m.id) return;
  if (m.kind !== 'image' && m.kind !== 'video' && m.kind !== 'file') return;
  fetch('/api/files/' + m.id, { method: 'DELETE' }).catch(() => {});
}
function removeBubble(m) {
  if (!m.id) return;
  purgeFile(m);
  const row = document.querySelector(`.row[data-id="${m.id}"]`);
  if (row) row.remove();
  selected.delete(m.id);
  if (selectionMode) renderSelBar();
  toast('已删除');
}
function openPreview(m) {
  const box = $('lightbox');
  const stage = box.querySelector('.stage');
  const tb = box.querySelector('.ltools');
  stage.innerHTML = ''; tb.innerHTML = '';
  if (m.kind === 'video') {
    const v = document.createElement('video'); v.src = m.url || m.data; v.controls = true; v.autoplay = true;
    v.style.maxWidth = '90vw'; v.style.maxHeight = '78vh'; v.style.background = '#000'; stage.appendChild(v);
  } else {
    const img = document.createElement('img'); img.src = m.data || m.url; img.alt = m.name || '';
    img.style.maxWidth = '90vw'; img.style.maxHeight = '78vh'; img.style.objectFit = 'contain'; stage.appendChild(img);
  }
  const mk = (label, fn, cls) => { const x = document.createElement('button'); x.textContent = label; if (cls) x.className = cls; x.onclick = fn; tb.appendChild(x); };
  mk('下载', () => download(m));
  mk('复制', () => copyItem(m));
  mk('删除', () => { removeBubble(m); box.classList.add('hidden'); }, 'danger');
  box.classList.remove('hidden');
}

function render(m) {
  const div = document.createElement('div');
  div.className = `row ${m.from}`;
  div.dataset.id = m.id || '';
  div._m = m;
  const b = document.createElement('div');
  b.className = 'bubble';
  const k = mediaKind(m);
  if (k === 'text') {
    b.textContent = m.text;
  } else if (k === 'image' || k === 'video') {
    const media = document.createElement('div');
    media.className = 'thumb';
    if (k === 'video') {
      const v = document.createElement('video');
      v.src = m.data || m.url; v.preload = 'metadata'; v.muted = true; v.playsInline = true; v.className = 'thumbmedia';
      v.addEventListener('loadedmetadata', () => { try { const d = v.duration || 0; v.currentTime = Math.min(0.1, d > 0 ? d * 0.1 : 0.1); } catch (e) {} });
      v.addEventListener('error', () => media.classList.add('noframe'));
      media.appendChild(v);
      const play = document.createElement('div'); play.className = 'playbadge'; play.textContent = '▶'; media.appendChild(play);
    } else {
      const img = document.createElement('img');
      img.src = m.data || m.url; img.alt = m.name || ''; img.className = 'thumbmedia'; media.appendChild(img);
    }
    media.addEventListener('click', (e) => { if (selectionMode) return; e.stopPropagation(); openPreview(m); });
    b.appendChild(media);
  } else {
    const card = document.createElement('div');
    card.className = 'filecard';
    card.innerHTML = fileIconSvg(m.name) +
      `<div class="fmeta"><a class="fname" href="${m.url || '#'}" download="${esc(m.name || '')}">${esc(m.name || '文件')}</a><span class="fsize">${fmtSize(m.size || 0)}</span></div>`;
    // 点击卡片任意处下载（文件名锚点是下载入口；整卡也可点）
    card.addEventListener('click', (e) => { if (selectionMode) return; if (e.target.closest('.fname')) return; download(m); });
    b.appendChild(card);
    if (m.progressEl) { const p = document.createElement('div'); p.className = 'progress'; p.innerHTML = '<i></i>'; b.appendChild(p); m.progressEl = p.firstChild; }
  }
  div.appendChild(b);
  div.oncontextmenu = (e) => { e.preventDefault(); showCtx(e, m, div, b); };
  if (selectionMode && m.id && selected.has(m.id)) markSelected(div, true);
  $('chat').appendChild(div);
}

/* ---------- 发送（双通道） ---------- */
async function sendTextNow(text) {
  if (!text) return;
  try {
    const m = { from: 'out', kind: 'text', text };
    addBubble(m);
    send({ type: 'text', id: uuid(), from: 'pc', text });
  } catch (e) {
    showError('发送文本失败：' + (e && e.message));
  }
}

async function sendFiles(files) { // 直发通道：选完即发
  try {
    const text = $('input').value.trim();
    if (text) { await sendTextNow(text); $('input').value = ''; }
    for (const f of files) {
      if (f.size > MAX_BATCH) { alert('单文件/单批次上限 500MB，超出请分批'); continue; }
      enqueue(f);
    }
    updateSendBtn();
  } catch (e) {
    showError('发送文件失败：' + (e && e.message));
  }
}

function enqueue(file) {
  let m;
  try {
    const isImage = file.type.startsWith('image/');
    const isVideo = file.type.startsWith('video/');
    m = {
      from: 'out', kind: isImage ? 'image' : (isVideo ? 'video' : 'file'),
      id: uuid(), name: file.name, size: file.size, mime: file.type || 'application/octet-stream',
    };
    if (isImage || isVideo) m.data = URL.createObjectURL(file);
    // PC 发出侧此前不设 url：视频缩略图/预览/保存/复制只认 m.url → 黑块+静默失败（A1）。
    // 上传目标即 /api/files/{id}，本地上传完成后该 url 始终可用，故在此统一赋值。
    m.url = '/api/files/' + m.id;
    addBubble(m);
  } catch (e) {
    showError('生成气泡失败：' + (e && e.message));
    return;
  }
  queue.push({ file, meta: m });
  pump();
}

async function pump() { // 串行：并发=1（F-20 AC6）
  if (uploading || queue.length === 0) return;
  uploading = true;
  const { file, meta } = queue.shift();
  try {
    const total = Math.ceil(file.size / CHUNK) || 1;
    for (let i = 0; i < total; i++) {
      const start = i * CHUNK;
      const blob = file.slice(start, Math.min(start + CHUNK, file.size));
      const resp = await fetch('/api/files', {
        method: 'POST',
        headers: {
          'X-File-Id': meta.id, 'X-Chunk-Index': String(i), 'X-Chunk-Size': String(CHUNK),
          // 供手机端显示「接收中 x%」占位气泡（头信息只能 ASCII，中文名编码后由服务端解码）
          'X-File-Name': encodeURIComponent(meta.name || ''), 'X-File-Size': String(meta.size || 0),
          'Content-Type': 'application/octet-stream',
        },
        body: blob,
      });
      if (!resp.ok) throw new Error('upload failed');
      updateProgress(meta, (i + 1) / total);
    }
    send({ type: 'file_meta', id: meta.id, from: 'pc', name: meta.name, mime: meta.mime,
           size: meta.size, chunkSize: CHUNK, totalChunks: total, kind: meta.kind === 'image' ? 'image' : 'file' });
  } catch (e) {
    // 上传失败：保留「重发」入口（v1.1.5 之前这个按钮没接事件，点了没反应）
    markFailed(meta, () => {
      const old = document.querySelector(`.row[data-id="${meta.id}"]`);
      if (old) old.remove();           // 移除失败气泡，重发成功后由服务端 file_meta 重建
      queue.push({ file, meta });      // 沿用同一 fileId，分块从 0 重传
      pump();
    });
    showError('上传失败：' + (e && e.message));
  }
  uploading = false;
  pump();
}

function updateProgress(meta, ratio) {
  if (!meta.el) meta.el = [...document.querySelectorAll(`.row[data-id="${meta.id}"] .progress > i`)].pop();
  if (meta.el) meta.el.style.width = `${Math.round(ratio * 100)}%`;
}

function markFailed(meta, retry) {
  const row = document.querySelector(`.row[data-id="${meta.id}"] .bubble`);
  if (!row) return;
  row.classList.add('failed');
  const s = document.createElement('div');
  s.className = 'status';
  s.innerHTML = '发送失败 <span class="resend">重发</span>';
  row.appendChild(s);
  const btn = s.querySelector('.resend');
  if (btn && retry) btn.onclick = retry; // 之前只渲染了「重发」文字，没绑事件
}

/* ---------- 输入区（驻留通道：拖拽/粘贴，≤9） ---------- */
function addChip(blob) {
  if (attaches.length >= 9) { alert('数量较多，建议直接发送（工具条按钮）'); return; } // 上限 9（AC4.2）
  const chip = document.createElement('div');
  chip.className = 'chip';
  const url = URL.createObjectURL(blob);
  chip.style.backgroundImage = `url(${url})`;
  const x = document.createElement('span'); x.className = 'x'; x.textContent = '×';
  x.onclick = () => { chip.remove(); attaches.splice(attaches.indexOf(blob), 1); updateSendBtn(); };
  chip.appendChild(x);
  $('attaches').appendChild(chip);
  attaches.push(blob);
  updateSendBtn();
}

function updateSendBtn() {
  $('btnSend').disabled = attaches.length === 0 && !$('input').value.trim();
}

/* ---------- 事件绑定 ---------- */
$('input').addEventListener('input', updateSendBtn);
$('input').addEventListener('keydown', (e) => {
  if (e.isComposing) return; // 输入法组词中不响应（T02C，防误发）
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSend(); }
});
function doSend() {
  if (attaches.length) { sendFiles([...attaches]); attaches.length = 0; $('attaches').innerHTML = ''; }
  else sendTextNow($('input').value.trim());
  $('input').value = '';
  updateSendBtn();
}
$('btnSend').onclick = doSend;

$('btnFile').onclick = () => $('fileInput').click();
$('btnPhoto').onclick = () => $('photoInput').click();
$('fileInput').onchange = (e) => { sendFiles([...e.target.files]); e.target.value = ''; };   // 直发
$('photoInput').onchange = (e) => { sendFiles([...e.target.files]); e.target.value = ''; };  // 直发

$('btnClear').onclick = () => {
  if (!confirm('将清空所有消息（保留连接地址），且记录不会保存。确定？')) return; // 二次确认（F-19 AC3）
  document.querySelectorAll('.row').forEach(r => r.remove());
};

// 拖拽兜底：window 级 preventDefault（T02C 致命项）
['dragover', 'drop'].forEach(t => window.addEventListener(t, e => e.preventDefault()));
window.addEventListener('dragover', () => $('composer').classList.add('drag'));
window.addEventListener('dragleave', (e) => { if (e.target === document.documentElement) $('composer').classList.remove('drag'); });
$('composer').addEventListener('drop', (e) => {
  e.preventDefault();
  $('composer').classList.remove('drag');
  const files = [...e.dataTransfer.files];
  if (files.length > 9) { files.slice(0, 9).forEach(addChip); alert('超过 9 个，仅驻留前 9 个，建议用「发文件」直接发送'); }
  else files.forEach(addChip); // 驻留通道：拖入输入框，随文字发送
});

// 粘贴截图 → 驻留（F-09）
document.addEventListener('paste', (e) => {
  for (const item of e.clipboardData.items) {
    if (item.type.startsWith('image/')) {
      addChip(item.getAsFile());
      e.preventDefault();
      break;
    }
  }
});

// 关闭前提示（零持久化）
window.addEventListener('beforeunload', (e) => {
  e.preventDefault();
  e.returnValue = '聊天记录不会保存，确定离开？';
});

/* ---------- 右键菜单 + 多选（T24B 精简版） ---------- */
let selectionMode = false;
const selected = new Set();

function showCtx(e, m, row, bubble) {
  const menu = $('ctxmenu');
  menu.innerHTML = '';
  const item = (label, cls, fn) => {
    const d = document.createElement('div');
    d.textContent = label; if (cls) d.className = cls;
    d.onclick = () => { menu.classList.add('hidden'); fn && fn(); };
    menu.appendChild(d);
  };
  const k = mediaKind(m);
  if (k !== 'text') item('预览', '', () => openPreview(m));
  if (k === 'text') item('复制', '', () => copyItem(m));
  else item('保存 / 另存为', '', () => download(m));
  if (k !== 'text') item('复制', '', () => copyItem(m));
  item('多选', '', () => { enterSelection(); toggleSelect(row, m.id); });
  item('删除', 'danger', () => removeBubble(m));
  menu.style.left = Math.min(e.clientX, innerWidth - 160) + 'px';
  menu.style.top = Math.min(e.clientY, innerHeight - 160) + 'px';
  menu.classList.remove('hidden');
}

/* 多选（与安卓一致：右键进入、点选切换、顶部操作栏 保存/复制/删除；PC 无第二对端故不显示转发） */
function markSelected(row, on) {
  const b = row.querySelector('.bubble'); if (!b) return;
  b.classList.toggle('selected', on);
  let c = b.querySelector('.checkbadge');
  if (on && !c) { c = document.createElement('span'); c.className = 'checkbadge'; c.textContent = '✓'; b.appendChild(c); }
  if (!on && c) c.remove();
}
function toggleSelect(row, id) {
  if (!id) return;
  if (selected.has(id)) { selected.delete(id); markSelected(row, false); }
  else { selected.add(id); markSelected(row, true); }
  renderSelBar();
}
function enterSelection() { if (selectionMode) return; selectionMode = true; renderSelBar(); }
function exitSelection() {
  selected.forEach(id => { const row = document.querySelector(`.row[data-id="${id}"]`); if (row) markSelected(row, false); });
  selected.clear(); renderSelBar();
}
function renderSelBar() {
  let bar = $('selbar');
  if (!selectionMode) { if (bar) bar.classList.add('hidden'); return; }
  if (!bar) { bar = document.createElement('div'); bar.id = 'selbar'; bar.className = 'selbar'; $('app').insertBefore(bar, $('composer')); }
  bar.innerHTML = '';
  const mk = (label, fn, cls) => { const x = document.createElement('button'); x.textContent = label; if (cls) x.className = cls; x.onclick = fn; bar.appendChild(x); };
  mk(`取消(${selected.size})`, exitSelection);
  mk('保存', saveSelected);
  mk('复制', copySelected);
  mk('删除', deleteSelected, 'danger');
  bar.classList.remove('hidden');
}
function saveSelected() { selected.forEach(id => { const row = document.querySelector(`.row[data-id="${id}"]`); const m = row && row._m; if (m) download(m); }); exitSelection(); } // 保存后退出多选，恢复普通界面
function copySelected() { selected.forEach(id => { const row = document.querySelector(`.row[data-id="${id}"]`); const m = row && row._m; if (m) copyItem(m); }); exitSelection(); } // 复制后退出多选，恢复普通界面
function deleteSelected() {
  const n = selected.size;
  selected.forEach(id => { const row = document.querySelector(`.row[data-id="${id}"]`); if (row) { purgeFile(row._m); row.remove(); } });
  selected.clear(); exitSelection();
  toast(`已删除 ${n} 条`);
}

document.addEventListener('click', (e) => {
  if (!e.target.closest('.ctxmenu')) $('ctxmenu').classList.add('hidden');
  if (selectionMode && e.target.closest('.row') && !e.target.closest('.ctxmenu') && !e.target.closest('a') && !e.target.closest('.thumb')) {
    const row = e.target.closest('.row');
    toggleSelect(row, row.dataset.id);
  }
});
// 灯箱：点击黑色背景/舞台空白处关闭（× 与 Esc 仍可用）
$('lightbox').addEventListener('click', (e) => { if (e.target === $('lightbox')) $('lightbox').classList.add('hidden'); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { $('ctxmenu').classList.add('hidden'); $('lightbox').classList.add('hidden'); }
});

// 心跳保活：每 10s ping 一次；>25s 无任何下行消息判定为僵尸连接，
// 主动断开触发 onclose 重连（否则页面长期显示"已连接"但发消息石沉大海）
setInterval(() => {
  if (ws && ws.readyState === 1) {
    if (Date.now() - lastPong > 25000) { try { ws.close(); } catch (e) {} return; }
    send({ type: 'ping', id: uuid() });
  }
}, 10000);

connect();
