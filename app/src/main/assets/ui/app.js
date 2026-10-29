'use strict';
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=(name,cls='')=>`<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
let state={hosts:[],chats:[],theme:'dark',connections:{},trustPrompts:[]};
let hostId='',chatId='',search='',collapsed=new Set(),pendingSend=new Map(),drafts=new Map(),toastTimer,dialogKind='',lastMessages='';
let activeTrust=null,returnDialog=null,connectionTest=null;
const imageDrafts=new Map();
const draftKey=()=>chatId||'host-'+hostId;
const currentImages=()=>imageDrafts.get(draftKey())||[];
function imageHTML(images){return (images||[]).filter(i=>/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(i.preview||'')).map(i=>`<img class="chat-image" src="${esc(i.preview)}" alt="图片 ${Number(i.width)||''} × ${Number(i.height)||''}" loading="lazy">`).join('');}
function renderImages(){const box=$('#image-previews');if(box)box.innerHTML=currentImages().map(i=>`<div class="attachment-preview">${imageHTML([i])}<button data-action="removeImage" data-id="${esc(i.id)}" aria-label="移除图片">×</button></div>`).join('');}
const host=()=>state.hosts.find(h=>h.id===hostId);
const chat=()=>state.chats.find(c=>c.id===chatId);
const connection=id=>state.connections?.[id]||{status:'offline',busyChat:''};
const statusText=s=>({online:'已连接',offline:'未连接',connecting:'正在连接',error:'连接失败'}[s]||'未连接');
function connectionNotice(con){
  if(con.status==='connecting')return `<div class="connection-progress"><span class="pulse"></span>${esc(con.phase||'正在建立 SSH 连接')}</div>`;
  if(con.error)return `<div class="connection-problem"><strong>连接未完成</strong><div>${esc(con.error)}</div><button class="text-button" data-action="hostInfo">查看连接设置</button></div>`;
  return '';
}
function native(a){if(window.Android?.action)window.Android.action(JSON.stringify(a));else toast('浏览器仅用于界面预览；SSH 连接请安装安卓 APK');}
function toast(text){$('#toast').textContent=text;$('#toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').classList.remove('show'),5500);}
function rememberDraft(){if(chatId)drafts.set(chatId,$('#prompt').value);}
function selectHost(id){rememberDraft();hostId=id;chatId='';$('#prompt').value='';lastMessages='';render();closeSidebar();}
function selectChat(id){rememberDraft();const c=state.chats.find(c=>c.id===id);if(!c)return;chatId=id;hostId=c.hostId;$('#prompt').value=drafts.get(id)||'';resize();lastMessages='';render();closeSidebar();if(c.needsHistory)native({type:'loadHistory',chatId:id});}
function closeSidebar(){document.body.classList.remove('sidebar-open');}
function render(){
  document.documentElement.dataset.theme=state.theme;
  if(!state.hosts.some(h=>h.id===hostId)){hostId=state.hosts[0]?.id||'';chatId='';}
  if(chatId&&!state.chats.some(c=>c.id===chatId))chatId='';
  renderSidebar();renderHeader();renderConversation();renderComposer();
}
function renderSidebar(){
  $('#hosts').innerHTML=state.hosts.length?state.hosts.map(h=>{
    const con=connection(h.id),isClosed=collapsed.has(h.id)&&!search;
    const cs=state.chats.filter(c=>c.hostId===h.id&&c.title.toLowerCase().includes(search.toLowerCase())).sort((a,b)=>b.updatedAt-a.updatedAt);
    return `<div class="host-row ${h.id===hostId?'selected':''}"><button class="host-select" data-action="selectHost" data-id="${esc(h.id)}">${icon('server')}<span class="host-name">${esc(h.name)}</span><span class="dot ${esc(con.status)}"></span></button><button class="icon" data-action="collapseHost" data-id="${esc(h.id)}" aria-label="展开或折叠">${icon('chevron',isClosed?'':'expanded')}</button></div>${isClosed?'':`<div class="chat-list">${cs.length?cs.map(c=>`<div class="chat-entry ${c.id===chatId?'active':''}"><button class="chat-select" data-action="selectChat" data-id="${esc(c.id)}">${icon('chat')}<span>${esc(c.title||'远程聊天')}</span>${c.status==='working'?'<i class="dot online"></i>':''}</button><button class="chat-more" data-action="chatMenu" data-id="${esc(c.id)}" aria-label="聊天选项">···</button></div>`).join(''):`<div class="empty-chats">${search?'没有匹配的聊天':'还没有聊天'} <button class="text-button" data-action="newChat" data-id="${esc(h.id)}">新建</button></div>`}</div>`}`;
  }).join(''):'<div class="sidebar-empty">添加第一台虚拟机<br>把远程工作放进口袋<div class="space-8"></div><button class="text-button" data-action="addHost">＋ 添加虚拟机</button></div>';
}
function renderHeader(){
  const h=host(),c=chat(),con=connection(hostId);
  $('#host-pill').innerHTML=icon('server')+esc(h?.name||'添加虚拟机');
  $('#connection-status').innerHTML=`<span class="dot ${esc(con.status)}"></span>${esc(con.status==='connecting'?(con.phase||'正在连接'):statusText(con.status))}`;
  $('#workspace-bar').innerHTML=icon('folder')+`<span>${esc(c?.cwd||connection(hostId).cwd||h?.cwd||'自动（服务器主目录）')}</span>`;
}
function welcomeHTML(){
  const h=host(),con=connection(hostId);
  return `<div class="welcome-overline">${icon('code')}YOUR WORK, ANYWHERE</div><h1 class="welcome-title">从一个想法，<br>开始今天的工作。</h1><p class="welcome-subtitle">${h?'连接你的虚拟机，让 Codex 继续推进。':'把 Linux 服务器接入这里，用对话开启远程开发。'}<br>写代码、排查问题，或把下一步交给它。</p><div class="welcome-host">${icon('server')}<div><strong>${esc(h?.name||'连接第一台虚拟机')}</strong><small>${esc(h?`${h.user}@${h.host}:${h.port}`:'通过 SSH 安全连接 Linux 服务器')}</small></div><button class="connect-button" data-action="${h?'connect':'addHost'}" ${con.status==='connecting'?'disabled':''}>${h?(con.status==='online'?'已连接':con.status==='connecting'?'连接中…':'连接'):'添加连接'} ${con.status==='online'?'✓':'↗'}</button></div><div class="suggestions">${[
    ['code','了解这个项目','梳理项目结构与启动方式','查看当前工作目录，介绍项目结构、技术栈和启动方式，先不要修改文件。'],
    ['terminal','排查运行问题','从日志和配置找到原因','检查当前项目的运行情况和错误日志，分析可能的问题，先给我排查结果。'],
    ['edit','开始一个新功能','把需求拆成可执行的步骤','阅读当前项目代码，帮我规划一个新功能。先了解项目，再问我具体需求。'],
    ['shield','检查最近改动','审查代码，发现潜在问题','检查当前目录的 Git 变更，指出潜在问题与风险，先不要修改或提交代码。']
  ].map(([i,t,d,p])=>`<button class="suggestion" data-action="suggest" data-prompt="${esc(p)}">${icon(i)}<span><strong>${t}</strong><small>${d}</small></span><span class="suggest-arrow">↗</span></button>`).join('')}</div>`;
}
function markdown(text){
  return window.renderMarkdown(text);
}
const approvalLabels={'on-request':'按需审批',untrusted:'严格审批',never:'不询问'};
function approvalMode(){return chat()?.approvalMode||state.approvalMode||'on-request';}
function remoteImagesHTML(m){
  return (m.remoteImages||[]).map((i,index)=>{
    const ready=/^data:image\/(jpeg|png);base64,[A-Za-z0-9+/=]+$/.test(i.preview||'');
    return `<button class="remote-image-card" data-action="${ready?'viewRemoteImage':'loadRemoteImage'}" data-id="${esc(m.id)}" data-index="${index}" ${i.status==='loading'?'disabled':''} aria-label="${ready?'查看大图':'加载图片'}">${ready?`<img class="received-image" src="${esc(i.preview)}" alt="${esc(i.name||'服务器图片')}" loading="lazy">`:icon('folder')}<span>${esc(i.name||'服务器图片')}</span><small>${ready?'查看大图':i.status==='failed'?'加载失败，点击重试':i.status==='loading'?'正在接收图片…':'加载图片'}</small></button>`;
  }).join('');
}
function approvalModeDialog(){
  const descriptions={'on-request':'由 Codex 判断何时请求审批。',untrusted:'除受信任的只读操作外，请求执行审批。',never:'不弹出执行审批；超出当前权限的操作会失败。'};
  openDialog('approvalMode',`${head('审批模式')}<div class="dialog-body"><p class="dialog-description">${chat()?.status==='working'?'当前任务继续使用原模式，选择从下一次任务生效。':'选择当前聊天的审批方式，新聊天沿用最近选择。'}</p>${Object.entries(approvalLabels).map(([value,label])=>`<button class="approval-mode-option ${approvalMode()===value?'chosen':''}" data-action="setApprovalMode" data-value="${value}"><strong>${label}</strong><small>${descriptions[value]}</small></button>`).join('')}</div>`);
}
function messageHTML(m){
  if(m.kind==='approval'){
    const p=m.params||{},qs=p.questions||[],isQuestion=m.approvalType==='item/tool/requestUserInput';
    return `<article class="message system"><div class="approval"><div class="approval-heading">${icon('shield')}${m.resolved?'请求已处理':isQuestion?'Codex 想确认一些信息':'等待你的审批'}</div>${isQuestion?qs.map((q,i)=>`<label for="q-${esc(m.id)}-${i}">${esc(q.header||'')} ${esc(q.question||'')}</label>${q.options?.length?`<div class="question-hint">${q.options.map(o=>esc(o.label)+(o.description?'：'+esc(o.description):'')).join(' / ')}</div>`:''}${!m.resolved?`<input id="q-${esc(m.id)}-${i}" data-question="${esc(q.id)}" data-token="${esc(m.id)}" placeholder="输入回答" autocomplete="off">`:''}`).join(''):`<pre>${esc(m.text)}${p.cwd?'\n\n工作目录：'+esc(p.cwd):''}</pre>`}${m.resolved?`<div class="approval-resolved">${esc(m.resolution||'连接或请求已结束')}</div>`:`<div class="approval-actions">${isQuestion?`<button class="btn primary" data-action="answer" data-token="${esc(m.id)}">提交回答</button>`:`<button class="btn primary" data-action="approve" data-token="${esc(m.id)}">允许本次</button><button class="btn" data-action="decline" data-token="${esc(m.id)}">拒绝</button>`}</div>`}</div></article>`;
  }
  if(m.kind==='error')return `<article class="message system"><div class="error-message">${esc(m.text)}</div></article>`;
  if(m.role==='tool')return `<article class="message tool"><details class="tool-box" data-item="${esc(m.id)}"><summary>${icon(m.kind==='files'?'folder':'terminal')}<span class="tool-command" title="${esc(m.command||'')}">${esc(m.kind==='files'?'文件改动':(m.command||'命令输出').replace(/\s+/g,' '))}</span><span class="tool-state">${esc(m.exitCode!=null?'exit '+m.exitCode:m.status==='completed'?'完成':m.status==='failed'?'失败':'执行中')}</span></summary>${m.command?`<pre class="tool-full-command">${esc(m.command)}</pre>`:''}<pre>${esc(m.text||'等待输出…')}</pre></details></article>`;
  if(m.role==='user')return `<article class="message user"><div class="message-content">${imageHTML(m.images)}${m.steering?'<small class="steering-label">任务引导</small>':''}${esc(m.text)}</div></article>`;
  return `<article class="message assistant"><div class="assistant-heading"><span class="mini-logo">${icon('code')}</span>Codex</div><div class="message-content">${markdown(m.text)}<div class="received-images">${remoteImagesHTML(m)}</div></div><div class="message-actions"><button class="icon" data-action="copy" data-id="${esc(m.id)}" aria-label="复制回答">${icon('copy')}</button></div></article>`;
}
function renderConversation(){
  const c=chat(),ms=c?.messages||[];const welcome=ms.length===0;
  $('#welcome').hidden=!welcome;if(welcome)$('#welcome').innerHTML=welcomeHTML();
  if(welcome){
    const con=connection(hostId),card=$('#welcome .welcome-host');
    if(card)card.insertAdjacentHTML('afterend',connectionNotice(con));
    const button=$('#welcome .connect-button');
    if(button&&con.status==='connecting'){button.disabled=false;button.dataset.action='disconnect';button.textContent='取消连接';}
  }
  const key=JSON.stringify([chatId,ms]);
  if(key!==lastMessages){
    const pane=$('#conversation'),bottom=pane.scrollHeight-pane.scrollTop-pane.clientHeight<90;
    const opened=new Set([...document.querySelectorAll('details[open][data-item]')].map(e=>e.dataset.item));
    const answerDrafts=new Map([...document.querySelectorAll('input[data-question]')].map(e=>[e.id,e.value]));
    const focused=document.activeElement?.id;
    const oldScroll=pane.scrollTop;
    $('#messages').innerHTML=ms.map(messageHTML).join('');
    document.querySelectorAll('details[data-item]').forEach(e=>{e.open=opened.has(e.dataset.item);});
    document.querySelectorAll('input[data-question]').forEach(e=>{e.value=answerDrafts.get(e.id)||'';if(e.id===focused)e.focus();});
    if(bottom||!lastMessages)requestAnimationFrame(()=>pane.scrollTop=pane.scrollHeight);else pane.scrollTop=oldScroll;
    lastMessages=key;
  }
  $('#working').hidden=c?.status!=='working';
}
function renderComposer(){
  const c=chat(),h=host(),con=connection(hostId),working=c?.status==='working';
  $('#mode-label').textContent=(approvalLabels[approvalMode()]||approvalLabels['on-request'])+' ▾';$('#mode-label').disabled=!h;
  $('#send').innerHTML=icon('arrow');$('#send').classList.remove('stop');$('#stop-task').hidden=!working;renderImages();
  $('#send').setAttribute('aria-label',working?'发送引导':'发送消息');
  $('#send').disabled=!h||pendingSend.has(chatId)||!!(con.busyChat&&con.busyChat!==chatId)||(!$('#prompt').value.trim()&&!currentImages().length);$('#pick-images').disabled=!h||currentImages().length>=4;
  $('#prompt').disabled=!h;
  $('#prompt').placeholder=h?(working?'补充要求，引导当前任务…':'告诉 Codex 你想做什么…'):'先添加一台虚拟机，开始对话';
  const hint=con.status==='error'?'连接失败，请查看连接设置中的错误详情':con.status==='connecting'?(con.phase||'正在建立 SSH 连接'):con.busyChat&&con.busyChat!==chatId?'这台虚拟机有其他聊天正在运行':pendingSend.has(chatId)?'正在连接并发送…':c?.status==='interrupted'?'连接已中断，可刷新远程记录后继续':h?`${h.name}  /  ${c?.cwd||con.cwd||h.cwd||'自动识别'}`:'SSH 直连服务器，无需额外中转服务';
  $('#composer-context').innerHTML=`<span class="${c?.status==='interrupted'?'warning':''}">${esc(hint)}</span>`;
}
function resize(){const t=$('#prompt');t.style.height='auto';t.style.height=Math.min(180,t.scrollHeight)+'px';}
function openDialog(kind,html){dialogKind=kind;$('#dialog-content').innerHTML=html;if(!$('#dialog').open)$('#dialog').showModal();}
function head(title){return `<div class="dialog-head"><h2>${esc(title)}</h2><button class="icon" data-action="closeDialog" aria-label="关闭">${icon('close')}</button></div>`;}
function closeDialog(){
  if(activeTrust){native({type:'trust',hostId:activeTrust.hostId,fingerprint:activeTrust.fingerprint,accept:false});activeTrust=null;}
  if(returnDialog){const saved=returnDialog;returnDialog=null;$('#dialog-content').replaceChildren(...saved.nodes);dialogKind=saved.kind;$('#dialog').scrollTop=saved.scroll;renderTestStatus();return;}
  if(connectionTest?.running){native({type:'cancelConnectionTest',testId:connectionTest.id});}connectionTest=null;
  $('#dialog').close();dialogKind='';
}
function renderTestStatus(){
  const box=$('#connection-test-result'),button=$('#test-connection');if(!box||!button)return;
  const test=connectionTest;button.textContent=test?.running?'取消测试':'测试连接';button.disabled=false;
  box.hidden=!test;if(!test)return;
  box.className='connection-test-result '+(test.running?'pending':test.ok?'success':'failed');
  box.textContent=test.running?(state.connectionTests?.[test.id]?.phase||'正在测试 SSH 与 Codex 连接…'):test.ok?`连接成功：SSH 认证和 Codex 握手已通过\n目录：${test.cwd||'自动识别'}\nCodex：${test.codex||'自动查找'}`:test.error||'测试已取消';
}
function editor(id=''){
  if(connectionTest?.running)native({type:'cancelConnectionTest',testId:connectionTest.id});connectionTest=null;
  const h=state.hosts.find(x=>x.id===id)||{name:'',host:'',port:22,user:'ubuntu',cwd:'',codex:'',auth:'key'};
  openDialog('host',`${head(id?'编辑虚拟机':'添加虚拟机')}<form id="host-form" data-id="${esc(id)}"><div class="dialog-body"><p class="dialog-description">每台 Linux 服务器都是独立工作区，拥有自己的聊天记录。</p><div class="field-grid"><label class="field full">工作区名称<input name="name" value="${esc(h.name)}" placeholder="例如：开发服务器" required maxlength="60"></label><label class="field">服务器地址<input name="host" value="${esc(h.host)}" placeholder="IP 或域名" required autocomplete="off" autocapitalize="off" spellcheck="false"></label><label class="field">SSH 端口<input name="port" type="number" value="${h.port}" required min="1" max="65535"></label><label class="field">登录用户名<input name="user" value="${esc(h.user)}" required autocomplete="off" autocapitalize="off" spellcheck="false"></label><label class="field">认证方式<select name="auth" id="auth-select"><option value="key" ${h.auth==='key'?'selected':''}>SSH 私钥</option><option value="password" ${h.auth==='password'?'selected':''}>密码</option></select></label><label class="field full" id="password-field" ${h.auth!=='password'?'hidden':''}>SSH 密码<input name="password" type="password" autocomplete="new-password" placeholder="${h.hasSecret?'已保存；留空保留原值':'输入 SSH 登录密码'}"></label><div class="full" id="key-fields" ${h.auth!=='key'?'hidden':''}><label class="field">SSH 私钥<textarea name="privateKey" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="${h.hasSecret?'已保存；留空保留原值':'粘贴完整的 OpenSSH / PEM 私钥'}"></textarea></label><div class="space-8"></div><label class="field">私钥口令（可选）<input name="passphrase" type="password" autocomplete="new-password" placeholder="${h.hasSecret?'留空保留原值':'私钥的解密口令'}"></label></div><details class="full advanced-paths"><summary>高级路径设置（通常无需填写）</summary><label class="field full">默认工作目录<input name="cwd" value="${esc(h.cwd||'')}" placeholder="自动使用服务器用户主目录" autocomplete="off" autocapitalize="off" spellcheck="false"><small>留空自动识别；填写项目路径时目录须已存在。</small></label><label class="field full">Codex 可执行文件<input name="codex" value="${esc(h.codex||'')}" placeholder="自动查找 Codex，无需填写" autocomplete="off" autocapitalize="off" spellcheck="false"><small>自动搜索 PATH、常见安装目录及 nvm。服务器需已安装并登录 Codex。</small></label></details></div><div class="test-description">测试 SSH 认证、目录识别和 Codex 握手，不保存当前修改，也不打断已有聊天连接。</div><div id="connection-test-result" class="connection-test-result" role="status" aria-live="polite" hidden></div><div id="form-error" class="dialog-error"></div></div><div class="dialog-actions"><button type="button" class="btn" data-action="closeDialog">取消</button><button type="button" class="btn" data-action="testConnection" id="test-connection">测试连接</button><button class="btn primary" type="submit" id="save-host">保存连接</button></div></form>`);
}
function newChat(id=hostId){
  if(!state.hosts.length){editor();return;}
  const h=state.hosts.find(h=>h.id===id)||state.hosts[0];
  openDialog('newChat',`${head('新建聊天')}<form id="chat-form"><div class="dialog-body"><p class="dialog-description">选择虚拟机与工作目录，开始一段独立的 Codex 会话。</p><div class="field-grid"><label class="field full">虚拟机<select name="hostId" id="new-host">${state.hosts.map(x=>`<option value="${esc(x.id)}" ${x.id===h.id?'selected':''}>${esc(x.name)}</option>`).join('')}</select></label><label class="field full">工作目录<input name="cwd" id="new-cwd" value="${esc(h.cwd||'')}" placeholder="自动使用服务器用户主目录" autocapitalize="off" spellcheck="false"></label></div><div id="form-error" class="dialog-error"></div></div><div class="dialog-actions"><button type="button" class="btn" data-action="closeDialog">取消</button><button class="btn primary">创建聊天</button></div></form>`);
}
function settings(){
  openDialog('settings',`${head('设置')}<div class="dialog-body"><div class="settings-item"><div><strong>外观</strong><small>选择适合你的界面主题</small></div><div class="theme-choices"><button data-action="theme" data-value="dark" class="${state.theme==='dark'?'chosen':''}">深色</button><button data-action="theme" data-value="light" class="${state.theme==='light'?'chosen':''}">浅色</button></div></div><div class="settings-item"><div><strong>连接管理</strong><small>通过 SSH 直连 Linux 云服务器</small></div><button class="text-button" data-action="addHost">＋ 添加虚拟机</button></div>${state.hosts.map(h=>`<div class="host-card">${icon('server')}<div><strong>${esc(h.name)}</strong><small>${esc(h.user+'@'+h.host+':'+h.port)}</small></div><button class="icon" data-action="editHost" data-id="${esc(h.id)}" aria-label="编辑连接">${icon('edit')}</button></div>`).join('')}<div class="settings-item"><div><strong>导出调试日志</strong><small>仅保留最近 24 小时，不记录私钥和聊天正文</small></div><button class="btn" data-action="exportLogs">导出</button></div><div class="settings-title">后台连接</div><div class="settings-item"><div><strong>保持连接与自动重连</strong><small>已连接的服务器在后台继续保持；网络恢复后自动重连。主动断开会停止重连。</small></div>${icon('sync')}</div><div class="settings-item"><div><strong>${state.batteryUnrestricted?'已允许后台运行':'允许后台运行'}</strong><small>连接期间保活会增加耗电。vivo 等机型还需在系统中允许自启动和后台高耗电。</small></div><button class="btn" data-action="backgroundSettings">设置</button></div><div class="settings-title">数据与安全</div><div class="settings-item"><div><strong>本地加密存储</strong><small>密码、私钥和聊天记录通过 Android 密钥库加密</small></div>${icon('shield')}</div><div class="settings-item"><div><strong>断开全部连接</strong><small>运行中的任务可能中断，服务器文件改动会保留</small></div><button class="btn" data-action="disconnectAll">断开</button></div><p class="about-note">Codex Pocket 0.4.1 · 独立客户端，非 OpenAI 官方应用。<br>聊天任务使用服务器上 Codex 的账号和配置。自动恢复 SSH 连接，中断任务不会自动重发。系统强制停止或厂商后台限制仍可能断开连接。</p></div>`);
}
function hostInfo(id=hostId){
  const h=state.hosts.find(h=>h.id===id);if(!h){editor();return;}const con=connection(id);
  openDialog('hostInfo',`${head(h.name)}<div class="dialog-body"><div class="host-details">状态：<b>${statusText(con.status)}</b><br>地址：<b>${esc(h.user+'@'+h.host+':'+h.port)}</b><br>目录：<b>${esc(h.cwd||con.cwd||'自动（服务器主目录）')}</b><br>Codex：<b>${esc(h.codex||connection(id).codex||'自动查找')}</b></div><div class="settings-title">已固定的 SSH 主机指纹</div><div class="fingerprint">${esc(h.fingerprint||'首次连接时核对并保存')}</div><p class="about-note">如果服务器重新安装导致密钥变化，连接将被阻止。请在云控制台核对；确认后删除本地连接并重新添加。</p><div class="settings-item"><div><strong>导入服务器聊天记录</strong><small>读取这台服务器上已有的 Codex 会话</small></div><button class="btn" data-action="syncHistory" data-id="${esc(id)}">导入</button></div></div><div class="dialog-actions"><button class="btn danger" data-action="confirmDeleteHost" data-id="${esc(id)}">删除</button><button class="btn" data-action="editHost" data-id="${esc(id)}">编辑</button><button class="btn primary" data-action="${con.status==='online'?'disconnect':'connect'}" data-id="${esc(id)}">${con.status==='online'?'断开连接':'连接'}</button></div>`);
  const details=$('#dialog .host-details');
  if(details&&con.error)details.insertAdjacentHTML('afterend',`<div class="connection-problem"><strong>连接错误</strong><div>${esc(con.error)}</div></div>`);
  if(con.status==='connecting'){const b=$('#dialog .dialog-actions .primary');if(b){b.dataset.action='disconnect';b.textContent='取消连接';}}
}
function chatMenu(id){
  const c=state.chats.find(c=>c.id===id);if(!c)return;
  openDialog('chatMenu',`${head('聊天选项')}<form id="rename-form" data-id="${esc(id)}"><div class="dialog-body"><label class="field">聊天名称<input name="title" value="${esc(c.title)}" required maxlength="100"></label><p class="about-note">${esc(c.cwd)}${c.remoteId?'<br>远程会话：'+esc(c.remoteId):''}</p></div><div class="dialog-actions"><button type="button" class="btn danger" data-action="confirmDeleteChat" data-id="${esc(id)}">删除本地记录</button>${c.remoteId?`<button type="button" class="btn" data-action="loadHistory" data-id="${esc(id)}">刷新远程记录</button>`:''}<button class="btn primary">保存名称</button></div></form>`);
}
function confirmDialog(title,text,action,id){openDialog('confirm',`${head(title)}<div class="dialog-body"><p class="dialog-description">${esc(text)}</p></div><div class="dialog-actions"><button class="btn" data-action="closeDialog">取消</button><button class="btn danger" data-action="${action}" data-id="${esc(id)}">确认${title}</button></div>`);}
function trustPrompt(p){
  if(activeTrust?.hostId===p.hostId)return;
  if(dialogKind&&dialogKind!=='trust')returnDialog={kind:dialogKind,nodes:[...$('#dialog-content').childNodes],scroll:$('#dialog').scrollTop};
  activeTrust=p;
  openDialog('trust',`${head('核对服务器身份')}<div class="dialog-body"><p class="dialog-description">首次连接 ${esc(p.name)}。请在云服务器控制台核对 SSH 主机指纹，确认是你的服务器后再信任。</p><div class="fingerprint">${esc(p.fingerprint)}</div><p class="about-note">地址：${esc(p.address)}<br>信任后将固定此密钥；密钥变化会阻止连接。</p></div><div class="dialog-actions"><button class="btn" data-action="rejectTrust">取消连接</button><button class="btn primary" data-action="acceptTrust">核对无误，信任</button></div>`);
}
function send(){
  const text=$('#prompt').value.trim(),images=currentImages();if((!text&&!images.length)||!host())return;
  if(!chatId){drafts.set('__new',{text,images});native({type:'newChat',hostId,cwd:host().cwd});return;}
  if(pendingSend.has(chatId)||(connection(hostId).busyChat&&connection(hostId).busyChat!==chatId))return;
  const type=chat()?.status==='working'?'steer':'send';pendingSend.set(chatId,{text,images});drafts.set(chatId,'');imageDrafts.set(draftKey(),[]);$('#prompt').value='';resize();renderComposer();native({type,chatId,text,images:images.map(i=>({id:i.id}))});
}
window.receive=function(e){
  if(e.type==='state'){
    if(e.state.error){toast(e.state.error);return;}state=e.state;render();renderTestStatus();
    if(activeTrust&&!state.trustPrompts?.some(p=>p.hostId===activeTrust.hostId)){activeTrust=null;if(dialogKind==='trust')closeDialog();}
    const p=state.trustPrompts?.[0];if(p&&!activeTrust)trustPrompt(p);
  }else if(e.type==='imagesSelected'){const existing=imageDrafts.get(e.draftKey)||[];const all=[...existing,...e.images];for(const item of all.slice(4))native({type:'removeImage',id:item.id});imageDrafts.set(e.draftKey,all.slice(0,4));renderComposer();}
  else if(e.type==='remoteImageOpened'){
    const original=/^https:\/\/app\.codexpocket\.local\/images\/[a-f0-9-]{36}\/original$/.test(e.url||'');
    const preview=/^data:image\/(jpeg|png);base64,[A-Za-z0-9+/=]+$/.test(e.preview||'');
    if(e.chatId===chatId&&(original||preview))openDialog('image',`${head('图片')}<div class="dialog-body image-viewer"><img src="${esc(original?e.url:e.preview)}" alt="图片"></div>${e.messageId!=null?`<div class="dialog-actions"><button class="btn primary" data-action="saveRemoteImage" data-id="${esc(e.messageId)}" data-index="${Number(e.index)}">保存原图</button></div>`:''}`);
  }
  else if(e.type==='connectionTestResult'){if(connectionTest?.id===e.testId){connectionTest={...connectionTest,...e,running:false};renderTestStatus();}}
  else if(e.type==='hostSaved'){hostId=e.hostId;chatId='';closeDialog();toast('虚拟机已保存');}
  else if(e.type==='chatCreated'){
    if(e.state)state=e.state;chatId=e.chatId;hostId=e.hostId;lastMessages='';closeDialog();closeSidebar();
    const draft=drafts.get('__new');if(draft){drafts.delete('__new');imageDrafts.delete('host-'+hostId);pendingSend.set(chatId,draft);$('#prompt').value='';native({type:'send',chatId,text:draft.text,images:draft.images.map(i=>({id:i.id}))});}else $('#prompt').value='';resize();render();
  }else if(e.type==='actionComplete'){
    if(['send','steer'].includes(e.action)){pendingSend.delete(e.chatId);renderComposer();}
  }else if(e.type==='actionError'){
    if(['send','steer'].includes(e.action)){const draft=pendingSend.get(e.chatId);pendingSend.delete(e.chatId);if(draft){drafts.set(e.chatId,draft.text);imageDrafts.set(e.chatId,draft.images);if(e.chatId===chatId){$('#prompt').value=draft.text;resize();}}}
    if(['saveHost','newChat'].includes(e.action)&&$('#form-error')){$('#form-error').textContent=e.message;const b=$('#save-host');if(b)b.disabled=false;}else toast(e.message);
    renderComposer();
  }else if(e.type==='trust')trustPrompt(e);
  else if(e.type==='toast')toast(e.message);
};
window.back=function(){if($('#dialog').open){closeDialog();return;}if(document.body.classList.contains('sidebar-open')){closeSidebar();return;}document.body.classList.add('sidebar-open');};
document.addEventListener('click',e=>{
  const link=e.target.closest('.message-content a[href]');if(link){e.preventDefault();native({type:'openLink',url:link.href});return;}
  const b=e.target.closest('[data-action]');if(!b)return;const a=b.dataset.action,id=b.dataset.id;
  switch(a){
    case 'sidebar':document.body.classList.toggle('sidebar-open');break;
    case 'newChat':newChat(id||hostId);break;
    case 'addHost':editor();break;
    case 'editHost':editor(id);break;
    case 'selectHost':selectHost(id);break;
    case 'selectChat':selectChat(id);break;
    case 'collapseHost':collapsed.has(id)?collapsed.delete(id):collapsed.add(id);renderSidebar();break;
    case 'settings':settings();break;
    case 'approvalMode':approvalModeDialog();break;
    case 'setApprovalMode':native({type:'approvalMode',chatId,value:b.dataset.value});closeDialog();break;
    case 'loadRemoteImage':case 'viewRemoteImage':case 'saveRemoteImage':native({type:a,chatId,messageId:id,index:Number(b.dataset.index)});break;
    case 'pickImages':native({type:'pickImages',draftKey:draftKey()});break;
    case 'removeImage':imageDrafts.set(draftKey(),currentImages().filter(i=>i.id!==id));native({type:'removeImage',id});renderComposer();break;
    case 'stopTask':native({type:'stop',chatId});break;
    case 'exportLogs':native({type:'exportLogs'});break;
    case 'backgroundSettings':native({type:'backgroundSettings'});break;
    case 'testConnection':{
      if(connectionTest?.running){native({type:'cancelConnectionTest',testId:connectionTest.id});connectionTest={running:false,error:'测试已取消'};renderTestStatus();break;}
      const form=$('#host-form');if(!form.reportValidity())break;const values=hostFormValues(form);if(!values)break;
      connectionTest={id:Date.now().toString(36)+'-'+Math.random().toString(36).slice(2),running:true};renderTestStatus();native({type:'testConnection',testId:connectionTest.id,host:values});break;
    }
    case 'hostInfo':hostInfo(id||hostId);break;
    case 'closeDialog':closeDialog();break;
    case 'connect':case 'disconnect':native({type:a,hostId:id||hostId});if($('#dialog').open)closeDialog();if(a==='connect')toast('正在建立 SSH 与 Codex 连接…');break;
    case 'syncHistory':native({type:a,hostId:id});closeDialog();toast('正在读取服务器聊天记录…');break;
    case 'loadHistory':native({type:a,chatId:id});closeDialog();toast('正在刷新远程记录…');break;
    case 'theme':state.theme=b.dataset.value;document.documentElement.dataset.theme=state.theme;native({type:a,value:state.theme});settings();break;
    case 'suggest':$('#prompt').value=b.dataset.prompt;resize();renderComposer();$('#prompt').focus();break;
    case 'copyCode':{const code=b.closest('.code-block')?.querySelector('pre code');if(code){native({type:'copy',text:code.textContent});toast('已复制代码');}break;}
    case 'copy':{const m=chat()?.messages.find(m=>m.id===id);if(m){native({type:'copy',text:m.text});toast('已复制回答');}break;}
    case 'chatMenu':chatMenu(id);break;
    case 'confirmDeleteHost':confirmDialog('删除虚拟机','这会删除手机上该连接和所属聊天记录；服务器上的文件和 Codex 会话仍会保留。','deleteHost',id);break;
    case 'confirmDeleteChat':confirmDialog('删除聊天记录','仅删除手机中的记录；服务器上的 Codex 会话仍会保留。','deleteChat',id);break;
    case 'deleteHost':native({type:a,hostId:id});closeDialog();break;
    case 'deleteChat':native({type:a,chatId:id});closeDialog();break;
    case 'disconnectAll':confirmDialog('断开连接','会关闭全部 SSH 连接，正在运行的任务可能中断。','doDisconnectAll','');break;
    case 'doDisconnectAll':native({type:'disconnectAll'});closeDialog();break;
    case 'acceptTrust':case 'rejectTrust':if(activeTrust){native({type:'trust',hostId:activeTrust.hostId,fingerprint:activeTrust.fingerprint,accept:a==='acceptTrust'});activeTrust=null;}closeDialog();break;
    case 'approve':case 'decline':native({type:'approve',hostId,token:b.dataset.token,decision:a==='approve'?'accept':'decline'});b.disabled=true;break;
    case 'answer':{
      const token=b.dataset.token,answers={};let missing=false;
      document.querySelectorAll('input[data-question]').forEach(i=>{if(i.dataset.token===token){if(!i.value.trim())missing=true;answers[i.dataset.question]={answers:[i.value.trim()]};}});
      if(missing){toast('请回答所有问题');break;}native({type:'approve',hostId,token,answers});b.disabled=true;break;
    }
  }
});
function hostFormValues(f){
    const v=Object.fromEntries(new FormData(f));
    v.host=v.host.trim();
    if(/^[0-9.]+$/.test(v.host)){const parts=v.host.split('.');if(parts.length!==4||parts.some(p=>!/^\d{1,3}$/.test(p)||Number(p)>255)){$('#form-error').textContent='IPv4 地址需要四段数字，每段 0–255，请检查是否漏写了点';return null;}}
    if(v.cwd&&!v.cwd.startsWith('/')){$('#form-error').textContent='工作目录须为 Linux 绝对路径';return null;}
    const old=state.hosts.find(h=>h.id===f.dataset.id);
    if((!old?.hasSecret||old.auth!==v.auth)&&!(v.auth==='key'?v.privateKey.trim():v.password)){$('#form-error').textContent='请填写所选认证方式的凭据';return null;}
    if(f.dataset.id)v.id=f.dataset.id;v.port=Number(v.port);$('#form-error').textContent='';return v;
}
document.addEventListener('input',e=>{if(e.target.closest('#host-form')&&connectionTest){if(connectionTest.running)native({type:'cancelConnectionTest',testId:connectionTest.id});connectionTest=null;renderTestStatus();}});
document.addEventListener('submit',e=>{
  e.preventDefault();const f=e.target,v=Object.fromEntries(new FormData(f));
  if(f.id==='host-form'){
    const values=hostFormValues(f);if(!values)return;
    if(connectionTest?.running){native({type:'cancelConnectionTest',testId:connectionTest.id});connectionTest=null;}
    $('#save-host').disabled=true;native({type:'saveHost',host:values});
  }else if(f.id==='chat-form'){if(v.cwd&&!v.cwd.startsWith('/')){$('#form-error').textContent='工作目录须为 Linux 绝对路径';return;}native({type:'newChat',...v});}
  else if(f.id==='rename-form'){native({type:'renameChat',chatId:f.dataset.id,title:v.title});closeDialog();}
});
document.addEventListener('change',e=>{
  if(e.target.id==='auth-select'){$('#password-field').hidden=e.target.value!=='password';$('#key-fields').hidden=e.target.value!=='key';}
  if(e.target.id==='new-host')$('#new-cwd').value=state.hosts.find(h=>h.id===e.target.value)?.cwd||'';
});
$('#send').addEventListener('click',send);
$('#prompt').addEventListener('input',()=>{resize();rememberDraft();renderComposer();});
$('#prompt').addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)&&!e.isComposing){e.preventDefault();send();}});
$('#search').addEventListener('input',e=>{search=e.target.value;renderSidebar();});
$('#scrim').addEventListener('click',closeSidebar);
$('#dialog').addEventListener('cancel',e=>{e.preventDefault();closeDialog();});
render();
