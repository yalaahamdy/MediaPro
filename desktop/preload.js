/* MediaPro Desktop Bridge — exposes MediaProFS, MediaProDesktop, and MediaProUpdater */
const { contextBridge, ipcRenderer } = require('electron');

const S = (op, a) => ipcRenderer.sendSync('mpfs-sync', { op, a: a === undefined ? {} : a });

contextBridge.exposeInMainWorld('MediaProFS', {
  getRoots: () => S('getRoots'),
  hasAllFilesAccess: () => S('hasAllFilesAccess'),
  requestAllFilesAccess: () => S('requestAllFilesAccess'),
  list: (p) => S('list', { p }),
  readFileBase64: (p) => S('read', { p }),
  mkdir: (p) => S('mkdir', { p }),
  renamePath: (p, n) => S('rename', { p, n }),
  copyPath: (p, d) => S('copy', { p, d }),
  movePath: (p, d) => S('move', { p, d }),
  deletePath: (p) => S('delete', { p }),
  openWithSystem: (p) => { try { ipcRenderer.invoke('mpfs-open', { p }); } catch (e) {} },
  shareFile: (p) => { try { ipcRenderer.invoke('mpfs-reveal', { p }); } catch (e) {} }
});

contextBridge.exposeInMainWorld('MediaProDesktop', {
  driveInfo: (p) => ipcRenderer.invoke('mpfs-driveinfo', { p }),
  quickAccess: () => ipcRenderer.invoke('mpfs-quick'),
  deepSearch: (o) => ipcRenderer.invoke('mpfs-deepsearch', o || {}),
  diskUsage: (p) => ipcRenderer.invoke('mpfs-diskusage', { p }),
  checksum: (p, algo) => ipcRenderer.invoke('mpfs-checksum', { p, algo }),
  trashMany: (paths) => ipcRenderer.invoke('mpfs-trash', { paths }),
  reveal: (p) => ipcRenderer.invoke('mpfs-reveal', { p }),
  statFull: (p) => ipcRenderer.invoke('mpfs-stat', { p }),
  writeFileBase64: (p, b) => ipcRenderer.invoke('mpfs-write', { p, b }),
  openTerminal: (p) => ipcRenderer.invoke('mpfs-terminal', { p })
});

contextBridge.exposeInMainWorld('MediaProDesktopUpdater', {
  getVersion: () => S('getVersion'),
  checkForUpdates: () => ipcRenderer.invoke('mpfs-updater-check'),
  downloadUpdate: (downloadUrl) => ipcRenderer.invoke('mpfs-updater-download', { downloadUrl }),
  applyUpdate: () => ipcRenderer.invoke('mpfs-updater-apply'),
  onProgress: (callback) => {
    ipcRenderer.removeAllListeners('mpfs-updater-progress');
    ipcRenderer.on('mpfs-updater-progress', (event, data) => callback(data));
  }
});
