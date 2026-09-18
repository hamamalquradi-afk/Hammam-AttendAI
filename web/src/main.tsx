import React,{useEffect,useMemo,useState} from 'react';
import{createRoot}from'react-dom/client';
import'./style.css';

type Module={id:string,title:string,description:string,localCore:boolean};
const modules:Module[]=[
 {id:'students',title:'الطلاب',description:'إدارة الطلاب والاستيراد والأرشفة. العمليات الأساسية تتم في تطبيق Android المحلي.',localCore:true},
 {id:'teachers',title:'المدرسون',description:'إدارة المدرسين وربطهم بالمواد وإعداد التقارير.',localCore:true},
 {id:'subjects',title:'المواد',description:'المواد والجدول وسياسات الحضور.',localCore:true},
 {id:'reports',title:'التقارير',description:'سجل التقارير وحالات الاعتماد والإرسال. الإرسال الخارجي يحتاج Backend مضبوطًا.',localCore:true},
 {id:'analytics',title:'التحليلات',description:'ملخصات واتجاهات الحضور من البيانات المسموح بمزامنتها.',localCore:true},
 {id:'audit',title:'Audit Log',description:'عرض سجل التغييرات المصرح بمزامنتها بدون أسرار.',localCore:true},
 {id:'settings',title:'الإعدادات',description:'Feature flags وإعدادات الخدمات السحابية الاختيارية.',localCore:true},
 {id:'health',title:'System Health',description:'حالة Backend والمزامنة والمهام الخارجية.',localCore:false},
];

function App(){
 const [selected,setSelected]=useState('health');
 const [health,setHealth]=useState<'checking'|'online'|'offline'>('checking');
 const api=(import.meta as any).env?.VITE_API_BASE_URL||'http://localhost:8080';
 useEffect(()=>{let alive=true;fetch(`${api}/health`,{cache:'no-store'}).then(r=>{if(alive)setHealth(r.ok?'online':'offline')}).catch(()=>{if(alive)setHealth('offline')});return()=>{alive=false}},[api]);
 const current=useMemo(()=>modules.find(m=>m.id===selected)??modules[0],[selected]);
 return <main dir="rtl">
  <header><div><h1>همّام للحضور الذكي</h1><p>Hammam AttendAI Admin</p></div><span className={`pill ${health}`}>Backend: {health}</span></header>
  <section className="hero"><h2>لوحة الإدارة الاختيارية</h2><p>Android وRoom هما مصدر الحقيقة أثناء Offline. هذه الواجهة لا تستبدل الوظائف المحلية ولا تستخدم نماذج خارجية.</p></section>
  <section className="layout">
   <nav className="grid" aria-label="Admin modules">{modules.map(m=><button className={selected===m.id?'module active':'module'} key={m.id} onClick={()=>setSelected(m.id)}><strong>{m.title}</strong><span>{m.localCore?'Local-first core':'Online diagnostics'}</span></button>)}</nav>
   <article className="detail"><h3>{current.title}</h3><p>{current.description}</p>{current.id==='health'&&<dl><dt>API Base</dt><dd>{api}</dd><dt>Backend status</dt><dd>{health}</dd></dl>}<p className="note">عمليات CRUD السحابية لا تُعرض على أنها متاحة قبل تفعيل Cloud Sync والمصادقة في Backend.</p></article>
  </section>
 </main>
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
