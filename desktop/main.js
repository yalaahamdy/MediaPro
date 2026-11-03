/* MediaPro Desktop — main process
 * Full-filesystem bridge for the Files browser + desktop-only superpowers:
 * drives with free space, quick access, deep search, folder analytics,
 * checksums, Recycle-Bin delete, reveal in Explorer, open terminal here,
 * write-back for text editor + In-App GitHub Releases Auto-Updater. */
const { app, BrowserWindow, Menu, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const http = require('http');
const crypto = require('crypto');
const { execFile, spawn } = require('child_process');

app.setName('MediaPro');
let win = null;
let downloadedUpdatePath = null;

/* ---------------- paths ---------------- */
function norm(p) {
  if (typeof p !== 'string' || !p.trim()) throw new Error('مسار غير صالح');
  let s = p.replace(/\\/g, '/').replace(/\/{2,}/g, '/').trim();
  s = s.replace(/\/+$/, '');
  if (!s) s = '/';
  if (/^[A-Za-z]:$/.test(s)) s += '/';
  return s;
}
function baseOf(p) { return p.replace(/\/+$/, ''); }
function destIn(dir, name) {
  name = String(name).replace(/[\/\\]/g, '_').trim() || 'ملف';
  dir = baseOf(dir);
  let cand = dir + '/' + name;
  if (!fs.existsSync(cand)) return cand;
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : '';
  for (let i = 2; i < 1000; i++) {
    cand = dir + '/' + stem + ' (' + i + ')' + ext;
    if (!fs.existsSync(cand)) return cand;
  }
  throw new Error('تعذّر إيجاد اسم متاح');
}

/* ---------------- drives ---------------- */
let driveMeta = [];
let driveMetaT = 0;
function driveLabel(L) {
  const m = driveMeta.find(d => (d.path || '').toUpperCase().charAt(0) === L.toUpperCase());
  if (!m) return 'القرص المحلي (' + L + ':)';
  const t = +m.type;
  const tname = t === 2 ? 'قرص قابل للإزالة' : t === 3 ? 'القرص المحلي' : t === 5 ? 'محرك أقراص ضوئي' : 'قرص';
  return (m.name ? m.name + ' — ' : '') + tname + ' (' + L + ':)';
}
function listDrives() {
  if (driveMeta.length && Date.now() - driveMetaT < 30000) {
    return driveMeta.map(d => ({ name: driveLabel(d.path.charAt(0)), path: d.path.charAt(0) + ':' }));
  }
  const roots = [];
  for (let i = 67; i <= 90; i++) {
    const L = String.fromCharCode(i);
    try { fs.statSync(L + ':/'); roots.push({ name: driveLabel(L), path: L + ':' }); } catch (e) {}
  }
  if (!roots.length) roots.push({ name: 'القرص المحلي (C:)', path: 'C:' });
  return roots;
}
function refreshDrives() {
  return new Promise(res => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      'Get-CimInstance Win32_LogicalDisk | Select-Object DeviceID,VolumeName,DriveType,Size,FreeSpace | ConvertTo-Json -Compress'],
      { timeout: 9000, windowsHide: true },
      (er, so) => {
        if (!er && so) {
          try {
            const j = JSON.parse(String(so));
            const arr = Array.isArray(j) ? j : [j];
            driveMeta = arr.filter(d => d && d.DeviceID).map(d => ({
              path: String(d.DeviceID).replace(/\\/g, ''),
              name: d.VolumeName || '',
              type: +d.DriveType || 0,
              total: +d.Size || 0,
              free: +d.FreeSpace || 0
            }));
            driveMetaT = Date.now();
          } catch (e) {}
        }
        res();
      });
  });
}

