# generates architecture.dot (fixed-position layout, rendered with neato -n2)
P=72
B="#1F4E79"; G="#2E6B30"; O="#A0522D"; K="#444444"
impl={'app':("#9EC5EE",B),'data':("#A9D18E",G),'ai':("#F4B183",O),'ops':("#C9C9C9",K)}
plan={'app':("#EAF2FB",B),'data':("#EDF6E6",G),'ai':("#FDEEE2",O),'ops':("#F2F2F2",K)}
out=[]
def node(n,x,y,title,sub,kind,done,shape="box",w=1.25,h=0.42):
    f,c=(impl if done else plan)[kind]
    st="filled" if shape=="cylinder" else "rounded,filled"
    if not done: st+=",dashed"
    lab=f'<<b>{title}</b>' + (f'<br/><font point-size="8">{sub}</font>' if sub else '') + '>'
    out.append(f'  {n} [pos="{x*P:.0f},{y*P:.0f}!", label={lab}, shape={shape}, style="{st}", fillcolor="{f}", color="{c}", width={w}, height={h}, penwidth={1.3 if done else 1.1}];')
def frame(n,x0,y0,x1,y1,title,color,dashed,fs=9,chars=10):
    st="rounded,dashed" if dashed else "rounded"
    out.append(f'  {n} [pos="{(x0+x1)/2*P:.0f},{(y0+y1)/2*P:.0f}!", label="", shape=box, style="{st}", color="{color}", width={x1-x0:.2f}, height={y1-y0:.2f}, fixedsize=true, penwidth=1.0];')
    tw=chars*0.058*fs/9
    out.append(f'  {n}_t [pos="{(x0+0.08+tw/2)*P:.0f},{(y1-0.13)*P:.0f}!", label={title}, shape=plaintext, width={tw:.2f}, height=0.18, margin=0, fontsize={fs}, fontcolor="{color}"];')

# frames (drawn first = behind)
frame("k8s",1.25,0.25,9.70,3.55,'<<i>Kubernetes cluster (kubeadm) on GCP VMs — provisioned with Terraform + Ansible</i>>' ,"#666666",True,chars=82)
frame("pg",6.05,0.3,7.75,2.78,'<<b>PostgreSQL 16</b>>',G,False,chars=13)
frame("mon",7.90,0.38,9.55,1.95,'<<b>Monitoring</b>>',K,True,chars=10)

node("browser",0.55,1.75,"User browser","",'ops',True,w=0.95)
out[-1]=out[-1].replace('fillcolor="#C9C9C9"','fillcolor="#FFFFFF"')
node("ingress",1.95,3.0,"Ingress","NGINX controller",'ops',False,w=1.15)
node("frontend",3.2,2.2,"Frontend","Next.js",'app',True,w=1.1)
node("keycloak",3.2,0.8,"Keycloak 26","OIDC provider",'ops',True,w=1.1)
node("recsvc",5.15,3.0,"Recommendation API","Quarkus / Python",'app',False,w=1.45)
node("movies",5.15,2.2,"Movies API","Quarkus",'app',True)
node("profiles",5.15,1.5,"Profiles API","Quarkus",'app',True)
node("recdb",6.90,3.08,"Rec. cache","per profile",'data',False,shape="cylinder",w=1.0,h=0.5)
node("mvdb",6.90,2.0,"movies","schema",'data',True,shape="cylinder",w=1.0,h=0.46)
node("prdb",6.90,1.42,"profiles","schema",'data',True,shape="cylinder",w=1.0,h=0.46)
node("kcdb",6.90,0.8,"keycloak","database",'data',True,shape="cylinder",w=1.0,h=0.46)
node("agent",8.70,3.0,"Rec. agent","background worker",'ai',False,w=1.3)
node("llm",8.70,2.3,"Inference engine","LLM / embeddings (Ollama)",'ai',False,w=1.55)
node("prom",8.70,1.35,"Prometheus","scrapes /q/metrics",'ops',False,w=1.3)
node("grafana",8.70,0.75,"Grafana","dashboards",'ops',False,w=1.3)
# note on shared DB
out.append(f'  pgnote [pos="{6.90*P:.0f},{2.4*P:.0f}!", label="today: one shared instance\\nplanned: one per service", shape=plaintext, fontsize=8, fontcolor="#2E6B30", width=1.6, height=0.2, ];')
def lab(x,y,t,fs=8,col="#333333"):
    out.append(f'  "l_{x}_{y}" [pos="{x*P:.0f},{y*P:.0f}!", label="{t}", shape=plaintext, fontsize={fs}, fontcolor="{col}", width=0.1, height=0.1, margin=0];')
lab(1.75,2.15,"HTTPS")
lab(6.90,0.43,"planned: PV/PVC storage",fs=8,col="#2E6B30")
lab(1.5,1.1,"OIDC login\\n(code + PKCE)")
lab(4.1,2.31,"Bearer JWT")
lab(4.85,1.02,"validate JWT\\n(JWKS)",col="#555555")
lab(7.73,3.27,"precompute")

# legend
out.append(f'  lg1 [pos="{0.55*P:.0f},{0.85*P:.0f}!", label="implemented", shape=box, style="rounded,filled", fillcolor="#9EC5EE", color="{B}", penwidth=1.3, width=0.95, height=0.24, fontsize=8, fixedsize=true];')
out.append(f'  lg2 [pos="{0.55*P:.0f},{0.5*P:.0f}!", label="planned", shape=box, style="rounded,filled,dashed", fillcolor="#EAF2FB", color="{B}", penwidth=1.1, width=0.95, height=0.24, fontsize=8, fixedsize=true];')

edges='''
  // implemented (solid)
  browser -> frontend;
  browser -> keycloak [dir=both];
  frontend -> movies;
  frontend -> profiles;
  movies -> keycloak  [style=dotted, color="#666666"];
  profiles -> keycloak [style=dotted, color="#666666"];
  movies -> mvdb; profiles -> prdb; keycloak -> kcdb;
  // planned (dashed)
  browser -> ingress [style=dashed];
  ingress -> frontend [style=dashed];
  frontend -> recsvc [style=dashed];
  recsvc -> recdb [style=dashed];
  agent -> recdb [style=dashed];
  agent -> llm [style=dashed];
  prom -> grafana [style=dashed, dir=back];
'''
hdr='''// Architecture of the AI-powered Movie Recommendation Hub (PSIA, group 17).
// Solid border + saturated fill = implemented; dashed border + light fill = planned.
// Palette: blue = application services, green = data, orange = AI, grey = ops/infra.
// Fixed-position layout. Render with:
//   neato -n2 -Tpdf architecture.dot -o architecture.pdf
//   neato -n2 -Tpng -Gdpi=300 architecture.dot -o architecture.png
digraph arch {
  graph [fontname="Liberation Sans", splines=false, pad=0.03, margin=0, outputorder=nodesfirst];
  node  [fontname="Liberation Sans", fontsize=10, margin="0.04,0.02"];
  edge  [fontname="Liberation Sans", fontsize=8, arrowsize=0.55, penwidth=0.9, color="#444444"];
'''
open("architecture.dot","w").write(hdr+"\n".join(out)+"\n"+edges+"}\n")
