/* GPL-3.0: diagnostic CLI using the bundled helper's QRTR code. No preference setters.
 * --nas-control-reset additionally resets this control point (NAS 0x0000). */
#define NH_QMI_NO_ENTRY
#include "../app/src/main/cpp/qmi/nh-qmi.c"

static void emit_probe(struct state*s,const char*step,int ok){
 char out[1024];u32 pos=0;
 jput(out,&pos,sizeof(out),"{\"step\":");jstr(out,&pos,sizeof(out),step);
 jput(out,&pos,sizeof(out),",\"ok\":");jbool(out,&pos,sizeof(out),ok);
 jput(out,&pos,sizeof(out),",\"sim\":");jint(out,&pos,sizeof(out),(u32)s->sim);
 jput(out,&pos,sizeof(out),",\"qmi_result\":");jint(out,&pos,sizeof(out),s->last_op.result);
 jput(out,&pos,sizeof(out),",\"qmi_error\":");jint(out,&pos,sizeof(out),s->last_op.error);
 jput(out,&pos,sizeof(out),",\"stage\":");jint(out,&pos,sizeof(out),(u32)s->bind_failure_stage);
 jput(out,&pos,sizeof(out),",\"status\":");jstr(out,&pos,sizeof(out),s->status);
 jput(out,&pos,sizeof(out),",\"tx\":\"");jhex_bytes(out,&pos,sizeof(out),s->diag.tx,s->diag.tx_len);
 jput(out,&pos,sizeof(out),"\",\"rx\":\"");jhex_bytes(out,&pos,sizeof(out),s->diag.rx,s->diag.rx_len);
 jput(out,&pos,sizeof(out),"\"}\n");sc3(SYS_write,1,(s64)out,pos);
}
void probe_start(u64*stack){
 struct state s;int ok;zero(&s,sizeof(s));s.fd=-1;s.sim=1;s.mon_fd[0]=-1;s.mon_fd[1]=-1;
 ok=open_nas(&s);emit_probe(&s,"open",ok);
 if(ok){
  ok=reopen_bound(&s,1);emit_probe(&s,"bind-primary",ok);
  if(ok){ok=query(&s);emit_probe(&s,"read-primary",ok);}
  ok=reopen_bound(&s,2);emit_probe(&s,"bind-secondary",ok);
  if(ok){ok=query(&s);emit_probe(&s,"read-secondary",ok);}
  {
   int did_set,shutdown_req;char stage[16];
   do_command(&s,"{\"cmd\":\"session_reset\",\"sim\":1}",&ok,&did_set,&shutdown_req,stage,sizeof(stage));
   emit_probe(&s,"reset-read-primary",ok&&!did_set&&!shutdown_req);
   do_command(&s,"{\"cmd\":\"session_reset\",\"sim\":2}",&ok,&did_set,&shutdown_req,stage,sizeof(stage));
   emit_probe(&s,"reset-read-secondary",ok&&!did_set&&!shutdown_req);
  }
 }
 /* NAS Reset (0x0000) affects only this requesting control point, per NAS IDL.
    It requires an explicit CLI flag and is separate from production recovery. */
 if(stack[0]>1&&eq(((char**)&stack[1])[1],"--nas-control-reset")){
 if(s.fd>=0)sc1(SYS_close,s.fd);s.fd=-1;s.nas_bound=0;
 if(open_nas(&s)){
  u8 r[128];u32 n;u16 result=0,error=0;
  ok=exchange(&s,0x0000,0,0,r,sizeof(r),&n)&&parse_result(r,n,&result,&error)&&!result;
  zero(&s.last_op,sizeof(s.last_op));s.last_op.result=result;s.last_op.error=error;
  emit_probe(&s,"nas-control-point-reset",ok);
  ok=bind_sim(&s,1);emit_probe(&s,"post-nas-reset-bind-primary",ok);
  if(ok){ok=query(&s);emit_probe(&s,"post-nas-reset-read-primary",ok);}
 }
 }
 if(s.fd>=0)sc1(SYS_close,s.fd);
 mon_close(&s,0);mon_close(&s,1);
 sc1(SYS_exit,0);for(;;){}
}
__asm__(".global _start\n.type _start,%function\n_start:\nmov x0,sp\nbl probe_start\nb .\n");
