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
 assert.deepEqual(errors,[],'no browser JS errors');
 fs.writeFileSync(path.join(out,'ui-test-result.json'),JSON.stringify({passed:true,checks:['mobile layout 412/360px','server grouping','history search','new chat selects correct VM and cwd','send targets correct chat','safe markdown','approval scoped to host','theme switching','credential form','absolute path validation','host trust cancellation','connecting stage and cancellation','persistent connection errors','root default directory','removed welcome footer','log export action','invalid IPv4 validation','test unsaved connection settings','restore editor after fingerprint trust','cancel isolated connection test','GFM tables lists and code copy','background permission settings'],errors},null,2));
 console.log('UI checks passed: 22 workflows; screenshots saved.');await browser.close();server.close();
})().catch(e=>{console.error(e);server.close();process.exit(1);});
