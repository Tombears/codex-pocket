'use strict';
// Only bundled parsers run here. Remote HTML and remote image loads stay disabled.
window.renderMarkdown=(()=>{
  const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const renderer={
    html(token){return escape(token.text);},
    code(token){
      const lang=(token.lang||'').trim().split(/\s+/)[0],body=token.text||'';
      let code=escape(body);
      const fence=token.raw?.match(/^ {0,3}(`{3,}|~{3,})/);
      const closed=!fence||new RegExp('\\n {0,3}'+fence[1][0]+'{'+fence[1].length+',}[ \\t]*(?:\\n)?$').test(token.raw);
      // Keep an unfinished fence as one text node; highlight once it closes.
      if(closed&&body.length<30000&&lang&&hljs.getLanguage(lang))try{code=hljs.highlight(body,{language:lang,ignoreIllegals:true}).value;}catch(_){}
      return `<figure class="code-block"><figcaption><span>${escape(lang||'代码')}</span><button type="button" data-action="copyCode" aria-label="复制代码">复制代码</button></figcaption><pre><code class="hljs">${code}</code></pre></figure>`;
    },
    link(token){
      const label=this.parser.parseInline(token.tokens);let url;
      const path=token.href||'';
      if(path&&!path.startsWith('#')&&!path.startsWith('//')&&(!/^[a-z][a-z0-9+.-]*:/i.test(path)||/^(file:\/\/\/|sandbox:\/)/i.test(path))){
        return `<a href="#" data-remote-path="${escape(path)}" title="下载服务器文件">${label}</a>`;
      }
      try{url=new URL(token.href);}catch(_){return label;}
      if(!['http:','https:'].includes(url.protocol))return label;
      return `<a href="${escape(url.href)}" rel="noreferrer noopener">${label}</a>`;
    },
    image(token){return `<span class="md-image">[图片：${escape(token.text||'图片')}]</span>`;}
  };
  const parser=new marked.Marked({gfm:true,breaks:false,renderer});
  function fragment(html){
      const clean=DOMPurify.sanitize(html,{
        ALLOWED_TAGS:['p','br','hr','h1','h2','h3','h4','h5','h6','strong','em','del','s','ul','ol','li','blockquote','pre','code','span','a','table','thead','tbody','tr','th','td','figure','figcaption','button','input'],
        ALLOWED_ATTR:['class','href','rel','title','type','disabled','checked','start','align','data-action','data-remote-path','aria-label'],ADD_URI_SAFE_ATTR:['data-remote-path'],ALLOW_DATA_ATTR:false
      });
      const template=document.createElement('template');template.innerHTML=clean;
      template.content.querySelectorAll('table').forEach(table=>{const wrap=document.createElement('div');wrap.className='markdown-table';table.replaceWith(wrap);wrap.append(table);});
      return template;
  }
  const render=text=>{
    try{return fragment(parser.parse(String(text??''))).innerHTML;}
    catch(_){return `<p>${escape(text).replace(/\n/g,'<br>')}</p>`;}
  };
  // Lexing retains Markdown context (lists, tables and reference definitions).
  // Only changed blocks are parsed, sanitized and patched into the live DOM.
  window.updateMarkdown=(container,text,patch)=>{
    text=String(text??'');if(container._markdownText===text)return;
    try{
      const tokens=parser.lexer(text),links=JSON.stringify(tokens.links||{});
      const previous=container._markdownBlocks||[],next=[];
      for(const token of tokens){
        if(token.type==='space'||token.type==='def')continue;
        const index=next.length,old=previous[index],key=token.type+'\n'+token.raw;
        let block=old?.node;
        if(!old||old.key!==key||container._markdownLinks!==links){
          const group=[token];group.links=tokens.links;
          const template=fragment(parser.parser(group,parser.defaults));
          const updated=document.createElement('div');updated.className='markdown-block';updated.append(template.content);
          if(block)patch(block,updated);else block=updated;
        }
        if(container.children[index]!==block)container.insertBefore(block,container.children[index]||null);
        next.push({key,node:block});
      }
      while(container.children.length>next.length)container.lastElementChild.remove();
      container._markdownBlocks=next;container._markdownLinks=links;container._markdownText=text;
    }catch(_){
      const fallback=document.createElement('div');fallback.textContent=text;
      container.replaceChildren(fallback);delete container._markdownText;delete container._markdownBlocks;
    }
  };
  return render;
})();
