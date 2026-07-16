const demoDoors = [
  { id: 'north', name: '北门 · 云庭', mac: 'A4:C1:38:••:7B:10', rawMac: 'A4:C1:38:2F:7B:10', key: '8F20D44C19B7A631', isDefault: true },
  { id: 'garage', name: '地下车库 B2', mac: 'D8:3A:DD:••:91:6E', rawMac: 'D8:3A:DD:05:91:6E', key: '1B780A4E2DCC9480', isDefault: false }
];

let doors = structuredClone(demoDoors);
let scene = new URLSearchParams(location.search).get('scene') || 'list';
let activeStage = new URLSearchParams(location.search).get('stage') || 'read';
let activeTask = false;

const screen = document.querySelector('#screen');
const modal = document.querySelector('#modal');
const modalContent = document.querySelector('#modal-content');
const sceneSelect = document.querySelector('#scene-select');
const toast = document.querySelector('#toast');

const icons = { empty: '⌁', error: '!', timeout: '⌛', permission: '◇', bluetooth: 'ᛒ', corrupt: '⚠' };

function doorCard(door) {
  return `<article class="door-card ${door.isDefault ? 'default' : ''}" data-id="${door.id}">
    <div class="card-top">
      <div class="door-glyph" aria-hidden="true"></div>
      <div class="door-info"><h3>${door.name}</h3><p>${door.mac}</p></div>
      ${door.isDefault ? '<span class="badge">默认</span>' : ''}
    </div>
    <div class="card-actions">
      <button class="unlock-button" data-unlock="${door.id}">开门</button>
      <button class="more-button" data-menu="${door.id}" aria-label="管理 ${door.name}">•••</button>
    </div>
  </article>`;
}

function listView() {
  return `<div class="door-list">${doors.map(doorCard).join('')}</div>`;
}

function loadingView() {
  return `<section class="system-state"><div class="loader"></div><h3>正在读取本机门禁</h3><p>解密与检查默认门禁关系，不会发起网络请求。</p></section>`;
}

function emptyView() {
  return `<section class="empty-state"><div class="state-illustration">▥</div><h3>还没有本地门禁</h3><p>添加第一处门禁后，即使没有网络也能发起开门。</p><button class="primary-button" data-action="add">新增门禁</button></section>`;
}

function progressView() {
  const order = ['connect','read','send','response'];
  const labels = { connect: ['连接设备','正在连接'], read: ['读取种子','正在读取'], send: ['发送指令','等待发送'], response: ['确认写入','等待响应'] };
  const index = Math.max(0, order.indexOf(activeStage));
  return `<section class="task-panel">
    <div class="task-target"><div class="pulse-ring">▥</div><div><p class="eyebrow">${activeTask ? '手动开门' : '冷启动 · 自动一次'}</p><h3>北门 · 云庭</h3><p>A4:C1:38:••:7B:10</p></div></div>
    <div class="progress-list">${order.map((key,i) => `<div class="progress-step ${i < index ? 'done' : i === index ? 'active' : ''}"><span class="step-mark">${i < index ? '✓' : i+1}</span><b>${labels[key][0]}</b><small>${i < index ? '完成' : i === index ? labels[key][1] : '等待'}</small></div>`).join('')}</div>
    <p class="task-note">正在执行的门禁任务全局唯一。回到前台或连续点击不会创建第二个任务。</p>
    <div class="task-actions"><button class="secondary-button" data-action="background">模拟前后台</button><button class="danger-button" data-action="cancel">取消开门</button></div>
  </section>`;
}

