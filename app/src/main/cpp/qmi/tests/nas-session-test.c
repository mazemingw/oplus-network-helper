/* Executes the actual daemon session code with fake QRTR syscalls, no radio access. */
#define NH_QMI_TEST
#include "../nh-qmi.c"

static int next_fd,socket_calls,bind_calls,closed_old,closed_before_bind;
static int reject_bind,reject_error,reject_first_only,fail_discovery,fail_connect,timeout_first_bind,closed_monitors;
static s64 receive_fd;static int receive_lookup;static u16 request_tx,request_message;
static int last_subscription;
static u8 cell_request[512];static u32 cell_request_len;static s64 cell_fd;

static void reset_mock(void){
 next_fd=200;socket_calls=bind_calls=closed_old=closed_before_bind=0;
 reject_bind=reject_first_only=fail_discovery=fail_connect=timeout_first_bind=closed_monitors=0;reject_error=7;
 receive_lookup=0;last_subscription=-1;
 cell_request_len=0;cell_fd=-1;
}
static void setup(struct state*s,int bound){zero(s,sizeof(*s));s->fd=100;s->sim=1;s->nas_bound=bound;s->node=7;s->port=42;s->mon_fd[0]=-1;s->mon_fd[1]=-1;}

static s64 sc1(s64 n,s64 a){if(n==SYS_close&&a==100)closed_old++;if(n==SYS_close&&(a==101||a==102))closed_monitors++;return 0;}
static s64 sc2(s64 n,s64 a,s64 b){
 if(n==SYS_clock_gettime){struct timespec64*t=(void*)b;t->sec=1;t->nsec=bind_calls+100;}
 return 0;
}
static s64 sc3(s64 n,s64 a,s64 b,s64 c){
 if(n==SYS_socket){socket_calls++;return next_fd++;}
 if(n==SYS_connect)return fail_connect?-13:0;
 return 0;
}
static s64 sc4(s64 n,s64 a,s64 b,s64 c,s64 d){return 0;}
static s64 sc5(s64 n,s64 a,s64 b,s64 c,s64 d,s64 e){return 0;}
static s64 sc6(s64 n,s64 a,s64 b,s64 c,s64 d,s64 e,s64 f){
 if(n==SYS_sendto){
  receive_fd=a;receive_lookup=(c==sizeof(struct qrtr_ctrl_pkt));
  if(!receive_lookup){
   const u8*p=(void*)b;request_tx=le16(p+1);request_message=le16(p+3);
   if(request_message==MSG_NR_CELL_LOCK_SET){cell_fd=a;cell_request_len=(u32)c;copy(cell_request,p,(u32)c);}
   if(request_message==MSG_BIND){bind_calls++;last_subscription=p[10];closed_before_bind=closed_old;}
  }
  return c;
 }
 if(n==SYS_recvfrom){
  if(receive_lookup){
   struct qrtr_ctrl_pkt*r=(void*)b;if(fail_discovery)return -11;
   zero(r,sizeof(*r));r->command=QRTR_TYPE_NEW_SERVER;r->service=NAS_SERVICE;
   r->instance=NAS_PACKED_INSTANCE;r->node=7;r->port=42;return sizeof(*r);
  }
  if(timeout_first_bind&&bind_calls==1)return -11;
  {int reject=reject_bind&&(!reject_first_only||bind_calls==1);
   u8*r=(void*)b;r[0]=2;put16(r+1,request_tx);put16(r+3,request_message);put16(r+5,7);
   r[7]=2;put16(r+8,4);put16(r+10,reject?1:0);put16(r+12,reject?reject_error:0);return 14;}
 }
 return 0;
}

