import http from 'node:http';
import {createHash,timingSafeEqual} from 'node:crypto';

type Json=Record<string,unknown>;
const env=(k:string,d='')=>String(process.env[k]??d);
const port=Number(env('PORT','8080'));
const limits=new Map<string,{count:number;reset:number}>();
const idempotency=new Map<string,{status:number;body:Json;expires:number}>();

function json(res:any,status:number,body:Json){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(body));}
function requestId(req:any){return String(req.headers['x-request-id']||cryptoRandom());}
function cryptoRandom(){return createHash('sha256').update(`${Date.now()}-${Math.random()}`).digest('hex').slice(0,16)}
function secureLog(event:string,data:Json){console.log(JSON.stringify({ts:new Date().toISOString(),event,...data}));}
function authorized(req:any){const expected=env('AUTH_TOKEN_HASH'); if(!expected)return env('APP_ENV','development').toLowerCase()!=='production'; const token=String(req.headers.authorization||'').replace(/^Bearer\s+/i,''); const actual=createHash('sha256').update(token).digest(); const exp=Buffer.from(expected,'hex'); return exp.length===actual.length&&timingSafeEqual(exp,actual)}
function rateLimit(req:any){const key=String(req.socket.remoteAddress||'unknown');const now=Date.now();const v=limits.get(key);if(!v||v.reset<now){limits.set(key,{count:1,reset:now+60_000});return true}v.count++;return v.count<=120}
async function body(req:any):Promise<Json>{let raw='';for await(const c of req){raw+=c;if(raw.length>8_000_000)throw new Error('PAYLOAD_TOO_LARGE')}if(!raw)return{};const value=JSON.parse(raw);if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('INVALID_JSON');return value}
async function relay(url:string,token:string,payload:Json){if(!url)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED'};const r=await fetch(url,{method:'POST',headers:{'content-type':'application/json',...(token?{authorization:`Bearer ${token}`}:{})},body:JSON.stringify(payload)});const text=await r.text();return{ok:r.ok,status:r.status,body:text.slice(0,2000)}}

function aiKey(provider:string){const p=provider.toUpperCase();return p==='OPENAI'?env('OPENAI_API_KEY'):p==='GEMINI'?env('GEMINI_API_KEY'):p==='CLAUDE'?env('ANTHROPIC_API_KEY'):''}
function textCandidate(id:string){const x=id.toLowerCase();return !['embedding','moderation','image','dall-e','tts','transcribe','whisper','realtime','audio','sora'].some(v=>x.includes(v))}
function extractText(value:any):string{
 if(!value)return'';if(typeof value==='string')return value;
 if(Array.isArray(value))return value.map(extractText).filter(Boolean).join('\n');
 if(typeof value==='object'){if(typeof value.output_text==='string')return value.output_text;if(value.type==='text'&&typeof value.text==='string')return value.text;if(typeof value.text==='string')return value.text;return Object.entries(value).filter(([k])=>!['id','model','usage'].includes(k)).map(([,v])=>extractText(v)).filter(Boolean).join('\n')}
 return''
}
async function fetchJson(url:string,init:any){const r=await fetch(url,init);const text=await r.text();let data:any={};try{data=JSON.parse(text)}catch{}return{ok:r.ok,status:r.status,text,data}}
async function providerModels(provider:string){
 const p=provider.toUpperCase(),key=aiKey(p);if(!key)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED',models:[] as string[]};
 if(p==='OPENAI'){const r=await fetchJson('https://api.openai.com/v1/models',{headers:{authorization:`Bearer ${key}`}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`OPENAI_${r.status}`,models:Array.isArray(r.data?.data)?r.data.data.map((m:any)=>String(m.id||'')).filter(textCandidate):[]}}
 if(p==='GEMINI'){const r=await fetchJson('https://generativelanguage.googleapis.com/v1beta/models',{headers:{'x-goog-api-key':key}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`GEMINI_${r.status}`,models:Array.isArray(r.data?.models)?r.data.models.filter((m:any)=>!Array.isArray(m.supportedGenerationMethods)||m.supportedGenerationMethods.includes('generateContent')).map((m:any)=>String(m.name||'')).filter(Boolean):[]}}
 if(p==='CLAUDE'){const r=await fetchJson('https://api.anthropic.com/v1/models',{headers:{'x-api-key':key,'anthropic-version':'2023-06-01'}});return{ok:r.ok,status:r.status,error:r.ok?undefined:`CLAUDE_${r.status}`,models:Array.isArray(r.data?.data)?r.data.data.map((m:any)=>String(m.id||'')).filter(Boolean):[]}}
 return{ok:false,status:400,error:'UNSUPPORTED_AI_PROVIDER',models:[] as string[]}
}
async function providerGenerate(provider:string,model:string,prompt:string,vision?:{mime:string;base64:string}){
 const p=provider.toUpperCase(),key=aiKey(p);if(!key)return{ok:false,status:503,error:'PROVIDER_NOT_CONFIGURED',text:''};if(!model)return{ok:false,status:400,error:'MODEL_REQUIRED',text:''};
 if(p==='OPENAI'){const content:any[]= [{type:'input_text',text:prompt}];if(vision)content.push(vision.mime==='application/pdf'?{type:'input_file',filename:'timetable.pdf',file_data:`data:application/pdf;base64,${vision.base64}`}:{type:'input_image',image_url:`data:${vision.mime};base64,${vision.base64}`});const payload=vision?{model,input:[{role:'user',content}]}:{model,input:prompt};const r=await fetchJson('https://api.openai.com/v1/responses',{method:'POST',headers:{authorization:`Bearer ${key}`,'content-type':'application/json'},body:JSON.stringify(payload)});return{ok:r.ok,status:r.status,error:r.ok?undefined:`OPENAI_${r.status}`,text:extractText(r.data)}}
 if(p==='GEMINI'){const parts:any[]=[{text:prompt}];if(vision)parts.push({inline_data:{mime_type:vision.mime,data:vision.base64}});const r=await fetchJson(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model.replace(/^models\//,''))}:generateContent`,{method:'POST',headers:{'x-goog-api-key':key,'content-type':'application/json'},body:JSON.stringify({contents:[{parts}]})});return{ok:r.ok,status:r.status,error:r.ok?undefined:`GEMINI_${r.status}`,text:extractText(r.data?.candidates?.[0]?.content?.parts)}}
 if(p==='CLAUDE'){const content:any[]=vision?[{type:vision.mime==='application/pdf'?'document':'image',source:{type:'base64',media_type:vision.mime,data:vision.base64}},{type:'text',text:prompt}]:[{type:'text',text:prompt}];const r=await fetchJson('https://api.anthropic.com/v1/messages',{method:'POST',headers:{'x-api-key':key,'anthropic-version':'2023-06-01','content-type':'application/json'},body:JSON.stringify({model,max_tokens:2048,messages:[{role:'user',content}]})});return{ok:r.ok,status:r.status,error:r.ok?undefined:`CLAUDE_${r.status}`,text:extractText(r.data?.content)}}
 return{ok:false,status:400,error:'UNSUPPORTED_AI_PROVIDER',text:''}
}

const server=http.createServer(async(req:any,res:any)=>{
 const rid=requestId(req);res.setHeader('x-request-id',rid);try{
  if(!rateLimit(req))return json(res,429,{error:'RATE_LIMITED',request_id:rid});
  const url=new URL(req.url||'/',`http://${req.headers.host||'localhost'}`); if(url.pathname==='/health')return json(res,200,{ok:true,service:'Hammam AttendAI Backend'});
  if(!url.pathname.startsWith('/api/v1/'))return json(res,404,{error:'NOT_FOUND',request_id:rid});
  if(!authorized(req))return json(res,401,{error:'UNAUTHORIZED',request_id:rid});
  if(req.method!=='POST')return json(res,405,{error:'METHOD_NOT_ALLOWED',request_id:rid});
  const key=String(req.headers['idempotency-key']||'');if(!key)return json(res,400,{error:'IDEMPOTENCY_KEY_REQUIRED',request_id:rid});
  const cached=idempotency.get(key);if(cached&&cached.expires>Date.now())return json(res,cached.status,cached.body);
  const data=await body(req); let status=200; let out:Json={ok:true};
  if(url.pathname==='/api/v1/notifications/whatsapp'){
    const r=await relay(env('WHATSAPP_PROVIDER_URL'),env('WHATSAPP_ACCESS_TOKEN'),data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_message_id:createHash('sha256').update(key).digest('hex').slice(0,20)}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/notifications/email'){
    const r=await relay(env('EMAIL_PROVIDER_URL'),env('EMAIL_API_KEY'),data);status=r.ok?200:r.status;out=r.ok?{ok:true}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/reports/send'){
    const channel=String((data as any).channel||'').toUpperCase();
    const providerUrl=channel==='WHATSAPP'?env('WHATSAPP_PROVIDER_URL'):channel==='EMAIL'?env('EMAIL_PROVIDER_URL'):env('REPORT_PROVIDER_URL');
    const providerToken=channel==='WHATSAPP'?env('WHATSAPP_ACCESS_TOKEN'):channel==='EMAIL'?env('EMAIL_API_KEY'):env('REPORT_PROVIDER_TOKEN');
    const r=await relay(providerUrl,providerToken,data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_message_id:createHash('sha256').update(key).digest('hex').slice(0,20)}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/ai/providers/models'){
    const provider=String((data as any).provider||'').toUpperCase();const r=await providerModels(provider);status=r.ok?200:r.status;out=r.ok?{ok:true,models:r.models}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/providers/summarize'){
    const provider=String((data as any).provider||'').toUpperCase(),model=String((data as any).model||''),prompt=String((data as any).prompt||''),facts=(data as any).facts;
    if(!facts||typeof facts!=='object')return json(res,400,{error:'FACTS_REQUIRED',request_id:rid});const grounded=`Authoritative facts: ${JSON.stringify(facts)}\nTask: ${prompt}\nDo not invent numbers. State when data is insufficient.`;const r=await providerGenerate(provider,model,grounded);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.text}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/providers/vision'){
    const provider=String((data as any).provider||'').toUpperCase(),model=String((data as any).model||''),prompt=String((data as any).prompt||''),mime=String((data as any).mime||''),image=String((data as any).image_base64||'');if(!mime||!image)return json(res,400,{error:'IMAGE_REQUIRED',request_id:rid});const r=await providerGenerate(provider,model,prompt,{mime,base64:image});status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.text}:{ok:false,error:r.error};
  }else if(url.pathname==='/api/v1/ai/grounded-summary'){
    const facts=(data as any).facts;if(!facts||typeof facts!=='object')return json(res,400,{error:'FACTS_REQUIRED',request_id:rid});
    const r=await relay(env('AI_PROVIDER_URL'),env('AI_API_KEY'),data);status=r.ok?200:r.status;out=r.ok?{ok:true,provider_response:r.body}:{ok:false,error:r.error||`PROVIDER_${r.status}`};
  }else if(url.pathname==='/api/v1/sync/push'){
    if(env('CLOUD_SYNC_ENABLED','false')!=='true'){status=503;out={ok:false,error:'CLOUD_SYNC_DISABLED'}} else out={ok:true,accepted:true};
  }else if(url.pathname==='/api/v1/backups/upload'){
    if(env('REMOTE_BACKUP_ENABLED','false')!=='true'){status=503;out={ok:false,error:'REMOTE_BACKUP_DISABLED'}} else out={ok:true,accepted:true};
  }else{status=404;out={error:'NOT_FOUND',request_id:rid}}
  idempotency.set(key,{status,body:out,expires:Date.now()+24*3600_000});secureLog('request',{request_id:rid,path:url.pathname,status});return json(res,status,out)
 }catch(e:any){const code=e?.message==='PAYLOAD_TOO_LARGE'?413:400;secureLog('error',{request_id:rid,code:e?.message||'UNKNOWN'});return json(res,code,{error:'REQUEST_FAILED',request_id:rid})}
});
server.listen(port,()=>secureLog('startup',{port,env:env('APP_ENV','development')}));
