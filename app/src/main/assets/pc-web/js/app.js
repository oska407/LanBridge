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
        // 手机端发来的文件：PC 主动拉取展示（气泡带下载）
        addBubble({
          from: m.from === 'phone' ? 'in' : 'out', kind: m.kind === 'image' ? 'image' : 'file',
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

function render(m) {
  const div = document.createElement('div');
  div.className = `row ${m.from}`;
  div.dataset.id = m.id || '';
  const b = document.createElement('div');
  b.className = 'bubble';
  if (m.kind === 'text') {
    b.textContent = m.text;
  } else if (m.kind === 'image') {
    const img = document.createElement('img');
    img.src = m.data || m.url; img.alt = m.name || '';
    b.appendChild(img);
    if (m.url && m.from === 'in') { const d = dlLink(m, '下载'); b.appendChild(d); }
  } else {
    const a = document.createElement('a');
    a.className = 'fname'; a.href = m.url || '#'; a.download = m.name || ''; a.textContent = m.name || '文件';
    b.appendChild(a);
    const s = document.createElement('span'); s.className = 'fsize'; s.textContent = fmtSize(m.size || 0);
    b.appendChild(s);
    if (m.progressEl) { const p = document.createElement('div'); p.className = 'progress'; p.innerHTML = '<i></i>'; b.appendChild(p); m.progressEl = p.firstChild; }
  }
  div.appendChild(b);
  div.oncontextmenu = (e) => { e.preventDefault(); showCtx(e, m, div, b); };
  $('chat').appendChild(div);
}

function dlLink(m, text) {
  const a = document.createElement('a');
  a.href = m.url; a.download = m.name; a.textContent = ' ' + text;
  a.className = 'fsize';
  return a;
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
    m = {
      from: 'out', kind: file.type.startsWith('image/') ? 'image' : 'file',
      id: uuid(), name: file.name, size: file.size, mime: file.type || 'application/octet-stream',
    };
    if (m.kind === 'image') m.data = URL.createObjectURL(file);
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
  if (m.kind === 'text') item('复制', '', () => navigator.clipboard?.writeText(m.text).catch(() => {}));
  else item('另存为', '', () => { const a = document.createElement('a'); a.href = m.url || m.data; a.download = m.name; a.click(); });
  item('多选', '', () => { selectionMode = true; row.click(); });
  item('删除', 'danger', () => { row.remove(); });
  menu.style.left = Math.min(e.clientX, innerWidth - 160) + 'px';
  menu.style.top = Math.min(e.clientY, innerHeight - 140) + 'px';
  menu.classList.remove('hidden');
}

document.addEventListener('click', (e) => {
  if (!e.target.closest('.ctxmenu')) $('ctxmenu').classList.add('hidden');
  if (selectionMode && e.target.closest('.row') && !e.target.closest('.ctxmenu')) {
    const row = e.target.closest('.row');
    const id = row.dataset.id;
    if (!id) return;
    if (selected.has(id)) { selected.delete(id); row.querySelector('.bubble').classList.remove('selected'); }
    else { selected.add(id); row.querySelector('.bubble').classList.add('selected'); }
  }
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') $('ctxmenu').classList.add('hidden'); });

// 心跳保活：每 10s ping 一次；>25s 无任何下行消息判定为僵尸连接，
// 主动断开触发 onclose 重连（否则页面长期显示"已连接"但发消息石沉大海）
setInterval(() => {
  if (ws && ws.readyState === 1) {
    if (Date.now() - lastPong > 25000) { try { ws.close(); } catch (e) {} return; }
    send({ type: 'ping', id: uuid() });
  }
}, 10000);

connect();
