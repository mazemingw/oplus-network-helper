/* GPL-3.0: read-only NAS neighbor/LTE CA/NR serving-frequency diagnostic.
 * Uses the bundled helper's transport. No preference or cell-lock setters. */
#define NH_QMI_NO_ENTRY
#include "../app/src/main/cpp/qmi/nh-qmi.c"

void radio_probe_start(u64*stack){
 struct state s;int sim;zero(&s,sizeof(s));s.fd=-1;s.sim=1;s.mon_fd[0]=-1;s.mon_fd[1]=-1;
 for(sim=1;sim<=2;sim++){
  const char*req=sim==1?"{\"cmd\":\"nas_read\",\"sim\":1,\"reads\":[\"0043\",\"00ac\",\"00d2\"]}":
   "{\"cmd\":\"nas_read\",\"sim\":2,\"reads\":[\"0043\",\"00ac\",\"00d2\"]}";
  int ok,did_set,shutdown_req;char stage[16];static char response[65536];
  do_command(&s,req,&ok,&did_set,&shutdown_req,stage,sizeof(stage));
  build_response(&s,req,ok,stage,response,sizeof(response));
  sc3(SYS_write,1,(s64)response,slen(response));sc3(SYS_write,1,(s64)"\n",1);
 }
 mon_close(&s,0);mon_close(&s,1);
 sc1(SYS_exit,0);for(;;){}
}
__asm__(".global _start\n.type _start,%function\n_start:\nmov x0,sp\nbl radio_probe_start\nb .\n");
