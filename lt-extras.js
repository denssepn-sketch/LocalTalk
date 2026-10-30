// LocalTalk extras — carregar DEPOIS do script principal do index.html
(()=>{
let RP=null,OPEN=0,PIC=localStorage.lt_pic||'';
const mk=h=>{const d=document.createElement('div');d.innerHTML=h;return d.firstElementChild};
const rs=(f,mx,q)=>new Promise((ok,no)=>{const i=new Image,u=URL.createObjectURL(f);i.onload=()=>{const s=Math.min(1,mx/Math.max(i.width,i.height)),c=document.createElement('canvas');c.width=i.width*s;c.height=i.height*s;c.getContext('2d').drawImage(i,0,0,c.width,c.height);URL.revokeObjectURL(u);ok(c.toDataURL('image/jpeg',q))};i.onerror=no;i.src=u});
const av=(pic,n)=>{const e=pic?new Image():E('div','',(n||'?')[0].toUpperCase());if(pic)e.src=pic;e.style.cssText='width:44px;height:44px;border-radius:50%;object-fit:cover;background:var(--a);display:flex;align-items:center;justify-content:center;font-weight:700;border:2px solid var(--r)';return e};
const fin=accept=>{const i=document.createElement('input');i.type='file';i.accept=accept;i.hidden=true;document.body.append(i);return i};

// ---------- Responder mensagem ----------
const rpb=mk('<div hidden style="display:flex;gap:8px;align-items:center;background:var(--p);border-left:3px solid var(--a);border-radius:8px;padding:6px 10px;font-size:12px;color:var(--m)"><span style="flex:1"></span><button style="padding:2px 8px">✕</button></div>');
$('#inp').before(rpb);
const rpUi=()=>{rpb.hidden=!RP;rpb.firstChild.textContent=RP?'Respondendo a '+RP.n+': '+RP.x:''};
rpb.lastChild.onclick=()=>{RP=null;rpUi()};

draw=function(m){const b=E('div','m '+(m.sys?'s':m.from==ID?'me':'th'));
if(!m.sys&&m.from!=ID)b.append(E('small','',m.name));
if(!m.sys&&(m.ch||'Geral')!=chn)b.append(E('small','','# '+(m.ch||'Geral')));
if(m.rp){const q=E('div','',m.rp.n+': '+m.rp.x);q.style.cssText='border-left:3px solid #fff8;padding:2px 8px;margin-bottom:4px;opacity:.8;font-size:12px';b.append(q)}
if(m.img){const i=new Image();i.src=m.img;i.style.cssText='max-width:100%;border-radius:10px;display:block';b.append(i)}
if(m.text&&!(m.img&&m.text=='📷 Foto'))b.append(E('div','',m.text));
if(!m.sys){const s=E('small','',new Date(m.ts).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'})+stat(m)),f=E('span','fv'+(m.fav?' on':''),'⭐');
f.onclick=e=>{e.stopPropagation();m.fav=m.fav?0:1;save();render()};s.prepend(f);b.append(s);
b.onclick=()=>{RP={n:m.from==ID?'Você':m.name,x:(m.text||'📷').slice(0,60)};rpUi();$('#tx').focus()}}
$('#msgs').append(b);$('#msgs').scrollTop=1e9};

send=function(v0,img){const v=(v0!=null?v0:$('#tx').value).trim();if(!v&&!img)return;if(v0==null)$('#tx').value='';
const m={t:'msg',id:uid(),from:ID,name:NAME,text:v||'📷 Foto',ts:Date.now(),ak:[],ch:chn};
if(RP)m.rp=RP;if(img)m.img=img;RP=null;rpUi();
m.pd=Object.values(peers).some(x=>x.hi&&x.on)?0:1;seen.add(m.id);add(m);all(Object.assign(pub(m),{rp:m.rp,img:m.img}))};
$('#snd').onclick=()=>send();

// ---------- Emoji, galeria e câmera ----------
const em='😀 😂 😍 😎 😢 😡 👍 👎 🙏 👏 🔥 ❤️ ✅ ❌ ⚠️ 🆘 📍 🎉 😮 🤝 💪 ☕ 🚗 🏠'.split(' ');
const ep=mk('<div hidden style="display:flex;flex-wrap:wrap;gap:4px;background:var(--p);padding:8px;border-radius:12px"></div>');
em.forEach(x=>{const b=E('button','',x);b.style.cssText='background:none;padding:4px 6px;font-size:20px';b.onclick=()=>{$('#tx').value+=x;$('#tx').focus()};ep.append(b)});
$('#inp').after(ep);
const gal=fin('image/*'),cam=fin('image/*');cam.setAttribute('capture','environment');
const bE=E('button','','😊'),bG=E('button','','🖼'),bC=E('button','','📷');
bE.onclick=()=>ep.hidden=!ep.hidden;bG.onclick=()=>gal.click();bC.onclick=()=>cam.click();
$('#inp').prepend(bE,bG,bC);
[gal,cam].forEach(i=>i.onchange=async()=>{const f=i.files[0];i.value='';if(!f)return;try{send('',await rs(f,640,.6))}catch(e){sys('Não consegui abrir a imagem')}});

// ---------- Foto de perfil ----------
const pv=E('span'),pbt=E('button','','📷 Escolher foto de perfil'),pw=mk('<div style="display:flex;gap:12px;align-items:center"></div>');
pw.append(pv,pbt);$('#v-cfg').prepend(pw);$('#v-cfg').prepend(E('label','','Foto de perfil'));
const showPv=()=>{pv.textContent='';pv.append(av(PIC,NAME))};showPv();
const pin=fin('image/*');pbt.onclick=()=>pin.click();
pin.onchange=async()=>{const f=pin.files[0];pin.value='';if(!f)return;try{PIC=await rs(f,96,.7);localStorage.lt_pic=PIC;all({t:'pic',d:PIC});showPv()}catch(e){}};

// ---------- Microfone aberto (só administrador) ----------
const ck=mk('<label class="ck" hidden><input type="checkbox">🎙 Microfone aberto (transmite direto, só administrador)</label>'),om=ck.firstChild;
$('#v-cfg').append(ck);
const setOpen=on=>{if(on){if(!mic||txOn)return;OPEN=1;pttOn();if(!txOn)OPEN=0}else if(OPEN){OPEN=0;pttOff()}};
om.onchange=e=>{if(e.target.checked){setOpen(1);if(!OPEN){e.target.checked=false;sys('Não foi possível abrir o microfone agora (canal ocupado ou sem microfone).')}}else setOpen(0)};
const _off=pttOff;pttOff=function(){if(!OPEN)_off()};
pb.onpointerup=pb.onpointercancel=()=>pttOff();

// ---------- Foto de quem está falando ----------
const spk=mk('<div style="display:flex;gap:10px;flex-wrap:wrap"></div>');$('#bar').before(spk);
const _ui=ui;ui=function(){_ui();ck.hidden=adm!=ID;if(adm!=ID&&OPEN){setOpen(0);om.checked=false}
spk.textContent='';const add1=(pic,n)=>{const w=E('div');w.style.cssText='text-align:center;font-size:11px;color:var(--m)';w.append(av(pic,n),E('div','',n));spk.append(w)};
if(txOn)add1(PIC,NAME);Object.keys(talk).forEach(i=>{const p=peers[i];if(p)add1(p.pic,dn(p))});showPv()};

// ---------- Rede: receber foto/respostas/imagens e enviar foto ao conectar ----------
const _dcs=dcs;dcs=function(p,dc){_dcs(p,dc);const o=dc.onopen;dc.onopen=()=>{o();if(PIC)tx(p,{t:'pic',d:PIC})}};
const _on=on;on=function(p,m){const r=_on(p,m);
if(m.t=='pic'){if(typeof m.d=='string'&&m.d.length<30000&&m.d.startsWith('data:image/'))p.pic=m.d;ui()}
else if(m.t=='msg'&&(m.img||m.rp)){const h=hist.find(x=>x.id==m.id);if(h&&!h.img&&!h.rp){
if(typeof m.img=='string'&&m.img.startsWith('data:image/jpeg')&&m.img.length<150000)h.img=m.img;
if(m.rp&&typeof m.rp.n=='string')h.rp={n:m.rp.n.slice(0,20),x:String(m.rp.x||'').slice(0,60)};save();render()}}
return r};
})();