int nh_test_run(void){
 struct state s;char response[65536];
 reset_mock();setup(&s,0);
 if(!reopen_bound(&s,1)||socket_calls||closed_old||bind_calls!=1||last_subscription!=0||!s.nas_bound)return 1;

 reset_mock();setup(&s,1);
 if(!reopen_bound(&s,1)||socket_calls||bind_calls||closed_old)return 2;

 reset_mock();setup(&s,1);
 if(!reopen_bound(&s,2)||s.sim!=2||s.fd==100||closed_old!=1||closed_before_bind||last_subscription!=1)return 3;

 reset_mock();setup(&s,1);reject_bind=1;
 if(reopen_bound(&s,2)||s.fd!=100||s.sim!=1||!s.nas_bound||closed_old||s.bind_failure_stage!=2||s.last_op.error!=7)return 4;
 build_response(&s,"{\"cmd\":\"sim_set\"}",0,"sim_bind",response,sizeof(response));
 if(!json_find(response,"code")||!json_find(response,"stage"))return 4;

 reset_mock();setup(&s,1);fail_discovery=1;
 if(reopen_bound(&s,2)||s.fd!=100||closed_old||s.bind_failure_stage!=1||bind_calls)return 5;

 reset_mock();setup(&s,0);timeout_first_bind=1;
 if(!reopen_bound(&s,2)||bind_calls!=2||last_subscription!=1||closed_old!=1||closed_before_bind)return 6;

 reset_mock();setup(&s,1);fail_connect=1;
 if(reopen_bound(&s,2)||s.fd!=100||closed_old||s.bind_failure_stage!=1||!eq(s.status,"NAS socket connection failed (errno 13)"))return 7;

 reset_mock();setup(&s,0);reject_bind=1;reject_error=5;reject_first_only=1;
 if(!reopen_bound(&s,2)||bind_calls!=2||last_subscription!=1||closed_old!=1||s.fd==100||s.sim!=2)return 8;

 reset_mock();setup(&s,1);reject_bind=1;reject_error=5;
 if(reopen_bound(&s,2)||bind_calls!=2||closed_old!=1||s.fd!=-1||s.nas_bound||s.last_op.error!=5)return 9;

 reset_mock();setup(&s,1);s.mon_fd[0]=101;s.mon_fd[1]=102;
 if(!reset_nas_session(&s,2)||closed_old!=1||closed_monitors!=2||bind_calls!=1||last_subscription!=1||s.mon_fd[0]!=-1||s.mon_fd[1]!=-1)return 10;

 reset_mock();setup(&s,1);
 if(serve_client(&s,500)!=1)return 11; /* App EOF must terminate the daemon, not keep clients for minutes. */

 /* Real NR multi-PCI SET uses the selected NAS socket, message 0x010e, and full uint16 PCIs. */
 reset_mock();setup(&s,1);
 {u32 pcis[2]={1005,293};
  if(!reopen_bound(&s,2)||!cmd_nr_cell_lock_multi_pci_set(&s,504990,1,41,pcis,2))return 12;
  if(last_subscription!=1||s.node!=7||s.port!=42||cell_fd!=s.fd||cell_fd==100)return 12;
  if(cell_request_len!=92||le16(cell_request+3)!=0x010e||cell_request[14]!=TLV_NRCELL_SET_MULTI||
     cell_request[17]!=2||le16(cell_request+18)!=1005||le16(cell_request+20)!=293||
     le16(cell_request+22)!=2||le32(cell_request+24)!=504990||cell_request[33]!=1)return 12;
 }
 reset_mock();setup(&s,1);
 {u32 pcis[2]={0,1007};
  if(!cmd_nr_cell_lock_multi_pci_set(&s,504990,1,41,pcis,2)||le16(cell_request+18)!=0||le16(cell_request+20)!=1007)return 13;
 }
 reset_mock();setup(&s,1);
 {u32 pcis[64],i;for(i=0;i<64;i++)pcis[i]=944+i;
  if(!cmd_nr_cell_lock_multi_pci_set(&s,504990,1,41,pcis,64)||cell_request_len!=216||cell_request[17]!=64||
     le16(cell_request+18)!=944||le16(cell_request+144)!=1007)return 14;
 }
 if(nas_read_allowed(0x0034)!=0||nas_read_allowed(0x00d7)!=0||nas_read_allowed(0x010f)!=0||
    nas_read_allowed(0x010e)!=-1||NAS_READ_MAX<10)return 15;
 reset_mock();setup(&s,1);
 {int ok,did_set,shutdown_req;char stage[64];
  do_command(&s,"{\"cmd\":\"nr_cell_lock_multi_pci_set\",\"arfcn\":504990,\"scs_khz\":30,\"band\":41,\"pci_list\":[0,1007]}",
             &ok,&did_set,&shutdown_req,stage,sizeof(stage));
  if(!ok||shutdown_req||cell_request_len!=92||le32(cell_request+10)!=NRCELL_TYPE_MULTI||
     le16(cell_request+18)!=0||le16(cell_request+20)!=1007)return 16;
 }
 reset_mock();setup(&s,1);
 {int ok,did_set,shutdown_req;char stage[64];
  do_command(&s,"{\"cmd\":\"nr_cell_lock_multi_pci_set\",\"arfcn\":504990,\"scs_khz\":30,\"band\":41,\"pci_list\":[1008]}",
             &ok,&did_set,&shutdown_req,stage,sizeof(stage));
  if(ok||cell_request_len||!eq(stage,"bad_request"))return 17;
 }
 return 0;
}
