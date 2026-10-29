'use strict';
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=(name,cls='')=>`<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
let state={hosts:[],chats:[],theme:'dark',connections:{},trustPrompts:[]};
let hostId='',chatId='',search='',collapsed=new Set(),pendingSend=new Map(),drafts=new Map(),toastTimer,dialogKind='',lastMessages='';
let activeTrust=null;
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
  ].map(([i,t,d,p])=>`<button class="suggestion" data-action="suggest" data-prompt="${esc(p)}">${icon(i)}<span><strong>${t}</strong><small>${d}</small></span><span class="suggest-arrow">↗</span></button>`).join('')}</div><div class="welcome-bottom"><span>${icon('shield')}凭据本地加密</span><span>${icon('server')}多虚拟机独立会话</span><span>${icon('chat')}随时继续聊天</span></div>`;
}
function markdown(text){
  const codes=[];let s=esc(text).replace(/```([^\n]*)\n([\s\S]*?)```/g,(_,lang,body)=>{codes.push(`<pre><code>${body}</code></pre>`);return `\u0001${codes.length-1}\u0001`;});
  s=s.replace(/^### (.+)$/gm,'<h3>$1</h3>').replace(/^##? (.+)$/gm,'<h2>$1</h2>').replace(/\*\*([^*\n]+)\*\*/g,'<strong>$1</strong>').replace(/`([^`\n]+)`/g,'<code>$1</code>');
  s=s.split(/\n\n+/).map(p=>`<p>${p.replace(/\n/g,'<br>')}</p>`).join('');
  return s.replace(/\u0001(\d+)\u0001/g,(_,n)=>codes[Number(n)]||'');
}
function messageHTML(m){
  if(m.kind==='approval'){
    const p=m.params||{},qs=p.questions||[],isQuestion=m.approvalType==='item/tool/requestUserInput';
    return `<article class="message system"><div class="approval"><div class="approval-heading">${icon('shield')}${m.resolved?'请求已处理':isQuestion?'Codex 想确认一些信息':'等待你的审批'}</div>${isQuestion?qs.map((q,i)=>`<label for="q-${esc(m.id)}-${i}">${esc(q.header||'')} ${esc(q.question||'')}</label>${q.options?.length?`<div class="question-hint">${q.options.map(o=>esc(o.label)+(o.description?'：'+esc(o.description):'')).join(' / ')}</div>`:''}${!m.resolved?`<input id="q-${esc(m.id)}-${i}" data-question="${esc(q.id)}" data-token="${esc(m.id)}" placeholder="输入回答" autocomplete="off">`:''}`).join(''):`<pre>${esc(m.text)}${p.cwd?'\n\n工作目录：'+esc(p.cwd):''}</pre>`}${m.resolved?`<div class="approval-resolved">${esc(m.resolution||'连接或请求已结束')}</div>`:`<div class="approval-actions">${isQuestion?`<button class="btn primary" data-action="answer" data-token="${esc(m.id)}">提交回答</button>`:`<button class="btn primary" data-action="approve" data-token="${esc(m.id)}">允许本次</button><button class="btn" data-action="decline" data-token="${esc(m.id)}">拒绝</button>`}</div>`}</div></article>`;
  }
  if(m.kind==='error')return `<article class="message system"><div class="error-message">${esc(m.text)}</div></article>`;
  if(m.role==='tool')return `<article class="message tool"><details class="tool-box" data-item="${esc(m.id)}"><summary>${icon(m.kind==='files'?'folder':'terminal')}<span>${esc(m.kind==='files'?'文件改动':m.command||'命令输出')}</span><span class="tool-state">${esc(m.exitCode!=null?'exit '+m.exitCode:m.status==='completed'?'完成':m.status==='failed'?'失败':'执行中')}</span></summary><pre>${esc(m.text||'等待输出…')}</pre></details></article>`;
  if(m.role==='user')return `<article class="message user"><div class="message-content">${esc(m.text)}</div></article>`;
  return `<article class="message assistant"><div class="assistant-heading"><span class="mini-logo">${icon('code')}</span>Codex</div><div class="message-content">${markdown(m.text)}</div><div class="message-actions"><button class="icon" data-action="copy" data-id="${esc(m.id)}" aria-label="复制回答">${icon('copy')}</button></div></article>`;
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
  $('#send').innerHTML=icon(working?'stop':'arrow');$('#send').classList.toggle('stop',working);
  $('#send').setAttribute('aria-label',working?'停止任务':'发送消息');
  $('#send').disabled=!h||(!working&&(pendingSend.has(chatId)||!!con.busyChat||!$('#prompt').value.trim()));
  $('#prompt').disabled=!h;
  $('#prompt').placeholder=h?'告诉 Codex 你想做什么…':'先添加一台虚拟机，开始对话';
  const hint=con.status==='error'?'连接失败，请查看连接设置中的错误详情':con.status==='connecting'?(con.phase||'正在建立 SSH 连接'):con.busyChat&&con.busyChat!==chatId?'这台虚拟机有其他聊天正在运行':pendingSend.has(chatId)?'正在连接并发送…':c?.status==='interrupted'?'连接已中断，可刷新远程记录后继续':h?`${h.name}  /  ${c?.cwd||con.cwd||h.cwd||'自动识别'}`:'SSH 直连服务器，无需额外中转服务';
  $('#composer-context').innerHTML=`<span class="${c?.status==='interrupted'?'warning':''}">${esc(hint)}</span>`;
}
function resize(){const t=$('#prompt');t.style.height='auto';t.style.height=Math.min(180,t.scrollHeight)+'px';}
function openDialog(kind,html){dialogKind=kind;$('#dialog-content').innerHTML=html;if(!$('#dialog').open)$('#dialog').showModal();}
function head(title){return `<div class="dialog-head"><h2>${esc(title)}</h2><button class="icon" data-action="closeDialog" aria-label="关闭">${icon('close')}</button></div>`;}
function closeDialog(){if(activeTrust){native({type:'trust',hostId:activeTrust.hostId,fingerprint:activeTrust.fingerprint,accept:false});activeTrust=null;}$('#dialog').close();dialogKind='';}
function editor(id=''){
  const h=state.hosts.find(x=>x.id===id)||{name:'',host:'',port:22,user:'ubuntu',cwd:'',codex:'',auth:'key'};
  openDialog('host',`${head(id?'编辑虚拟机':'添加虚拟机')}<form id="host-form" data-id="${esc(id)}"><div class="dialog-body"><p class="dialog-description">每台 Linux 服务器都是独立工作区，拥有自己的聊天记录。</p><div class="field-grid"><label class="field full">工作区名称<input name="name" value="${esc(h.name)}" placeholder="例如：开发服务器" required maxlength="60"></label><label class="field">服务器地址<input name="host" value="${esc(h.host)}" placeholder="IP 或域名" required autocomplete="off" autocapitalize="off" spellcheck="false"></label><label class="field">SSH 端口<input name="port" type="number" value="${h.port}" required min="1" max="65535"></label><label class="field">登录用户名<input name="user" value="${esc(h.user)}" required autocomplete="off" autocapitalize="off" spellcheck="false"></label><label class="field">认证方式<select name="auth" id="auth-select"><option value="key" ${h.auth==='key'?'selected':''}>SSH 私钥</option><option value="password" ${h.auth==='password'?'selected':''}>密码</option></select></label><label class="field full" id="password-field" ${h.auth!=='password'?'hidden':''}>SSH 密码<input name="password" type="password" autocomplete="new-password" placeholder="${h.hasSecret?'已保存；留空保留原值':'输入 SSH 登录密码'}"></label><div class="full" id="key-fields" ${h.auth!=='key'?'hidden':''}><label class="field">SSH 私钥<textarea name="privateKey" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="${h.hasSecret?'已保存；留空保留原值':'粘贴完整的 OpenSSH / PEM 私钥'}"></textarea></label><div class="space-8"></div><label class="field">私钥口令（可选）<input name="passphrase" type="password" autocomplete="new-password" placeholder="${h.hasSecret?'留空保留原值':'私钥的解密口令'}"></label></div><details class="full advanced-paths"><summary>高级路径设置（通常无需填写）</summary><label class="field full">默认工作目录<input name="cwd" value="${esc(h.cwd||'')}" placeholder="自动使用服务器用户主目录" autocomplete="off" autocapitalize="off" spellcheck="false"><small>留空自动识别；填写项目路径时目录须已存在。</small></label><label class="field full">Codex 可执行文件<input name="codex" value="${esc(h.codex||'')}" placeholder="自动查找 Codex，无需填写" autocomplete="off" autocapitalize="off" spellcheck="false"><small>自动搜索 PATH、常见安装目录及 nvm。服务器需已安装并登录 Codex。</small></label></details></div><div id="form-error" class="dialog-error"></div></div><div class="dialog-actions"><button type="button" class="btn" data-action="closeDialog">取消</button><button class="btn primary" type="submit" id="save-host">保存连接</button></div></form>`);
}
function newChat(id=hostId){
  if(!state.hosts.length){editor();return;}
  const h=state.hosts.find(h=>h.id===id)||state.hosts[0];
  openDialog('newChat',`${head('新建聊天')}<form id="chat-form"><div class="dialog-body"><p class="dialog-description">选择虚拟机与工作目录，开始一段独立的 Codex 会话。</p><div class="field-grid"><label class="field full">虚拟机<select name="hostId" id="new-host">${state.hosts.map(x=>`<option value="${esc(x.id)}" ${x.id===h.id?'selected':''}>${esc(x.name)}</option>`).join('')}</select></label><label class="field full">工作目录<input name="cwd" id="new-cwd" value="${esc(h.cwd||'')}" placeholder="自动使用服务器用户主目录" autocapitalize="off" spellcheck="false"></label></div><div id="form-error" class="dialog-error"></div></div><div class="dialog-actions"><button type="button" class="btn" data-action="closeDialog">取消</button><button class="btn primary">创建聊天</button></div></form>`);
}
function settings(){
  openDialog('settings',`${head('设置')}<div class="dialog-body"><div class="settings-item"><div><strong>外观</strong><small>选择适合你的界面主题</small></div><div class="theme-choices"><button data-action="theme" data-value="dark" class="${state.theme==='dark'?'chosen':''}">深色</button><button data-action="theme" data-value="light" class="${state.theme==='light'?'chosen':''}">浅色</button></div></div><div class="settings-item"><div><strong>连接管理</strong><small>通过 SSH 直连 Linux 云服务器</small></div><button class="text-button" data-action="addHost">＋ 添加虚拟机</button></div>${state.hosts.map(h=>`<div class="host-card">${icon('server')}<div><strong>${esc(h.name)}</strong><small>${esc(h.user+'@'+h.host+':'+h.port)}</small></div><button class="icon" data-action="editHost" data-id="${esc(h.id)}" aria-label="编辑连接">${icon('edit')}</button></div>`).join('')}<div class="settings-title">数据与安全</div><div class="settings-item"><div><strong>本地加密存储</strong><small>密码、私钥和聊天记录通过 Android 密钥库加密</small></div>${icon('shield')}</div><div class="settings-item"><div><strong>断开全部连接</strong><small>运行中的任务可能中断，服务器文件改动会保留</small></div><button class="btn" data-action="disconnectAll">断开</button></div><p class="about-note">Codex Pocket 0.1.1 · 独立客户端，非 OpenAI 官方应用。<br>聊天任务使用服务器上 Codex 的账号和配置。Android 系统可能回收后台连接，首版不保证断网后任务继续运行。重新连接后可恢复会话上下文。</p></div>`);
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
  if(activeTrust?.hostId===p.hostId)return;activeTrust=p;
  openDialog('trust',`${head('核对服务器身份')}<div class="dialog-body"><p class="dialog-description">首次连接 ${esc(p.name)}。请在云服务器控制台核对 SSH 主机指纹，确认是你的服务器后再信任。</p><div class="fingerprint">${esc(p.fingerprint)}</div><p class="about-note">地址：${esc(p.address)}<br>信任后将固定此密钥；密钥变化会阻止连接。</p></div><div class="dialog-actions"><button class="btn" data-action="rejectTrust">取消连接</button><button class="btn primary" data-action="acceptTrust">核对无误，信任</button></div>`);
}
function send(){
  if(chat()?.status==='working'){native({type:'stop',chatId});return;}
  const text=$('#prompt').value.trim();if(!text||!host())return;
  if(!chatId){drafts.set('__new',text);native({type:'newChat',hostId,cwd:host().cwd});return;}
  if(pendingSend.has(chatId)||connection(hostId).busyChat)return;
  pendingSend.set(chatId,text);drafts.set(chatId,'');$('#prompt').value='';resize();renderComposer();native({type:'send',chatId,text});
}
window.receive=function(e){
  if(e.type==='state'){
    if(e.state.error){toast(e.state.error);return;}state=e.state;render();
    if(activeTrust&&!state.trustPrompts?.some(p=>p.hostId===activeTrust.hostId)){activeTrust=null;if(dialogKind==='trust')closeDialog();}
    const p=state.trustPrompts?.[0];if(p&&!activeTrust)trustPrompt(p);
  }else if(e.type==='hostSaved'){hostId=e.hostId;chatId='';closeDialog();toast('虚拟机已保存');}
  else if(e.type==='chatCreated'){
    if(e.state)state=e.state;chatId=e.chatId;hostId=e.hostId;lastMessages='';closeDialog();closeSidebar();
    const draft=drafts.get('__new');if(draft){drafts.delete('__new');pendingSend.set(chatId,draft);$('#prompt').value='';native({type:'send',chatId,text:draft});}else $('#prompt').value='';resize();render();
  }else if(e.type==='actionComplete'){
    if(e.action==='send'){pendingSend.delete(e.chatId);renderComposer();}
  }else if(e.type==='actionError'){
    if(e.action==='send'){const draft=pendingSend.get(e.chatId);pendingSend.delete(e.chatId);if(draft){drafts.set(e.chatId,draft);if(e.chatId===chatId){$('#prompt').value=draft;resize();}}}
    if(['saveHost','newChat'].includes(e.action)&&$('#form-error')){$('#form-error').textContent=e.message;const b=$('#save-host');if(b)b.disabled=false;}else toast(e.message);
    renderComposer();
  }else if(e.type==='trust')trustPrompt(e);
  else if(e.type==='toast')toast(e.message);
};
window.back=function(){if($('#dialog').open){closeDialog();return;}if(document.body.classList.contains('sidebar-open')){closeSidebar();return;}document.body.classList.add('sidebar-open');};
document.addEventListener('click',e=>{
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
    case 'hostInfo':hostInfo(id||hostId);break;
    case 'closeDialog':closeDialog();break;
    case 'connect':case 'disconnect':native({type:a,hostId:id||hostId});if($('#dialog').open)closeDialog();if(a==='connect')toast('正在建立 SSH 与 Codex 连接…');break;
    case 'syncHistory':native({type:a,hostId:id});closeDialog();toast('正在读取服务器聊天记录…');break;
    case 'loadHistory':native({type:a,chatId:id});closeDialog();toast('正在刷新远程记录…');break;
    case 'theme':state.theme=b.dataset.value;document.documentElement.dataset.theme=state.theme;native({type:a,value:state.theme});settings();break;
    case 'suggest':$('#prompt').value=b.dataset.prompt;resize();renderComposer();$('#prompt').focus();break;
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
document.addEventListener('submit',e=>{
  e.preventDefault();const f=e.target,v=Object.fromEntries(new FormData(f));
  if(f.id==='host-form'){
    if(v.cwd&&!v.cwd.startsWith('/')){$('#form-error').textContent='工作目录须为 Linux 绝对路径';return;}
    const old=state.hosts.find(h=>h.id===f.dataset.id);
    if((!old?.hasSecret||old.auth!==v.auth)&&!(v.auth==='key'?v.privateKey.trim():v.password)){$('#form-error').textContent='请填写所选认证方式的凭据';return;}
    if(f.dataset.id)v.id=f.dataset.id;v.port=Number(v.port);$('#save-host').disabled=true;native({type:'saveHost',host:v});
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