function resultView(kind) {
  const content = {
    success: ['✓','开门指令已发送','传输已完成，请确认门禁的实际状态。','返回门禁','再次发送'],
    error: ['!','未找到可用门禁设备','请靠近门禁，并检查保存的 MAC 地址后重试。','检查配置','重试'],
    timeout: ['⌛','连接超时','设备在限定时间内没有响应，任务已停止且不会自动重试。','返回列表','重试'],
    protocol: ['!','门禁协议响应异常','读取到的种子或通道不符合已确认协议，未发送空帧或部分指令。','检查配置','重试'],
    permission: ['◇','需要“附近设备”权限','Android 12 及以上需要此权限连接已保存的门禁。拒绝后不会循环询问。','暂不授权','去授权'],
    bluetooth: ['ᛒ','蓝牙已关闭','开启蓝牙后将继续当前任务，不会因返回页面再次自动开门。','取消任务','开启蓝牙'],
    corrupt: ['⚠','门禁数据无法读取','原始加密数据会被保留。确认重置前，不会静默覆盖或删除。','稍后处理','安全重置']
  }[kind];
  const cls = kind === 'success' ? '' : kind === 'permission' || kind === 'bluetooth' ? 'permission' : 'error';
  return `<section class="system-state"><div class="result-symbol ${cls}">${content[0]}</div><p class="eyebrow">${kind === 'success' ? 'GATT WRITE · SUCCESS' : '任务已安全停止'}</p><h3>${content[1]}</h3><p>${content[2]}</p><div class="button-stack"><button class="primary-button" data-action="${kind === 'success' ? 'list' : kind === 'permission' ? 'grant' : kind === 'bluetooth' ? 'enable' : kind === 'corrupt' ? 'reset' : 'retry'}">${content[4]}</button><button class="secondary-button" data-action="list">${content[3]}</button></div></section>`;
}

function render() {
  sceneSelect.value = scene;
  if (scene === 'list') screen.innerHTML = listView();
  else if (scene === 'loading') screen.innerHTML = loadingView();
  else if (scene === 'empty') screen.innerHTML = emptyView();
  else if (scene === 'progress') screen.innerHTML = progressView();
  else screen.innerHTML = resultView(scene);
}

function openModal(html) { modalContent.innerHTML = html; modal.hidden = false; }
function closeModal() { modal.hidden = true; }
function showToast(message) { toast.textContent = message; toast.classList.add('show'); setTimeout(() => toast.classList.remove('show'), 1900); }

function formModal(door) {
  const editing = Boolean(door);
  openModal(`<h3 id="modal-title">${editing ? '编辑门禁' : '新增门禁'}</h3><p class="helper">凭据仅用于本机直连，保存时会规范化并加密。密钥不会直接显示或复制。</p>
    <form id="door-form" data-editing="${door?.id || ''}">
      <div class="form-field"><label>门禁名称</label><input name="name" maxlength="80" value="${door?.name || ''}" placeholder="例如：北门 · 云庭"><p class="field-error" data-error="name"></p></div>
      <div class="form-field"><label>蓝牙 MAC 地址</label><input name="mac" value="${door?.rawMac || ''}" placeholder="A4:C1:38:2F:7B:10"><p class="field-error" data-error="mac"></p></div>
      <div class="form-field"><label>门禁密钥 · 16 位 HEX</label><input name="key" type="password" value="${door?.key || ''}" placeholder="••••••••••••••••"><p class="field-error" data-error="key"></p></div>
      <div class="modal-actions"><button type="button" class="secondary-button" data-close>取消</button><button class="primary-button">保存</button></div>
    </form>`);
}

function menuModal(door) {
  openModal(`<h3 id="modal-title">管理门禁</h3><p class="helper">${door.name}<br>${door.mac}</p><div class="menu-list">
    <button data-menu-action="edit" data-id="${door.id}">编辑资料</button>
    <button data-menu-action="default" data-id="${door.id}">${door.isDefault ? '取消默认门禁' : '设为默认门禁'}</button>
    <button class="destructive" data-menu-action="delete" data-id="${door.id}">删除门禁</button>
    <button data-close>取消</button>
  </div>`);
}

function confirmDelete(door) {
  openModal(`<h3 id="modal-title">删除“${door.name}”？</h3><p class="helper">${door.isDefault ? '这也是当前默认门禁。删除后会同时取消默认，且不会自动选择其他门禁。' : '删除后，本机将无法再使用这项配置开门。'}</p><div class="modal-actions"><button class="secondary-button" data-close>取消</button><button class="danger-button" data-confirm-delete="${door.id}">确认删除</button></div>`);
}

