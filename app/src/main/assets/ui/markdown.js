'use strict';
// Only bundled parsers run here. Remote HTML and remote image loads stay disabled.
window.renderMarkdown=(()=>{
  const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const renderer={
    html(token){return escape(token.text);},
    code(token){
      const lang=(token.lang||'').trim().split(/\s+/)[0],body=token.text||'';
      let code=escape(body);
      if(body.length<30000&&lang&&hljs.getLanguage(lang))try{code=hljs.highlight(body,{language:lang,ignoreIllegals:true}).value;}catch(_){}
      return `<figure class="code-block"><figcaption><span>${escape(lang||'代码')}</span><button type="button" data-action="copyCode" aria-label="复制代码">复制代码</button></figcaption><pre><code class="hljs">${code}</code></pre></figure>`;
    },
    link(token){
      const label=this.parser.parseInline(token.tokens);let url;
      try{url=new URL(token.href);}catch(_){return label;}
      if(!['http:','https:'].includes(url.protocol))return label;
      return `<a href="${escape(url.href)}" rel="noreferrer noopener">${label}</a>`;
    },
    image(token){return `<span class="md-image">[图片：${escape(token.text||'图片')}]</span>`;}
  };
  const parser=new marked.Marked({gfm:true,breaks:false,renderer});
  return text=>{
    try{
      const clean=DOMPurify.sanitize(parser.parse(String(text??'')),{
        ALLOWED_TAGS:['p','br','hr','h1','h2','h3','h4','h5','h6','strong','em','del','s','ul','ol','li','blockquote','pre','code','span','a','table','thead','tbody','tr','th','td','figure','figcaption','button','input'],
        ALLOWED_ATTR:['class','href','rel','title','type','disabled','checked','start','align','data-action','aria-label'],ALLOW_DATA_ATTR:false
      });
      const template=document.createElement('template');template.innerHTML=clean;
      template.content.querySelectorAll('table').forEach(table=>{const wrap=document.createElement('div');wrap.className='markdown-table';table.replaceWith(wrap);wrap.append(table);});
      return template.innerHTML;
    }catch(_){return `<p>${escape(text).replace(/\n/g,'<br>')}</p>`;}
  };
})();
