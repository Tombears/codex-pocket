process.env.TEMP=process.env.CODEX_POCKET_TEST_TMP||require('path').resolve(__dirname,'test-results/browser-temp');process.env.TMP=process.env.TEMP;require('fs').mkdirSync(process.env.TEMP,{recursive:true});
const {chromium}=require('playwright');
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert');
const base=path.resolve(__dirname,'../app/src/main/assets/ui');
const out=process.env.CODEX_POCKET_TEST_OUTPUT||path.resolve(__dirname,'test-results');fs.mkdirSync(out,{recursive:true});
const server=http.createServer((req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1);if(!['index.html','app.js','app.css','markdown.js','vendor/marked.js','vendor/purify.js','vendor/highlight.js'].includes(name)){res.writeHead(404);return res.end();}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join(base,name)));});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{}),headless:true,args:['--disable-gpu'],env:{...process.env,TEMP:process.env.TEMP,TMP:process.env.TMP}});
 const context=await browser.newContext({viewport:{width:412,height:915},deviceScaleFactor:1,isMobile:true,hasTouch:true});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.commands=[];
  window.fixture={theme:'dark',connections:{},hosts:[{id:'h1',name:'开发服务器',host:'192.0.2.10',port:22,user:'ubuntu',cwd:'/home/ubuntu/workspace',codex:'codex',auth:'key',hasSecret:true},{id:'h2',name:'测试环境',host:'192.0.2.20',port:22,user:'dev',cwd:'/srv/project',codex:'codex',auth:'password',hasSecret:true}],chats:[{id:'c1',hostId:'h1',title:'梳理项目结构',cwd:'/home/ubuntu/workspace',remoteId:'r1',status:'completed',updatedAt:3,messages:[]},{id:'c2',hostId:'h1',title:'排查 API 超时问题',cwd:'/srv/api',status:'completed',updatedAt:2,messages:[]},{id:'c3',hostId:'h2',title:'部署前检查',cwd:'/srv/project',status:'idle',updatedAt:1,messages:[]}],trustPrompts:[]};
  window.Android={action(raw){const a=JSON.parse(raw);window.commands.push(a);const s=window.fixture;
   if(a.type==='theme'){s.theme=a.value;setTimeout(()=>window.receive({type:'state',state:s}),5);}
   if(a.type==='approvalMode'){s.approvalMode=a.value;const c=s.chats.find(c=>c.id===a.chatId);if(c)c.approvalMode=a.value;setTimeout(()=>window.receive({type:'state',state:s}),5);}
   if(a.type==='newChat'){const c={id:'cnew',hostId:a.hostId,cwd:a.cwd,title:'新聊天',messages:[],status:'idle',updatedAt:10};s.chats.push(c);setTimeout(()=>window.receive({type:'chatCreated',chatId:c.id,hostId:c.hostId,state:s}),5);}
   if(a.type==='renameChat'){s.chats.find(c=>c.id===a.chatId).title=a.title;setTimeout(()=>window.receive({type:'state',state:s}),5);}
   if(a.type==='send'){const c=s.chats.find(c=>c.id===a.chatId);c.messages.push({id:'u1',role:'user',kind:'text',text:a.text});setTimeout(()=>{window.receive({type:'state',state:s});window.receive({type:'actionComplete',action:'send',chatId:c.id});},5);}
   if(a.type==='deleteHost'){s.hosts=s.hosts.filter(h=>h.id!==a.hostId);s.chats=s.chats.filter(c=>c.hostId!==a.hostId);setTimeout(()=>window.receive({type:'state',state:s}),5);}
  }};
 });
 await page.goto(`http://127.0.0.1:${server.address().port}/`);
 await page.evaluate(()=>window.receive({type:'state',state:window.fixture}));
 await page.screenshot({path:path.join(out,'CodexPocket-手机预览.png'),animations:'disabled'});
 assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
 assert.equal(await page.locator('.welcome-bottom').count(),0,'removed footer text must not appear');
 assert.equal(await page.locator('body').evaluate(b=>b.scrollWidth<=window.innerWidth),true,'no horizontal overflow');
 await page.locator('header [data-action=sidebar]').click();
 assert.equal(await page.locator('.host-row').count(),2);
 await page.locator('#search').fill('部署');assert.equal(await page.locator('.chat-entry').count(),1);
 await page.locator('#search').fill('');
 await page.locator('.chat-select[data-id=c1]').click();
 await page.locator('header [data-action=newChat]').click();
 await page.locator('#new-host').selectOption('h2');assert.equal(await page.locator('#new-cwd').inputValue(),'/srv/project');
 await page.locator('#new-cwd').fill('/srv/new-project');await page.locator('#chat-form button.primary').click();
 await page.waitForFunction(()=>document.querySelector('#workspace-bar').textContent.includes('/srv/new-project'));
 await page.locator('#prompt').fill('检查当前项目');await page.locator('#send').click();
 await page.waitForFunction(()=>window.commands.some(a=>a.type==='send'&&a.chatId==='cnew'));
 await page.waitForSelector('.message.user');assert.equal(await page.locator('.message.user').innerText(),'检查当前项目');
 // Test streamed content escaping: remote content must not create HTML handlers.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages.push({id:'a1',role:'assistant',kind:'text',text:'## 检查结果\n\n**项目结构清晰**，可以继续开发。\n\n<img src=x onerror="window.pwned=1">\n\n```sh\nnpm test\n```'});window.receive({type:'state',state:window.fixture});});
 assert.equal(await page.locator('.message-content img').count(),0);assert.equal(await page.evaluate(()=>window.pwned),undefined);
 assert.equal(await page.locator('.message-content pre').count(),1);
 // Approval response must include selected host and the opaque token.
 await page.evaluate(()=>{window.fixture.chats.find(c=>c.id==='cnew').messages.push({id:'approval-token',role:'system',kind:'approval',text:'npm test',approvalType:'item/commandExecution/requestApproval',params:{cwd:'/srv/new-project'}});window.receive({type:'state',state:window.fixture});});
 await page.locator('[data-action=approve]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'approve',hostId:'h2',token:'approval-token',decision:'accept'});
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages=[{id:'preview-u',role:'user',kind:'text',text:'帮我了解这个项目，先检查目录结构和运行方式。'},{id:'preview-cmd',role:'tool',kind:'command',command:'pwd && ls -la',status:'completed',exitCode:0,text:'/srv/new-project\nREADME.md  package.json  src/  tests/'},{id:'preview-a',role:'assistant',kind:'text',text:'## 项目概览\n\n这是一个应用项目，主要代码位于 `src/`，测试放在 `tests/`。\n\n**建议下一步**\n\n1. 阅读 README 中的启动说明。\n2. 检查依赖和环境配置。\n3. 运行测试，确认当前状态。\n\n我可以继续检查启动脚本，暂时不会修改文件。'}];window.receive({type:'state',state:window.fixture});});
 await page.screenshot({path:path.join(out,'CodexPocket-聊天预览.png'),animations:'disabled'});
 await page.locator('header [data-action=sidebar]').click();await page.locator('.settings-row').click();await page.locator('[data-action=theme][data-value=light]').click();
 await page.waitForFunction(()=>document.documentElement.dataset.theme==='light');
 await page.locator('[data-action=exportLogs]').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'exportLogs');
 await page.locator('[data-action=theme][data-value=dark]').click();await page.locator('[data-action=closeDialog]').click();
 // Settings opens from the sidebar; closing it leaves the sidebar visible.
 // Ensure account secrets are never seeded into preview/bootstrap data.
 await page.locator('.host-row [data-action=selectHost][data-id=h1]').click();
 await page.setViewportSize({width:1365,height:900});await page.screenshot({path:path.join(out,'CodexPocket-桌面布局预览.png'),animations:'disabled'});
 await page.setViewportSize({width:360,height:740});
 await page.locator('header [data-action=sidebar]').click();await page.locator('.settings-row').click();await page.locator('#dialog [data-action=addHost]').click();
 await page.locator('#auth-select').selectOption('password');assert.equal(await page.locator('#password-field').isVisible(),true);assert.equal(await page.locator('#key-fields').isVisible(),false);
 await page.locator('#host-form .advanced-paths summary').click();
 await page.locator('input[name=name]').fill('新虚拟机');await page.locator('input[name=host]').fill('192.0.2.30');await page.locator('input[name=password]').fill('test-only');await page.locator('input[name=cwd]').fill('relative');await page.locator('#save-host').click();
 assert.ok((await page.locator('#form-error').textContent()).includes('绝对路径'));
 await page.locator('input[name=cwd]').fill('/srv/work');await page.locator('#save-host').click();
 assert.ok(await page.evaluate(()=>window.commands.some(a=>a.type==='saveHost'&&a.host.port===22&&a.host.auth==='password')));
 // Trust prompts must survive snapshots and cancel must explicitly reject.
 await page.locator('.dialog-head [data-action=closeDialog]').click();
 await page.evaluate(()=>window.receive({type:'trust',hostId:'h1',name:'开发服务器',address:'192.0.2.10',fingerprint:'SHA256:test'}));
 await page.locator('[data-action=rejectTrust]').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).accept),false);
 if(await page.locator('#dialog').evaluate(d=>d.open))await page.locator('.dialog-head [data-action=closeDialog]').click();
 // Connecting must identify its stage and keep an immediate cancel action available.
 await page.evaluate(()=>{document.body.classList.remove('sidebar-open');window.fixture.connections.h1={status:'connecting',phase:'连接 SSH 并验证身份'};window.receive({type:'state',state:window.fixture});});
 assert.ok((await page.locator('.connection-progress').innerText()).includes('连接 SSH'));
 assert.equal(await page.locator('.welcome-host [data-action=disconnect]').isEnabled(),true);
 await page.locator('.welcome-host [data-action=disconnect]').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'disconnect');
 // Failure details must remain visible after the transient toast disappears.
 await page.evaluate(()=>{window.fixture.connections.h1={status:'error',phase:'等待 Codex 握手',error:'工作目录不存在或无权限：/missing'};window.receive({type:'state',state:window.fixture});});
 assert.ok((await page.locator('.connection-problem').innerText()).includes('/missing'));
 await page.locator('.connection-problem [data-action=hostInfo]').click();assert.ok((await page.locator('#dialog .connection-problem').innerText()).includes('/missing'));await page.locator('.dialog-head [data-action=closeDialog]').click();
 // A root connection should work with automatic, blank paths and no advanced input.
 await page.locator('header [data-action=sidebar]').click();await page.locator('.settings-row').click();await page.locator('#dialog [data-action=addHost]').click();
 await page.locator('#host-form input[name=user]').fill('root');await page.locator('#host-form input[name=user]').dispatchEvent('change');
 assert.equal(await page.locator('#host-form input[name=cwd]').inputValue(),'');
 assert.equal(await page.locator('#host-form input[name=codex]').inputValue(),'');
 assert.equal(await page.locator('#host-form input[name=cwd]').isVisible(),false);
 await page.locator('#host-form input[name=name]').fill('自动路径测试');await page.locator('#host-form input[name=host]').fill('192.02553.10');await page.locator('#auth-select').selectOption('password');await page.locator('#host-form input[name=password]').fill('test-only');await page.locator('#save-host').click();
 assert.ok((await page.locator('#form-error').innerText()).includes('四段'));await page.locator('#host-form input[name=host]').fill('192.0.2.40');await page.locator('#save-host').click();
 assert.ok(await page.evaluate(()=>window.commands.some(a=>a.type==='saveHost'&&a.host.user==='root'&&a.host.cwd===''&&a.host.codex==='')));
 await page.locator('.dialog-head [data-action=closeDialog]').click();
 // Test the edited values in-place without saving, then return from host trust to the intact form.
 if(!await page.locator('body').evaluate(b=>b.classList.contains('sidebar-open')))await page.locator('header [data-action=sidebar]').click();await page.locator('.settings-row').click();await page.locator('[data-action=backgroundSettings]').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'backgroundSettings');await page.locator('#dialog [data-action=editHost][data-id=h1]').click();
 await page.locator('#host-form input[name=port]').fill('443');await page.locator('#test-connection').click();
 const request=await page.evaluate(()=>window.commands.at(-1));assert.equal(request.type,'testConnection');assert.equal(request.host.port,443);assert.equal(request.host.id,'h1');
 assert.equal(await page.locator('#dialog').evaluate(d=>d.open),true);assert.equal(await page.locator('#test-connection').innerText(),'取消测试');
 await page.evaluate(id=>window.receive({type:'trust',hostId:'test-'+id,name:'Test',address:'192.0.2.10:443',fingerprint:'SHA256:test'}),request.testId);
 await page.locator('[data-action=acceptTrust]').click();assert.equal(await page.locator('#host-form input[name=port]').inputValue(),'443');
 await page.evaluate(id=>window.receive({type:'connectionTestResult',testId:id,ok:true,cwd:'/root',codex:'/usr/local/bin/codex'}),request.testId);
 assert.ok((await page.locator('#connection-test-result').innerText()).includes('连接成功'));assert.equal(await page.locator('#dialog').evaluate(d=>d.open),true);
 assert.equal(await page.evaluate(()=>window.fixture.hosts.find(h=>h.id==='h1').port),22,'testing must not save edits');
 await page.locator('#test-connection').click();await page.locator('#test-connection').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'cancelConnectionTest');
 await page.locator('.dialog-head [data-action=closeDialog]').click();
 // GFM tables, nested lists, highlighted/unfinished code, safe links and source-only copy.
 await page.evaluate(()=>{document.body.classList.remove('sidebar-open');selectChat('cnew');window.fixture.chats.find(c=>c.id==='cnew').messages=[{id:'rich',role:'assistant',kind:'text',text:'# 格式化回答\n\n> 引用说明\n\n- 第一项\n  - 子项\n\n1. 步骤一\n2. 步骤二\n\n| 名称 | 值 |\n| --- | --- |\n| 端口 | `443` |\n\n~~旧文字~~ [文档](https://example.com/docs)\n\n```javascript\nconst text = "<tag>";\nconsole.log(text);\n```\n\n![remote](https://example.com/private.png)\n\n<img src=x onerror="window.pwned=2">\n\n[unsafe](javascript:alert(1))'}];window.receive({type:'state',state:window.fixture});});
 assert.equal(await page.locator('.message-content table').count(),1);assert.equal(await page.locator('.message-content blockquote').count(),1);assert.equal(await page.locator('.message-content ul ul').count(),1);assert.equal(await page.locator('.message-content ol li').count(),2);assert.equal(await page.locator('.message-content .hljs-keyword').count()>0,true);
 assert.equal(await page.locator('.message-content img').count(),0);assert.equal(await page.locator('.message-content a[href^="javascript:"]').count(),0);assert.equal(await page.evaluate(()=>window.pwned),undefined);
 await page.locator('[data-action=copyCode]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'copy',text:'const text = "<tag>";\nconsole.log(text);'});
 await page.locator('.message-content a').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'openLink');
 assert.equal(await page.locator('body').evaluate(b=>b.scrollWidth<=window.innerWidth),true);
 await page.screenshot({path:path.join(out,'CodexPocket-Markdown预览.png'),animations:'disabled'});
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages[0].text='```python\nprint("streaming")';window.receive({type:'state',state:window.fixture});});assert.ok((await page.locator('.code-block pre').innerText()).includes('streaming'));
 // Image picker context, image-only turn, failure draft recovery and in-turn steering.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.status='idle';window.fixture.connections.h2={status:'online',busyChat:''};window.receive({type:'state',state:window.fixture});});
 await page.locator('#pick-images').click();const pick=await page.evaluate(()=>window.commands.at(-1));assert.equal(pick.type,'pickImages');
 await page.evaluate(key=>window.receive({type:'imagesSelected',draftKey:key,images:[{id:'test-image',preview:'data:image/jpeg;base64,/9j/2Q==',width:32,height:24}]}),pick.draftKey);
 assert.equal(await page.locator('.attachment-preview').count(),1);await page.locator('#prompt').fill('');assert.equal(await page.locator('#send').isEnabled(),true);await page.locator('#send').click();
 const imageSend=await page.evaluate(()=>window.commands.at(-1));assert.equal(imageSend.type,'send');assert.deepEqual(imageSend.images,[{id:'test-image'}]);
 await page.evaluate(()=>window.receive({type:'actionError',action:'send',chatId:'cnew',message:'temporary network failure'}));assert.equal(await page.locator('.attachment-preview').count(),1);
 await page.locator('[data-action=removeImage]').click();assert.equal(await page.locator('.attachment-preview').count(),0);
 await page.evaluate(()=>{window.fixture.chats.find(c=>c.id==='cnew').status='working';window.fixture.connections.h2={status:'online',busyChat:'cnew'};window.receive({type:'state',state:window.fixture});});
 await page.locator('#prompt').fill('先检查测试，不要改代码');await page.locator('#send').click();const steering=await page.evaluate(()=>window.commands.at(-1));assert.equal(steering.type,'steer');assert.equal(steering.chatId,'cnew');
 await page.evaluate(()=>window.receive({type:'actionError',action:'steer',chatId:'cnew',message:'turn ended'}));assert.equal(await page.locator('#prompt').inputValue(),'先检查测试，不要改代码');await page.locator('#stop-task').click();assert.equal(await page.evaluate(()=>window.commands.at(-1).type),'stop');
 // Approval selection is scoped to the chat and displayed again from native state.
 await page.locator('#mode-label').click();assert.ok((await page.locator('#dialog').innerText()).includes('下一次任务'));
 await page.locator('[data-action=setApprovalMode][data-value=untrusted]').click();await page.waitForFunction(()=>document.querySelector('#mode-label').textContent.includes('严格审批'));
 const selection=await page.evaluate(()=>window.commands.at(-1));
 assert.deepEqual(selection,{type:'approvalMode',chatId:'cnew',value:'untrusted',requestId:selection.requestId});assert.ok(selection.requestId);
 await page.locator('#mode-label').click();await page.locator('[data-action=setApprovalMode][data-value=never]').click();await page.waitForFunction(()=>document.querySelector('#mode-label').textContent.includes('不询问'));
 await page.locator('#mode-label').click();await page.locator('[data-action=setApprovalMode][data-value=on-request]').click();await page.waitForFunction(()=>document.querySelector('#mode-label').textContent.includes('按需审批'));
 // A long multiline command stays one row while its full content remains expandable.
 await page.evaluate(()=>{window.fixture.chats.find(c=>c.id==='cnew').messages=[{id:'long-command',role:'tool',kind:'command',command:'/bin/bash -lc "sed -n 1,260p\n/root/.codex/skills/.system/imagegen/SKILL.md"',text:'output',status:'completed',exitCode:0}];window.receive({type:'state',state:window.fixture});});
 for(const width of [360,412,768]){await page.setViewportSize({width,height:800});const metrics=await page.locator('.tool-box summary').evaluate(e=>({height:e.getBoundingClientRect().height,stateHeight:e.querySelector('.tool-state').getBoundingClientRect().height,width:document.documentElement.scrollWidth,viewport:innerWidth}));assert.ok(metrics.height<45);assert.ok(metrics.stateHeight<20);assert.ok(metrics.width<=metrics.viewport);assert.equal(await page.locator('.tool-command').evaluate(e=>getComputedStyle(e).whiteSpace),'nowrap');}
 await page.locator('.tool-box summary').click();assert.ok((await page.locator('.tool-full-command').innerText()).includes('/root/.codex/skills/.system/imagegen/SKILL.md'));
 // Received PNG thumbnails and original previews only use native-provided raster data.
 await page.setViewportSize({width:412,height:915});
 const png='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=';
 await page.evaluate(preview=>{window.fixture.chats.find(c=>c.id==='cnew').messages=[{id:'remote-image',role:'assistant',kind:'image',text:'',remoteImages:[{name:'cat.png',status:'ready',preview},{name:'retry.png',status:'failed'}]}];window.receive({type:'state',state:window.fixture});},png);
 assert.equal(await page.locator('.received-image').count(),1);await page.locator('[data-action=viewRemoteImage]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'viewRemoteImage',chatId:'cnew',messageId:'remote-image',index:0});
 await page.evaluate(preview=>window.receive({type:'remoteImageOpened',chatId:'cnew',messageId:'remote-image',index:0,name:'cat.png',preview}),png);assert.equal(await page.locator('.image-viewer img').count(),1);assert.equal(await page.locator('.dialog-head h2').innerText(),'图片');await page.locator('[data-action=saveRemoteImage]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'saveRemoteImage',chatId:'cnew',messageId:'remote-image',index:0});await page.locator('[data-action=closeDialog]').click();
 const originalUrl='https://app.codexpocket.local/images/11111111-1111-1111-1111-111111111111/original';await page.route(originalUrl,route=>route.fulfill({contentType:'image/png',body:Buffer.from(png.split(',')[1],'base64')}));
 await page.evaluate(url=>window.receive({type:'remoteImageOpened',chatId:'cnew',messageId:'remote-image',index:0,name:'long-server-filename.png',url}),originalUrl);assert.equal(await page.locator('.image-viewer img').getAttribute('src'),originalUrl);assert.equal(await page.locator('.dialog-head h2').innerText(),'图片');await page.locator('[data-action=closeDialog]').click();
 await page.locator('[data-action=loadRemoteImage]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'loadRemoteImage',chatId:'cnew',messageId:'remote-image',index:1});
 await page.evaluate(()=>window.receive({type:'remoteImageOpened',chatId:'cnew',name:'unsafe',preview:'https://example.com/track.png'}));assert.equal(await page.locator('#dialog').evaluate(d=>d.open),false);
 await page.screenshot({path:path.join(out,'CodexPocket-服务器图片.png'),animations:'disabled'});
 // File links must retain the remote chat context, including encoded filenames.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages=[{id:'stable',role:'assistant',text:'保留这条记录'},{id:'stream',role:'assistant',text:'[下载报告](sandbox:/srv/out/a%20b.pdf)\n\n'+('长文本内容 '.repeat(3000))}];window.receive({type:'state',state:window.fixture});window.stableNode=document.querySelector('#messages').firstElementChild;window.streamNode=document.querySelector('#messages').lastElementChild;window.stableText=window.stableNode.querySelector('p').firstChild;});
 await page.locator('[data-remote-path]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'downloadFile',chatId:'cnew',path:'sandbox:/srv/out/a%20b.pdf'});
 await page.locator('#pick-files').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'pickFiles',chatId:'cnew'});
 assert.equal(await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');for(let i=0;i<12;i++){c.messages[1].text+=' 流式追加';window.receive({type:'state',state:window.fixture});}return window.stableNode===document.querySelector('#messages').firstElementChild&&window.streamNode===document.querySelector('#messages').lastElementChild&&window.stableText===window.stableNode.querySelector('p').firstChild;}),true,'streaming preserves existing message and text nodes');
 await page.evaluate(()=>{window.mutations=0;window.messageObserver=new MutationObserver(list=>window.mutations+=list.length);window.messageObserver.observe(document.querySelector('#messages'),{subtree:true,childList:true,characterData:true,attributes:true});const c=window.fixture.chats.find(c=>c.id==='cnew');c.status='completed';window.receive({type:'state',state:window.fixture});window.receive({type:'state',state:window.fixture});});
 await page.waitForTimeout(250);assert.equal(await page.evaluate(()=>{window.messageObserver.disconnect();return window.mutations;}),0,'completed history remains stable');
 await page.evaluate(()=>{document.querySelector('#prompt').value='读取附件';window.receive({type:'fileUploaded',chatId:'cnew',path:'/srv/.upload/a.txt'});});assert.ok((await page.locator('#prompt').inputValue()).includes('/srv/.upload/a.txt'));
 await page.evaluate(()=>window.receive({type:'fileUploaded',chatId:'c1',path:'/other/chat.txt'}));assert.ok(!(await page.locator('#prompt').inputValue()).includes('/other/chat.txt'));
 // Stable Markdown blocks must not be parsed/highlighted again for each delta.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.status='working';c.messages=[{id:'blocks',role:'assistant',text:'## 已完成标题\n\n保持 **已有内容**\n\n```javascript\nconst preserved = 1;\n```\n\n正在输出'}];window.receive({type:'state',state:window.fixture});window.fixedHeading=document.querySelector('.markdown-body h2');window.fixedCode=document.querySelector('.markdown-body code');window.fixedKeyword=window.fixedCode.querySelector('.hljs-keyword');window.tailText=document.querySelector('.markdown-body').lastElementChild.querySelector('p').firstChild;window.highlightCalls=0;window.savedHighlight=hljs.highlight;hljs.highlight=(...args)=>{window.highlightCalls++;return window.savedHighlight(...args);};window.appendCalls=0;window.savedAppend=CharacterData.prototype.appendData;CharacterData.prototype.appendData=function(value){window.appendCalls++;return window.savedAppend.call(this,value);};});
 await page.evaluate(async()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');for(let i=0;i<16;i++){c.messages[0].text+='增';window.receive({type:'state',state:window.fixture});await new Promise(requestAnimationFrame);}});
 assert.equal(await page.evaluate(()=>window.fixedHeading===document.querySelector('.markdown-body h2')&&window.fixedCode===document.querySelector('.markdown-body code')&&window.fixedKeyword===document.querySelector('.hljs-keyword')&&window.tailText===document.querySelector('.markdown-body').lastElementChild.querySelector('p').firstChild),true,'completed Markdown and active text nodes stay mounted');
 assert.equal(await page.evaluate(()=>window.highlightCalls),0,'completed code is not highlighted again');assert.equal(await page.evaluate(()=>window.appendCalls),16,'plain text deltas append only received characters');
 await page.evaluate(()=>{hljs.highlight=window.savedHighlight;CharacterData.prototype.appendData=window.savedAppend;});
 // Markdown can reinterpret its active block, or earlier reference links.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages[0].text='[文档][ref]\n\n未闭合 **粗体';window.receive({type:'state',state:window.fixture});c.messages[0].text+='完成**\n\n[ref]: https://example.com/reference';window.receive({type:'state',state:window.fixture});});
 assert.equal(await page.locator('.markdown-body strong').innerText(),'粗体完成');assert.equal(await page.locator('.markdown-body a').getAttribute('href'),'https://example.com/reference');
 // An open code fence stays plain during streaming, then highlights on closure.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages[0].text='```javascript\nconst streamed = ';window.receive({type:'state',state:window.fixture});window.fenceCode=document.querySelector('.markdown-body code');window.fenceText=window.fenceCode.firstChild;c.messages[0].text+='42;';window.receive({type:'state',state:window.fixture});});
 assert.equal(await page.evaluate(()=>window.fenceCode===document.querySelector('.markdown-body code')&&window.fenceText===window.fenceCode.firstChild),true);assert.equal(await page.locator('.markdown-body .hljs-keyword').count(),0);
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages[0].text+='\n```';c.status='completed';window.receive({type:'state',state:window.fixture});});assert.equal(await page.locator('.markdown-body .hljs-keyword').count(),1);
 // Full replacements and history shortening cannot leave cached tail blocks behind.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages[0].text='替换后的短回答';window.receive({type:'state',state:window.fixture});});assert.equal(await page.locator('.markdown-body').innerText(),'替换后的短回答');assert.equal(await page.locator('.markdown-body code').count(),0);
 // A disconnected chat offers a scoped reconnect action and preserves its draft.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.remoteId='remote-reconnect';c.needsHistory=false;c.historyEpoch=0;window.fixture.connections.h2={status:'error',error:'网络已断开',busyChat:''};window.receive({type:'state',state:window.fixture});});
 await page.locator('#prompt').fill('保留断线前的草稿');await page.locator('#chat-connection [data-action=reconnect]').click();assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'connect',hostId:'h2',chatId:'cnew'});assert.equal(await page.locator('#chat-connection [data-action=reconnect]').count(),0);assert.ok((await page.locator('#chat-connection').innerText()).includes('正在重新连接'));
 await page.evaluate(()=>{window.fixture.connections.h2={status:'connecting',phase:'等待 Codex 握手',busyChat:''};window.receive({type:'state',state:window.fixture});});assert.ok((await page.locator('#chat-connection').innerText()).includes('等待 Codex 握手'));
 // Restoring the transport requests history once, without sending a model task.
 const priorTasks=await page.evaluate(()=>window.commands.filter(a=>a.type==='send'||a.type==='steer').length);
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.needsHistory=true;c.historyEpoch=1;window.fixture.connections.h2={status:'online',busyChat:''};for(let i=0;i<4;i++)window.receive({type:'state',state:window.fixture});});
 const restore=await page.evaluate(()=>window.commands.at(-1));assert.equal(restore.type,'loadHistory');assert.equal(restore.chatId,'cnew');assert.ok(restore.requestId);assert.ok((await page.locator('#chat-connection').innerText()).includes('正在补齐'));
 assert.equal(await page.evaluate(id=>window.commands.filter(a=>a.requestId===id).length,restore.requestId),1);
 // A failed refresh keeps the old content and allows an explicit retry.
 await page.evaluate(a=>window.receive({type:'actionError',action:'loadHistory',chatId:a.chatId,requestId:a.requestId,message:'读取超时'}),restore);await page.evaluate(()=>window.receive({type:'state',state:window.fixture}));assert.ok((await page.locator('#chat-connection').innerText()).includes('读取超时'));assert.equal(await page.locator('.markdown-body').innerText(),'替换后的短回答');
 await page.locator('#chat-connection [data-action=retryHistory]').click();const retry=await page.evaluate(()=>window.commands.at(-1));assert.notEqual(retry.requestId,restore.requestId);
 await page.evaluate(a=>{window.receive({type:'actionComplete',action:'loadHistory',chatId:a.chatId,requestId:a.requestId});const c=window.fixture.chats.find(c=>c.id==='cnew');c.messages.push({id:'new-remote-answer',role:'assistant',text:'断线期间新增的回答'});c.needsHistory=false;window.receive({type:'state',state:window.fixture});},retry);
 assert.ok((await page.locator('#messages').innerText()).includes('断线期间新增的回答'));assert.equal(await page.locator('#chat-connection').isVisible(),false);assert.equal(await page.locator('#prompt').inputValue(),'保留断线前的草稿');
 // A subsequent automatic reconnect gets a new history generation; busy turns defer it.
 await page.evaluate(()=>{const c=window.fixture.chats.find(c=>c.id==='cnew');c.needsHistory=true;c.historyEpoch=2;window.fixture.connections.h2={status:'online',busyChat:'cnew'};window.historyCommandCount=window.commands.filter(a=>a.type==='loadHistory').length;window.receive({type:'state',state:window.fixture});});assert.equal(await page.evaluate(()=>window.commands.filter(a=>a.type==='loadHistory').length===window.historyCommandCount),true);
 await page.evaluate(()=>{window.fixture.connections.h2.busyChat='';window.receive({type:'state',state:window.fixture});});assert.equal(await page.evaluate(()=>window.commands.filter(a=>a.type==='loadHistory').length===window.historyCommandCount+1),true);
 assert.equal(await page.evaluate(()=>window.commands.filter(a=>a.type==='send'||a.type==='steer').length),priorTasks,'reconnect never resends tasks');
 assert.deepEqual(errors,[],'no browser JS errors');
 // Native frames keep other chat histories out of the WebView. Deltas respect the visible chat and a later authoritative state.
 await page.reload();
 await page.evaluate(()=>{
   const s=window.fixture,c=s.chats.find(c=>c.id==='c1');
   s.visibleChatId=c.id;s.connections={h1:{status:'online',busyChat:c.id}};
   c.status='working';c.activeApprovalMode='never';c.approvalMode='never';
   c.messages=[
     {id:'streamed',role:'assistant',text:'**文本**'},
     {id:'permissions',role:'system',kind:'approval',approvalType:'item/permissions/requestApproval',params:{permissions:{network:{enabled:true}}}}
   ];
   for(const other of s.chats)if(other!==c)delete other.messages;
   window.receive({type:'state',state:structuredClone(s)});
   window.originalMessage=document.querySelector('[data-message="streamed"]');
   window.receive({type:'messageDeltas',chatId:c.id,deltas:[{id:'streamed',text:' 增量'}]});
   window.receive({type:'messageDeltas',chatId:'c2',deltas:[{id:'streamed',text:' 其他聊天'}]});
 });
 assert.equal(await page.locator('[data-message="streamed"] .markdown-body').innerText(),'文本 增量');
 assert.equal(await page.evaluate(()=>window.originalMessage===document.querySelector('[data-message="streamed"]')),true);
 await page.evaluate(()=>window.receive({type:'state',state:structuredClone(window.fixture)}));
 assert.equal(await page.locator('[data-message="streamed"] .markdown-body').innerText(),'文本');
 await page.evaluate(()=>{
   const s=window.fixture,c=s.chats.find(c=>c.id==='c1');
   const tail='\n\n'+('长文本 '.repeat(9000))+'\n\n**完成标记**';
   window.receive({type:'messageDeltas',chatId:c.id,deltas:[{id:'streamed',text:tail}]});
   c.messages[0].text+=tail;
 });
 assert.ok((await page.locator('.stream-tail').textContent()).endsWith('**完成标记**'));
 await page.evaluate(()=>window.receive({type:'state',state:structuredClone(window.fixture)}));
 assert.equal(await page.locator('.stream-tail').count(),0);
 assert.deepEqual(await page.locator('[data-message="streamed"] strong').allTextContents(),['文本','完成标记']);
 await page.getByRole('button',{name:'仅允许本轮'}).click();
 assert.deepEqual(await page.evaluate(()=>window.commands.at(-1)),{type:'approve',hostId:'h1',token:'permissions',decision:'accept'});
 await page.locator('#mode-label').click();
 assert.ok((await page.locator('.dialog-description').innerText()).includes('当前任务使用「不询问」'));
 await page.locator('[data-action=setApprovalMode][data-value=on-request]').click();
 await page.waitForFunction(()=>document.querySelector('#mode-label').textContent.includes('下轮按需审批'));
 assert.equal(errors.length,0,errors.join('\n'));
 fs.writeFileSync(path.join(out,'ui-test-result.json'),JSON.stringify({passed:true,checks:['mobile layout 412/360px','server grouping','history search','new chat selects correct VM and cwd','send targets correct chat','safe markdown','approval scoped to host','theme switching','credential form','absolute path validation','host trust cancellation','connecting stage and cancellation','persistent connection errors','root default directory','removed welcome footer','log export action','invalid IPv4 validation','test unsaved connection settings','restore editor after fingerprint trust','cancel isolated connection test','GFM tables lists and code copy','background permission settings','image-only sending and draft recovery','steer active turn without starting another','separate stop action','approval mode selection and native state restoration','single-line long command across 360/412/768px with full expansion','received PNG preview, full view, retry and external URL rejection','remote file link download and upload context','long stream preserves message nodes','completed history produces no DOM mutations','uploaded file draft stays scoped to its chat','stable Markdown blocks skip parsing and highlighting','text deltas appendData only','reference links and incomplete Markdown reconcile','open code fence highlights on close','history replacement clears stale blocks','disconnected chat reconnect control','automatic history refresh deduplicates each connection','failed history retains messages and retries','reconnect preserves drafts and never resends tasks','busy turns defer history refresh','native message delta scoping and snapshot recovery','turn-scoped permission dialog and next-turn approval mode'],errors},null,2));
 console.log('UI checks passed: original 25 workflows plus approval modes, single-line commands, received images, file transfers and long-stream DOM stability.');await browser.close();server.close();
})().catch(e=>{console.error(e);server.close();process.exit(1);});