document.addEventListener('click', (event) => {
  const target = event.target.closest('button');
  if (!target) return;
  if (target.dataset.scene) { scene = target.dataset.scene; activeStage = target.dataset.stage || activeStage; activeTask = false; render(); }
  if (target.id === 'add-door' || target.dataset.action === 'add') formModal();
  if (target.dataset.unlock) { if (activeTask) return showToast('北门 · 云庭正在开门，请稍候'); activeTask = true; activeStage = 'connect'; scene = 'progress'; render(); }
  if (target.dataset.menu) menuModal(doors.find(d => d.id === target.dataset.menu));
  if (target.dataset.close !== undefined) closeModal();
  if (target.dataset.menuAction === 'edit') formModal(doors.find(d => d.id === target.dataset.id));
  if (target.dataset.menuAction === 'default') {
    const door = doors.find(d => d.id === target.dataset.id);
    doors.forEach(d => d.isDefault = door.isDefault ? false : d.id === door.id);
    closeModal(); render(); showToast(door.isDefault ? `已将“${door.name}”设为默认` : '已取消默认门禁');
  }
  if (target.dataset.menuAction === 'delete') confirmDelete(doors.find(d => d.id === target.dataset.id));
  if (target.dataset.confirmDelete) { doors = doors.filter(d => d.id !== target.dataset.confirmDelete); closeModal(); scene = doors.length ? 'list' : 'empty'; render(); showToast('门禁已从本机删除'); }
  if (target.dataset.action === 'cancel') { activeTask = false; scene = 'error'; render(); screen.querySelector('h3').textContent = '已取消开门'; screen.querySelector('.system-state > p:nth-of-type(2)').textContent = '任务资源已清理，迟到的设备回调不会改变此结果。'; }
  if (target.dataset.action === 'background') showToast('已恢复同一任务，没有重复触发');
  if (target.dataset.action === 'retry' || target.dataset.action === 'grant' || target.dataset.action === 'enable') { activeTask = true; activeStage = 'connect'; scene = 'progress'; render(); }
  if (target.dataset.action === 'list') { activeTask = false; scene = 'list'; render(); }
  if (target.dataset.action === 'reset') showToast('原型不会直接删除数据：实现需再次确认');
  if (target.id === 'help-button') openModal('<h3 id="modal-title">原型说明</h3><p class="helper">“成功”只代表 GATT 指令写入完成，不代表物理门已经打开。自动开门仅在进程级 Launcher 冷启动时尝试一次；回前台、旋转和设置页返回均不重复触发。</p><button class="primary-button" data-close>我知道了</button>');
  if (target.id === 'cold-start') { const defaultDoor = doors.find(d => d.isDefault); activeTask = false; scene = defaultDoor ? 'progress' : 'list'; activeStage = 'connect'; render(); if (!defaultDoor) showToast('没有默认门禁，本次冷启动不自动开门'); }
});

document.addEventListener('submit', (event) => {
  if (event.target.id !== 'door-form') return;
  event.preventDefault();
  const data = new FormData(event.target);
  const name = data.get('name').trim(); const mac = data.get('mac').trim().toUpperCase(); const key = data.get('key').trim().toUpperCase();
  const errors = { name: name ? '' : '请输入门禁名称', mac: /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(mac) ? '' : '请输入 XX:XX:XX:XX:XX:XX 格式', key: /^[0-9A-F]{16}$/.test(key) ? '' : '密钥必须恰好为 16 位十六进制' };
  Object.entries(errors).forEach(([field,message]) => event.target.querySelector(`[data-error="${field}"]`).textContent = message);
  if (Object.values(errors).some(Boolean)) return;
  const duplicate = doors.find(d => d.rawMac === mac && d.id !== event.target.dataset.editing);
  if (duplicate) { event.target.querySelector('[data-error="mac"]').textContent = `与“${duplicate.name}”重复`; return; }
  const existing = doors.find(d => d.id === event.target.dataset.editing);
  if (existing) Object.assign(existing, { name, rawMac: mac, mac: mac.replace(/^(.{9}).{5}/, '$1••') , key });
  else doors.push({ id: crypto.randomUUID(), name, rawMac: mac, mac: mac.replace(/^(.{9}).{5}/, '$1••'), key, isDefault: false });
  closeModal(); scene = 'list'; render(); showToast(existing ? '门禁资料已更新' : '门禁已加密保存到本机');
});

sceneSelect.addEventListener('change', () => { scene = sceneSelect.value; render(); });
modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });
render();