/* ---------------- sync ops (MediaProFS) ---------------- */
const SYNC_OPS = {
  getVersion() { return { ok: true, version: app.getVersion() }; },
  getRoots() { return { ok: true, roots: listDrives() }; },
  hasAllFilesAccess() { return true; },
  requestAllFilesAccess() { try { shell.openPath('shell:MyComputerFolder'); } catch (e) {} return { ok: true }; },
  list(a) {
    const p = norm(a.p);
    const items = fs.readdirSync(p, { withFileTypes: true });
    const entries = [];
    const base = baseOf(p);
    for (const it of items) {
      let d = it.isDirectory(), s = 0, m = 0;
      try { const st = fs.statSync(base + '/' + it.name); d = st.isDirectory(); s = st.size; m = st.mtimeMs; } catch (e) {}
      entries.push({ n: it.name, p: base + '/' + it.name, d, s, m });
    }
    return { ok: true, path: p, entries };
  },
  read(a) {
    const p = norm(a.p);
    const st = fs.statSync(p);
    if (st.size > 250 * 1024 * 1024) throw new Error('الملف أكبر من 250 ميجابايت');
    const b64 = fs.readFileSync(p).toString('base64');
    return { ok: true, b64, data: b64 };
  },
  mkdir(a) { fs.mkdirSync(norm(a.p), { recursive: false }); return { ok: true }; },
  rename(a) {
    const p = norm(a.p);
    const nn = String(a.n || '').replace(/[\/\\]/g, '').trim();
    if (!nn) throw new Error('اسم غير صالح');
    fs.renameSync(p, path.join(path.dirname(p), nn));
    return { ok: true };
  },
  copy(a) {
    const src = norm(a.p), dst = destIn(norm(a.d), path.basename(src));
    if (src.length > 3 && (dst === src || dst.startsWith(src + '/'))) throw new Error('لا يمكن النسخ داخل المجلد نفسه');
    fs.cpSync(src, dst, { recursive: true, force: false, errorOnExist: true });
    return { ok: true };
  },
  move(a) {
    const src = norm(a.p), dst = destIn(norm(a.d), path.basename(src));
    if (src.length > 3 && (dst === src || dst.startsWith(src + '/'))) throw new Error('لا يمكن النقل داخل المجلد نفسه');
    try { fs.renameSync(src, dst); }
    catch (e) {
      if (e.code === 'EXDEV') { fs.cpSync(src, dst, { recursive: true }); fs.rmSync(src, { recursive: true, force: true }); }
      else throw e;
    }
    return { ok: true };
  },
  delete(a) { try { shell.trashItem(norm(a.p)).catch(() => {}); } catch (e) {} return { ok: true }; },
  openWithSystem(a) { shell.openPath(norm(a.p)); return { ok: true }; },
  shareFile(a) { shell.showItemInFolder(norm(a.p)); return { ok: true }; }
};

ipcMain.on('mpfs-sync', (ev, payload) => {
  const { op, a } = payload || {};
  let out;
  try { out = SYNC_OPS[op] ? SYNC_OPS[op](a || {}) : { ok: false, error: 'عملية غير معروفة: ' + op }; }
  catch (e) { out = { ok: false, error: (e && e.message) || String(e) }; }
  ev.returnValue = out;
});

ipcMain.handle('mpfs-async-list', async (ev, a) => {
  try {
    const p = norm(a.p);
    const items = await fs.promises.readdir(p, { withFileTypes: true });
    const entries = [];
    const base = baseOf(p);
    for (const it of items) {
      let d = it.isDirectory(), s = 0, m = 0;
      try { const st = await fs.promises.stat(base + '/' + it.name); d = st.isDirectory(); s = st.size; m = st.mtimeMs; } catch (e) {}
      entries.push({ n: it.name, p: base + '/' + it.name, d, s, m });
    }
    return { ok: true, path: p, entries };
  } catch (e) { return { ok: false, error: (e && e.message) || String(e) }; }
});

ipcMain.handle('mpfs-async-read', async (ev, a) => {
  try {
    const p = norm(a.p);
    const st = await fs.promises.stat(p);
    if (st.size > 250 * 1024 * 1024) throw new Error('الملف أكبر من 250 ميجابايت');
    const buf = await fs.promises.readFile(p);
    const b64 = buf.toString('base64');
    return { ok: true, b64, data: b64 };
  } catch (e) { return { ok: false, error: (e && e.message) || String(e) }; }
});

