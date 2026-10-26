'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const field = (form, name) => form.elements.namedItem(name);
  const loginPage = document.body.dataset.page === 'login';
  let csrf, me, connections = [], nodes = [], loadedTools = [], loadedSource = null, userPage = 1, userTotal = 0, activeTab = 'nodes', polling = false;
  function status(id, message = '', ok = true) {
    $(id).textContent = message;
    $(id).className = 'status ' + (ok ? 'ok' : 'err');
  }
  async function token() {
    const response = await fetch('/api/auth/csrf', {credentials:'same-origin', cache:'no-store'});
    const data = await response.json();
    if (!response.ok) throw new Error('无法获取请求校验信息');
    csrf = data.data.token;
  }
  async function api(url, method = 'GET', body) {
    if (method !== 'GET' && !csrf) await token();
    const response = await fetch(url, {
      method, credentials:'same-origin', cache:'no-store',
      headers: {'Content-Type':'application/json', ...(method !== 'GET' ? {'X-CSRF-TOKEN':csrf} : {})},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    let data;
    try { data = await response.json(); } catch (_) { throw new Error('服务器响应异常'); }
    if (response.status === 401 && !loginPage) { location.replace('/login.html'); throw new Error('请重新登录'); }
    if (!response.ok || !data.success) throw new Error(data.message || '请求失败');
    return data.data;
  }
  function element(tag, text, className) {
    const e = document.createElement(tag);
    if (text !== undefined) e.textContent = text;
    if (className) e.className = className;
    return e;
  }
  function button(text, action, danger = false) {
    const b = element('button', text, 'secondary' + (danger ? ' danger' : ''));
    b.type = 'button'; b.addEventListener('click', () => run(b, 'globalStatus', action)); return b;
  }
  async function run(target, statusId, action) {
    const buttons = target.matches('button') ? [target] : [...target.querySelectorAll('button')];
    buttons.forEach(b => b.disabled = true); status(statusId);
    try { await action(); } catch (ex) { status(statusId, ex.message, false); }
    finally {
      buttons.forEach(b => b.disabled = false);
      if (target.id === 'prevUsers' || target.id === 'nextUsers') {
        $('prevUsers').disabled = userPage <= 1; $('nextUsers').disabled = userPage * 50 >= userTotal;
      }
    }
  }
  function bindForm(id, statusId, action) {
    $(id).addEventListener('submit', e => { e.preventDefault(); run($(id), statusId, () => action($(id))); });
  }
  function option(select, text, value) { const o = element('option', text); o.value = String(value); select.append(o); }
  function table(target, headings, rows) {
    const t = element('table'), head = element('thead'), tr = element('tr');
    headings.forEach(h => tr.append(element('th', h))); head.append(tr); t.append(head);
    const body = element('tbody');
    rows.forEach(row => { const r = element('tr'); row.forEach(value => { const cell = element('td'); if (value instanceof Node) cell.append(value); else cell.textContent = value; r.append(cell); }); body.append(r); });
    t.append(body); $(target).replaceChildren(rows.length ? t : element('div', '暂无数据', 'empty'));
  }
  function actions(...items) { const row = element('div', undefined, 'row'); row.append(...items); return row; }
  function endpointHost(url) { try { return new URL(url).host; } catch (_) { return '接入点'; } }
  function clearTools() {
    loadedTools = []; loadedSource = null;
    $('toolName').replaceChildren(); option($('toolName'), '请先加载工具', '');
    $('toolArgs').replaceChildren(); $('toolDescription').textContent = ''; $('invokeResult').value = ''; $('invokeTool').disabled = true;
  }
  function renderConnections() {
    table('connectionList', ['连接名称','协议','状态','服务地址','操作'], connections.map(c => [
      c.connectionName, c.transport, c.enabled ? '已启用' : '已禁用', endpointHost(c.url),
      actions(button('编辑', () => editConnection(c)), button('测试', async () => {
        const r = await api(`/api/external-mcp/connections/${c.id}/test`, 'POST'); status('globalStatus', `连接成功，发现 ${r.toolCount} 个工具`);
      }), button('删除', async () => {
        if (!confirm(`删除连接“${c.connectionName}”？关联节点将失去该连接的工具。`)) return;
        await api(`/api/external-mcp/connections/${c.id}`, 'DELETE'); await refresh(); status('globalStatus', '连接已删除');
      }, true))
    ]));
    const old = $('toolSource').value; $('toolSource').replaceChildren(); option($('toolSource'), 'LOCAL · 本地工具', 'LOCAL');
    connections.filter(c => c.enabled).forEach(c => option($('toolSource'), c.connectionName, c.id));
    if ([...$('toolSource').options].some(o => o.value === old)) $('toolSource').value = old;
  }
  function renderNodes() {
    const fragment = document.createDocumentFragment();
    const labels = {CONNECTED:'已连接', CONNECTING:'连接中', RECONNECTING:'重连中', STOPPED:'已停止'};
    nodes.forEach(({node:n, runtime:r}) => {
      const card = element('article', undefined, 'card node-card');
      card.append(element('span', labels[r.state] || r.state, 'badge ' + (r.state === 'CONNECTED' ? 'online' : r.state === 'STOPPED' ? '' : 'waiting')),
        element('h3', n.name), element('p', endpointHost(n.endpoint), 'muted small'), element('p', r.message, 'muted small'));
      const ids = JSON.parse(n.connectionIds || '[]');
      card.append(element('p', `${n.localTools ? '本地工具 + ' : ''}${ids.length} 个外部 MCP`, 'small'));
      card.append(actions(button('编辑', () => editNode(n)), button('重连', async () => { await api(`/api/nodes/${n.id}/reconnect`, 'POST'); await refreshNodes(); }), button('删除', async () => {
        if (!confirm(`删除小智节点“${n.name}”？`)) return;
        await api(`/api/nodes/${n.id}`, 'DELETE'); await refreshNodes();
      }, true))); fragment.append(card);
    });
    if (!nodes.length) fragment.append(element('div', '还没有小智节点。新建一个节点，连接你的第一个小智。', 'empty'));
    $('nodeList').replaceChildren(fragment);
    $('nodeCount').textContent = nodes.length; $('onlineCount').textContent = nodes.filter(n => n.runtime.state === 'CONNECTED').length;
  }
  async function refreshNodes() { nodes = await api('/api/nodes'); renderNodes(); }
  async function refresh() {
    const result = await Promise.all([api('/api/external-mcp/connections'), api('/api/nodes')]);
    [connections, nodes] = result; $('connectionCount').textContent = connections.length;
    renderConnections(); renderNodes(); clearTools();
  }
  function openEditor(id) { $(id).hidden = false; $(id).scrollIntoView({behavior:'smooth', block:'start'}); }
  function editConnection(c) {
    const f = $('connectionForm'); f.reset();
    field(f,'id').value = c?.id || ''; field(f,'connectionName').value = c?.connectionName || '';
    field(f,'transport').value = c?.transport || 'SSE'; field(f,'url').value = c?.url || '';
    field(f,'endpoint').value = c?.endpoint || ''; field(f,'headers').value = JSON.stringify(JSON.parse(c?.headers || '{}'), null, 2);
    field(f,'enabled').checked = c?.enabled ?? true; status('connectionStatus');
    $('connectionEditorTitle').textContent = c ? '编辑连接' : '新建连接'; openEditor('connectionEditor');
  }
  function editNode(n) {
    const f = $('nodeForm'); f.reset(); field(f,'id').value = n?.id || '';
    field(f,'name').value = n?.name || ''; field(f,'endpoint').value = n?.endpoint || '';
    field(f,'enabled').checked = n?.enabled ?? true; field(f,'localTools').checked = n?.localTools ?? true;
    const chosen = new Set(JSON.parse(n?.connectionIds || '[]')); $('nodeBindings').replaceChildren();
    connections.forEach(c => {
      const label = element('label'), check = element('input'); check.type = 'checkbox'; check.value = c.id; check.name = 'binding'; check.checked = chosen.has(c.id);
      label.append(check, document.createTextNode(c.connectionName + (c.enabled ? '' : '（已禁用）'))); $('nodeBindings').append(label);
    });
    if (!connections.length) $('nodeBindings').append(element('p', '暂无外部连接，可先启用本地工具。', 'muted small'));
    status('nodeStatus'); $('nodeEditorTitle').textContent = n ? '编辑节点' : '新建节点'; openEditor('nodeEditor');
  }
  function renderArgs() {
    const tool = loadedTools.find(t => t.name === $('toolName').value); $('toolArgs').replaceChildren();
    $('toolDescription').textContent = tool?.description || ''; $('invokeTool').disabled = !tool;
    if (!tool) return;
    const schema = tool.inputSchema || {}, required = new Set(schema.required || []);
    Object.entries(schema.properties || {}).forEach(([key, def]) => {
      const type = def.type || 'string', label = element('label', `${def.description || key}（${key}）${required.has(key) ? ' *' : ''}`);
      let input;
      if (Array.isArray(def.enum) || type === 'boolean') {
        input = element('select'); if (!required.has(key)) option(input, '不传入', '');
        (def.enum || [false,true]).forEach(v => option(input, String(v), JSON.stringify(v)));
      } else if (['array','object'].includes(type)) { input = element('textarea'); input.placeholder = type === 'array' ? '[]' : '{}'; }
      else { input = element('input'); input.type = ['integer','number'].includes(type) ? 'number' : 'text'; if (type === 'number') input.step = 'any'; }
      input.dataset.key = key; input.dataset.type = type; input.dataset.enum = String(Array.isArray(def.enum) || type === 'boolean'); input.required = required.has(key);
      if (def.default !== undefined) input.value = (type === 'object' || type === 'array' || input.dataset.enum === 'true') ? JSON.stringify(def.default) : String(def.default);
      label.append(input); $('toolArgs').append(label);
    });
    if (!$('toolArgs').children.length) $('toolArgs').append(element('p', '此工具无需参数。', 'muted'));
  }
  function readArgs() {
    const result = {};
    $('toolArgs').querySelectorAll('[data-key]').forEach(input => {
      const raw = input.value; if (raw === '' && !input.required) return;
      const type = input.dataset.type; let value = raw;
      if (input.dataset.enum === 'true' || ['array','object'].includes(type)) value = JSON.parse(raw);
      else if (['integer','number'].includes(type)) { value = Number(raw); if (!Number.isFinite(value) || (type === 'integer' && !Number.isInteger(value))) throw new Error('请输入有效数字'); }
      if (type === 'array' && !Array.isArray(value)) throw new Error('参数必须为 JSON 数组');
      if (type === 'object' && (value === null || Array.isArray(value) || typeof value !== 'object')) throw new Error('参数必须为 JSON 对象');
      result[input.dataset.key] = value;
    }); return result;
  }
  async function loadUsers() {
    const result = await api('/api/admin/users?page=' + userPage); userTotal = result.total;
    table('userList', ['用户名','角色','状态','操作'], result.items.map(u => [u.username, u.role === 'ADMIN' ? '管理员' : '普通用户', u.enabled ? '已启用' : '已停用', actions(button('编辑 / 重置密码', () => editUser(u)))]));
    $('userPage').textContent = `第 ${userPage} 页 · 共 ${userTotal} 人`;
    $('prevUsers').disabled = userPage <= 1; $('nextUsers').disabled = userPage * 50 >= userTotal;
  }
  function editUser(u) {
    const f = $('userForm'); f.reset(); field(f,'id').value = u?.id || ''; field(f,'username').value = u?.username || '';
    field(f,'username').disabled = !!u; field(f,'role').value = u?.role || 'USER'; field(f,'enabled').checked = u?.enabled ?? true;
    field(f,'password').required = !u; $('userEditorTitle').textContent = u ? '编辑用户 · ' + u.username : '创建用户'; status('userStatus'); openEditor('userEditor');
  }
  async function workspace() {
    me = await api('/api/auth/me'); $('currentUsername').textContent = me.username; $('currentRole').textContent = me.role === 'ADMIN' ? '管理员' : '普通用户';
    $('usersNav').hidden = me.role !== 'ADMIN'; $('workspace').hidden = false;
    document.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => run(b,'globalStatus',async () => {
      activeTab = b.dataset.tab; document.querySelectorAll('[data-tab]').forEach(t => t.classList.toggle('active', t === b));
      document.querySelectorAll('[data-panel]').forEach(p => p.hidden = p.dataset.panel !== activeTab); $('pageTitle').textContent = b.textContent.slice(2).trim();
      if (activeTab === 'users') await loadUsers();
    })));
    $('logout').onclick = () => run($('logout'),'globalStatus',async () => { await api('/api/auth/logout','POST'); location.replace('/login.html'); });
    $('reload').onclick = () => run($('reload'),'globalStatus',async () => { await refresh(); if (activeTab === 'users') await loadUsers(); status('globalStatus','数据已更新'); });
    $('newNode').onclick = () => editNode(); $('newConnection').onclick = () => editConnection(); $('newUser').onclick = () => editUser();
    ['Node','Connection','User'].forEach(name => $('cancel'+name).onclick = () => $(name.toLowerCase()+'Editor').hidden = true);
    bindForm('nodeForm','nodeStatus',async f => {
      const id = field(f,'id').value;
      await api('/api/nodes' + (id ? '/'+id : ''),id ? 'PUT' : 'POST', {
        name:field(f,'name').value.trim(), endpoint:field(f,'endpoint').value.trim(), enabled:field(f,'enabled').checked, localTools:field(f,'localTools').checked,
        connectionIds:[...f.querySelectorAll('[name=binding]:checked')].map(c => Number(c.value))
      }); $('nodeEditor').hidden = true; await refreshNodes(); status('globalStatus','节点已保存');
    });
    bindForm('connectionForm','connectionStatus',async f => {
      const id = field(f,'id').value, headers = JSON.parse(field(f,'headers').value || '{}');
      if (!headers || Array.isArray(headers) || typeof headers !== 'object' || Object.values(headers).some(v => typeof v !== 'string')) throw new Error('请求头必须是字符串键值组成的 JSON 对象');
      await api('/api/external-mcp/connections'+(id ? '/'+id : ''),id ? 'PUT' : 'POST', {
        connectionName:field(f,'connectionName').value.trim(),url:field(f,'url').value.trim(), endpoint:field(f,'endpoint').value.trim(),transport:field(f,'transport').value,headers,enabled:field(f,'enabled').checked
      }); $('connectionEditor').hidden = true; await refresh(); status('globalStatus','连接已保存，可在小智节点中绑定');
    });
    $('testConnection').onclick = () => run($('testConnection'),'connectionStatus',async () => {
      const f = $('connectionForm');
      const result = await api('/api/external-mcp/connections/test','POST', {
        connectionName:field(f,'connectionName').value.trim() || 'draft-test', url:field(f,'url').value.trim(),
        endpoint:field(f,'endpoint').value.trim(),transport:field(f,'transport').value,headers:JSON.parse(field(f,'headers').value || '{}'),enabled:true
      }); status('connectionStatus',`连通成功，发现 ${result.toolCount} 个工具`);
    });
    $('refreshRuntime').onclick = () => run($('refreshRuntime'),'globalStatus',async () => { await api('/api/external-mcp/connections/refresh','POST'); clearTools(); status('globalStatus','当前账号的工具缓存已刷新'); });
    $('toolSource').onchange = clearTools; $('toolName').onchange = renderArgs;
    $('loadTools').onclick = () => run($('loadTools'),'toolStatus',async () => {
      clearTools(); const source = $('toolSource').value;
      const result = await api(source === 'LOCAL' ? '/api/external-mcp/tools/local' : `/api/external-mcp/connections/${source}/tools`);
      if (source !== $('toolSource').value) return;
      loadedSource = source; loadedTools = result.tools || []; $('toolName').replaceChildren();
      loadedTools.forEach(t => option($('toolName'), t.name, t.name)); if (!loadedTools.length) option($('toolName'),'没有可用工具','');
      status('toolStatus', `已加载 ${loadedTools.length} 个工具`); renderArgs();
    });
    bindForm('invokeForm','toolStatus',async () => {
      if (loadedSource !== $('toolSource').value || !loadedTools.some(t => t.name === $('toolName').value)) throw new Error('请先加载当前来源的工具');
      const source = loadedSource;
      const result = await api(source === 'LOCAL' ? '/api/external-mcp/tools/local/invoke' : `/api/external-mcp/connections/${source}/tools/invoke`, 'POST', {toolName:$('toolName').value,arguments:readArgs()});
      $('invokeResult').value = JSON.stringify(result,null,2); status('toolStatus', result.isError ? '工具返回执行错误，详情见结果' : '工具执行完成', !result.isError);
    });
    bindForm('userForm','userStatus',async f => {
      const id = field(f,'id').value, body = {username:field(f,'username').value,role:field(f,'role').value,enabled:field(f,'enabled').checked};
      if (field(f,'password').value) body.password = field(f,'password').value;
      await api('/api/admin/users'+(id ? '/'+id : ''),id ? 'PATCH' : 'POST',body); $('userEditor').hidden = true;
      if (String(me.id) === id) { location.replace('/login.html'); return; }
      await loadUsers(); status('globalStatus','用户已保存');
    });
    $('prevUsers').onclick = () => { if (userPage > 1) { userPage--; run($('prevUsers'),'globalStatus',loadUsers); } };
    $('nextUsers').onclick = () => { if (userPage * 50 < userTotal) { userPage++; run($('nextUsers'),'globalStatus',loadUsers); } };
    bindForm('passwordForm','passwordStatus',async f => { await api('/api/auth/password','POST',{oldPassword:field(f,'oldPassword').value,newPassword:field(f,'newPassword').value}); location.replace('/login.html'); });
    await refresh();
    setInterval(async () => {
      if (polling || document.hidden || activeTab !== 'nodes') return; polling = true;
      try { await refreshNodes(); } catch (ex) { status('globalStatus',ex.message,false); } finally { polling = false; }
    },15000);
  }
  async function login() {
    let registering = false;
    const options = await api('/api/auth/options'); $('toggleRegister').hidden = !options.registrationEnabled;
    $('toggleRegister').onclick = () => {
      registering = !registering; $('loginTitle').textContent = registering ? '创建个人账号' : '登录工作空间';
      $('loginHint').textContent = registering ? '用户名为 3–64 位英文字符；密码至少 12 位。' : '使用管理员分配的账号登录。';
      $('loginSubmit').textContent = registering ? '注册' : '登录'; $('toggleRegister').textContent = registering ? '返回登录' : '创建账号';
      field($('loginForm'),'password').minLength = registering ? 12 : 1; field($('loginForm'),'password').autocomplete = registering ? 'new-password' : 'current-password'; status('loginStatus');
    };
    bindForm('loginForm','loginStatus',async f => {
      const data = {username:field(f,'username').value.trim(),password:field(f,'password').value};
      if (registering) { await api('/api/auth/register','POST',data); $('toggleRegister').click(); status('loginStatus','注册成功，请登录'); return; }
      await api('/api/auth/login','POST',data); csrf = null; location.replace('/mcp-admin.html');
    });
  }
  (async () => { try { await token(); await (loginPage ? login() : workspace()); } catch (ex) { status(loginPage ? 'loginStatus' : 'globalStatus',ex.message,false); if (!loginPage) $('workspace').hidden = false; } })();
})();
