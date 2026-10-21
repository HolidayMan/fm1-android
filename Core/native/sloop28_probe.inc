/* Local-only SLOOP upgrade QA; include after sloop_core.c and stress_fixture.inc.
 * Input must be a disposable synthetic flash; never supply owner data. */
#include <stdio.h>
#include <stdlib.h>
static void probe_write(const char *dir,const char *name,const void *p,size_t n) {
 char path[4096];snprintf(path,sizeof path,"%s/%s",dir,name);
 FILE *f=fopen(path,"wb");if(!f||fwrite(p,1,n,f)!=n)exit(20);fclose(f);
}
int main(int argc,char **argv) {
 if(argc!=5)return 1;
 FILE*f=fopen(argv[1],"rb");if(!f||fread(host_flash,1,HOST_FLASH_SIZE,f)!=HOST_FLASH_SIZE)return 2;fclose(f);
 slp_init();
 if(atoi(argv[3]) && fm1_stress_fixture_prepare(0))return 3;
 unsigned char object[65536];char name[64];
 for(unsigned i=0;i<12u;i++) {
   int n=slp_object_get(i,object,sizeof object);
   if(n>=0){snprintf(name,sizeof name,"object-%u.bin",i);probe_write(argv[2],name,object,n);}
 }
 slp_arr_state(object,sizeof object);probe_write(argv[2],"arrangement.bin",object,11+2*object[10]);
 probe_write(argv[2],"initial.flash",host_flash,HOST_FLASH_SIZE);
#ifdef PROBE_SLOOP28
 /* Direct firmware UI handlers: note pitch/length and FILTER RESO edit plus LCD draw. */
 song.sel=0;ui.cursor=0;ui.note_k=0;ui.sheet=0;
 unsigned oldpitch=trk[0].step[0].note[0];step_edit(1,1);
 if(trk[0].step[0].note[0]!=oldpitch+1)return 4;
 mi_set(MI_FRESO,1);if(flt_reso!=1)return 5;
 ui.force=1;ui_draw();unsigned lit=0;for(unsigned i=0;i<240*240;i++)lit+=host_screen[i]!=0;if(!lit)return 6;
 /* Boot must map the new persisted setting back into its global, as upstream main.c does. */
 fxfade=7;settings_save();song.g[G_FXFADE]=0;slp_init();
 if(song.g[G_FXFADE]!=7)return 8;
 /* Reset to synthetic input before measuring to avoid modifying migration comparison. */
 slp_flash_load(host_flash);
#endif
 slp_transport(1);int32_t pcm[512*2];unsigned maxvoices=0,maxdrums=0,maxgrains=0;unsigned long long nonzero=0,frames=0;
 unsigned seconds=(unsigned)atoi(argv[4]);
 for(;frames<(unsigned long long)FS*seconds;frames+=512) {
  slp_render(pcm,512);for(unsigned i=0;i<1024;i++)nonzero+=pcm[i]!=0;
  uint32_t m[13];fm1_stress_fixture_observe(m,13);unsigned voices=m[1]+m[2]+m[3];
  if(voices>maxvoices)maxvoices=voices;if(m[4]>maxdrums)maxdrums=m[4];if(m[5]>maxgrains)maxgrains=m[5];
 }
 printf("version=%s frames=%llu nonzero=%llu voices=%u drums=%u grains=%u\n",slp_version(),frames,nonzero,maxvoices,maxdrums,maxgrains);
 if(seconds&&(!nonzero||maxvoices<8||!maxdrums||!maxgrains))return 7;
 return 0;
}