/* ---------------- async ops (desktop pro features) ---------------- */
const CATS = {
  video: ['mp4', 'mkv', 'avi', 'mov', 'webm', 'wmv', 'flv', 'm4v', 'mpg', 'mpeg', 'ts', '3gp'],
  audio: ['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac', 'wma', 'opus', 'mid'],
  image: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'heic', 'ico', 'tif', 'tiff'],
  doc: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md', 'json', 'csv', 'html', 'htm', 'xml', 'epub', 'rtf'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso']
};
function catExt(name) {
  const e = (name.split('.').pop() || '').toLowerCase();
  for (const k in CATS) if (CATS[k].includes(e)) return k;
  return 'other';
}

ipcMain.handle('mpfs-driveinfo', async (ev, a) => {
  try {
    const p = norm(a.p);
    const L = (p.match(/^([A-Za-z]):/) || [])[1];
    if (!L) return { ok: false, error: 'لا يوجد قرص' };
    if (!driveMeta.length || Date.now() - driveMetaT > 60000) await refreshDrives();
    const m = driveMeta.find(d => (d.path || '').toUpperCase().charAt(0) === L.toUpperCase());
    if (m) return { ok: true, path: L + ':', free: m.free, total: m.total, label: m.name || '' };
    return { ok: true, path: L + ':', free: null, total: null };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-quick', () => {
  try {
    const items = [{ name: 'الأقراص', path: '__roots__' }];
    const push = (name, key) => {
      try {
        const p = app.getPath(key);
        if (fs.statSync(p).isDirectory()) items.push({ name, path: norm(p) });
      } catch (e) {}
    };
    push('سطح المكتب', 'desktop');
    push('التنزيلات', 'downloads');
    push('المستندات', 'documents');
    push('الصور', 'pictures');
    push('الموسيقى', 'music');
    push('الفيديوهات', 'videos');
    try {
      const h = app.getPath('home');
      if (fs.statSync(h).isDirectory()) items.push({ name: 'المجلد الرئيسي', path: norm(h) });
    } catch (e) {}
    return { ok: true, items };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-deepsearch', async (ev, o) => {
  const a = o || {};
  try {
    const dir = norm(a.dir);
    const needle = String(a.q || '').toLowerCase();
    if (!needle) throw new Error('لا يوجد نص بحث');
    const max = Math.min(Math.max(+a.max || 500, 1), 5000);
    const entries = [];
    let truncated = false;
    const t0 = Date.now();
    const dirs = [dir];
    while (dirs.length) {
      if (entries.length >= max || Date.now() - t0 > 6000) { truncated = true; break; }
      const cur = dirs.shift();
      let list;
      try { list = fs.readdirSync(cur, { withFileTypes: true }); } catch (e) { continue; }
      const base = baseOf(cur);
      for (const it of list) {
        if (entries.length >= max || Date.now() - t0 > 6000) { truncated = true; break; }
        if (it.isSymbolicLink && it.isSymbolicLink()) continue;
        const full = base + '/' + it.name;
        let isDir = it.isDirectory(), size = 0, mtime = 0;
        try { const st = fs.statSync(full); isDir = st.isDirectory(); size = st.size; mtime = st.mtimeMs; } catch (e) {}
        if (it.name.toLowerCase().includes(needle)) entries.push({ n: it.name, p: full, d: isDir, s: size, m: mtime });
        if (isDir) dirs.push(full);
      }
    }
    return { ok: true, entries, truncated };
  } catch (e) { return { ok: false, error: e.message, entries: [] }; }
});

ipcMain.handle('mpfs-diskusage', async (ev, a) => {
  try {
    const p = norm(a.p);
    const types = { video: { n: 0, s: 0 }, audio: { n: 0, s: 0 }, image: { n: 0, s: 0 }, doc: { n: 0, s: 0 }, archive: { n: 0, s: 0 }, other: { n: 0, s: 0 } };
    const st = fs.statSync(p);
    if (!st.isDirectory()) {
      return { ok: true, size: st.size, files: 1, dirs: 0, top: [{ n: path.basename(p), p, s: st.size }], types, truncated: false };
    }
    let size = 0, files = 0, dirs = 0, truncated = false;
    const t0 = Date.now();
    const top = [];
    const stack = [p];
    while (stack.length) {
      if (Date.now() - t0 > 8000 || files > 200000) { truncated = true; break; }
      const cur = stack.pop();
      let list;
      try { list = fs.readdirSync(cur, { withFileTypes: true }); } catch (e) { continue; }
      dirs++;
      const base = baseOf(cur);
      for (const it of list) {
        if (Date.now() - t0 > 8000 || files > 200000) { truncated = true; break; }
        if (it.isSymbolicLink && it.isSymbolicLink()) continue;
        const full = base + '/' + it.name;
        if (it.isDirectory()) { stack.push(full); continue; }
        let sz = 0;
        try { sz = fs.statSync(full).size; } catch (e) { continue; }
        files++; size += sz;
        const k = catExt(it.name);
        types[k].n++; types[k].s += sz;
        top.push({ n: it.name, p: full, s: sz });
        if (top.length > 400) { top.sort((x, y) => y.s - x.s); top.length = 200; }
      }
    }
    top.sort((x, y) => y.s - x.s);
    return { ok: true, size, files, dirs: Math.max(0, dirs - 1), top: top.slice(0, 5), types, truncated };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-checksum', async (ev, a) => {
  try {
    const p = norm(a.p);
    const algo = ['md5', 'sha1', 'sha256'].includes(a.algo) ? a.algo : 'md5';
    const hex = await new Promise((res, rej) => {
      try {
        const h = crypto.createHash(algo);
        const s = fs.createReadStream(p);
        s.on('data', d => h.update(d));
        s.on('end', () => res(h.digest('hex')));
        s.on('error', e => rej(e));
      } catch (e) { rej(e); }
    });
    return { ok: true, algo, hex };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-trash', async (ev, a) => {
  const paths = ((a && a.paths) || []).map(p => norm(p));
  let done = 0, fail = 0;
  for (const p of paths) {
    try { await shell.trashItem(p); done++; } catch (e) { fail++; }
  }
  return { ok: fail === 0, done, fail };
});

ipcMain.handle('mpfs-reveal', (ev, a) => {
  try { shell.showItemInFolder(norm(a.p)); return { ok: true }; }
  catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-open', (ev, a) => {
  try { shell.openPath(norm(a.p)); return { ok: true }; }
  catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-stat', async (ev, a) => {
  try {
    const p = norm(a.p);
    const st = fs.statSync(p);
    let readonly = false;
    try { fs.accessSync(p, fs.constants.W_OK); } catch (e) { readonly = true; }
    return { ok: true, size: st.size, mtimeMs: st.mtimeMs, ctimeMs: st.ctimeMs, birthtimeMs: st.birthtimeMs || st.ctimeMs, isDir: st.isDirectory(), readonly, hidden: false };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-write', (ev, a) => {
  try {
    const p = norm(a.p);
    const buf = Buffer.from(String(a.b || ''), 'base64');
    fs.writeFileSync(p, buf);
    return { ok: true, bytes: buf.length };
  } catch (e) { return { ok: false, error: e.message }; }
});

ipcMain.handle('mpfs-terminal', (ev, a) => {
  try {
    const d = norm(a.p);
    if (!fs.existsSync(d) || !fs.statSync(d).isDirectory()) {
      return { ok: false, error: 'المجلد المحدد غير موجود' };
    }
    // Launch cmd safely in folder without command string concatenation
    spawn('cmd.exe', ['/k', 'title MediaPro Workspace'], { cwd: d, detached: true, stdio: 'ignore' }).unref();
    return { ok: true };
  } catch (e) { return { ok: false, error: e.message }; }
});

/* =========================================================================
 *  In-App GitHub Auto-Updater for Desktop
 * ========================================================================= */
function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https:') ? https : http;
    client.get(url, { headers: { 'User-Agent': 'MediaPro-Desktop/' + app.getVersion() } }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return fetchJson(res.headers.location).then(resolve).catch(reject);
      }
      if (res.statusCode !== 200) {
        return reject(new Error('HTTP ' + res.statusCode));
      }
      let raw = '';
      res.on('data', chunk => raw += chunk);
      res.on('end', () => {
        try { resolve(JSON.parse(raw)); } catch (e) { reject(e); }
      });
    }).on('error', reject);
  });
}

function downloadFile(url, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https:') ? https : http;
    client.get(url, { headers: { 'User-Agent': 'MediaPro-Desktop/' + app.getVersion() } }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        return downloadFile(res.headers.location, destPath, onProgress).then(resolve).catch(reject);
      }
      if (res.statusCode !== 200) {
        return reject(new Error('HTTP Error: ' + res.statusCode));
      }
      const total = parseInt(res.headers['content-length'] || '0', 10);
      let downloaded = 0;
      const fileStream = fs.createWriteStream(destPath);

      res.on('data', chunk => {
        downloaded += chunk.length;
        if (total > 0 && onProgress) {
          const percent = Math.min(100, Math.round((downloaded / total) * 100));
          onProgress({ percent, downloaded, total });
        }
      });

      res.pipe(fileStream);

      fileStream.on('finish', () => {
        fileStream.close(() => resolve(destPath));
      });

      fileStream.on('error', err => {
        fs.unlink(destPath, () => {});
        reject(err);
      });
    }).on('error', err => {
      fs.unlink(destPath, () => {});
      reject(err);
    });
  });
}

ipcMain.handle('mpfs-updater-check', async () => {
  try {
    const release = await fetchJson('https://api.github.com/repos/yalaahamdy/MediaPro/releases/latest');
    return { ok: true, release };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

ipcMain.handle('mpfs-updater-download', async (ev, a) => {
  try {
    const downloadUrl = a.downloadUrl;
    if (!downloadUrl) throw new Error('رابط التنزيل غير محدد');
    const tempDir = app.getPath('temp');
    downloadedUpdatePath = path.join(tempDir, 'MediaPro-Windows-Portable-Update.exe');

    await downloadFile(downloadUrl, downloadedUpdatePath, progress => {
      if (win && !win.isDestroyed()) {
        win.webContents.send('mpfs-updater-progress', progress);
      }
    });

    return { ok: true, path: downloadedUpdatePath };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

ipcMain.handle('mpfs-updater-apply', async () => {
  try {
    if (!downloadedUpdatePath || !fs.existsSync(downloadedUpdatePath)) {
      throw new Error('ملف التحديث غير موجود، يرجى التنزيل أولاً');
    }

    const currentExe = process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
    const tempDir = app.getPath('temp');
    const scriptPath = path.join(tempDir, 'mediapro_replace_and_run.bat');
    const pid = process.pid;

    const scriptContent = `@echo off
set "PID=${pid}"
set "SRC=${downloadedUpdatePath.replace(/\//g, '\\')}"
set "DEST=${currentExe.replace(/\//g, '\\')}"

echo Waiting for MediaPro (PID %PID%) to terminate...
:waitloop
tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
if %errorlevel% equ 0 (
    timeout /t 1 /nobreak >nul
    goto waitloop
)

timeout /t 1 /nobreak >nul
echo Replacing executable...
copy /y "%SRC%" "%DEST%" >nul
if %errorlevel% neq 0 (
    timeout /t 2 /nobreak >nul
    copy /y "%SRC%" "%DEST%" >nul
)

echo Starting updated MediaPro...
start "" "%DEST%"

del "%SRC%" 2>nul
del "%~f0" 2>nul
exit
`;

    fs.writeFileSync(scriptPath, scriptContent, { encoding: 'utf8' });

    spawn('cmd.exe', ['/c', scriptPath], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true
    }).unref();

    setTimeout(() => {
      app.quit();
    }, 500);

    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

/* ---------------- window ---------------- */
function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 420,
    minHeight: 520,
    title: 'MediaPro v5.5',
    backgroundColor: '#0a0d14',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.png'),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
      backgroundThrottling: false
    }
  });
  Menu.setApplicationMenu(null);
  win.loadFile(path.join(__dirname, '..', 'MediaPro.html'));
  win.once('ready-to-show', () => { win.show(); });
  win.on('page-title-updated', (e) => e.preventDefault());
}

app.whenReady().then(() => {
  refreshDrives();
  setInterval(refreshDrives, 60000).unref();
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
