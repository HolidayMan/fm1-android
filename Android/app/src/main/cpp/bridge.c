#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include "engine_api.h"
#define JNI(name) Java_org_fm1_android_NativeEngine_##name
#define H ((void*)(uintptr_t)handle)
static void fail(JNIEnv* e,const char* message){jclass c=(*e)->FindClass(e,"java/lang/IllegalStateException");(*e)->ThrowNew(e,c,message);}
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv* e,jclass c,jbyteArray data,jint required){
 (void)c;jsize size=(*e)->GetArrayLength(e,data);jbyte* bytes=(*e)->GetByteArrayElements(e,data,NULL);
 uint32_t kind=instrument_native_kind_for_fwsc((uint8_t*)bytes,size);void* m=NULL;uint8_t error[512]={0};
 if(kind==(uint32_t)required&&kind>=2&&kind<=9)m=instrument_create((uint8_t*)bytes,size,error,sizeof(error));
 (*e)->ReleaseByteArrayElements(e,data,bytes,JNI_ABORT);
 if(!m){fail(e,error[0]?(char*)error:"Exact compiled native instrument is unavailable.");return 0;}
 if(instrument_native_kind(m)!=(uint32_t)required){instrument_destroy(m);fail(e,"Native instrument identity mismatch.");return 0;}
 return (jlong)(uintptr_t)m;
}
JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*e,jclass c,jlong handle){instrument_destroy(H);}
JNIEXPORT jint JNICALL JNI(kind)(JNIEnv*e,jclass c,jlong handle){return instrument_native_kind(H);}
JNIEXPORT jint JNICALL JNI(render)(JNIEnv*e,jclass c,jlong handle,jfloatArray pcm,jint frames){if(frames<1||frames>2048||(*e)->GetArrayLength(e,pcm)<frames*2)return 0;jfloat* out=(*e)->GetFloatArrayElements(e,pcm,NULL);int n=instrument_render(H,out,frames);(*e)->ReleaseFloatArrayElements(e,pcm,out,0);return n;}
JNIEXPORT void JNICALL JNI(key)(JNIEnv*e,jclass c,jlong handle,jint id,jboolean down){instrument_key(H,id,down);}
JNIEXPORT void JNICALL JNI(release)(JNIEnv*e,jclass c,jlong handle){instrument_release_all(H);}
JNIEXPORT void JNICALL JNI(encoder)(JNIEnv*e,jclass c,jlong handle,jint id,jint delta){instrument_encoder(H,id,delta);}
JNIEXPORT void JNICALL JNI(volume)(JNIEnv*e,jclass c,jlong handle,jint value){instrument_volume(H,value);}
JNIEXPORT jint JNICALL JNI(snapshot)(JNIEnv*e,jclass c,jlong handle,jintArray pixels,jfloatArray leds){
 if((*e)->GetArrayLength(e,pixels)!=57600||(*e)->GetArrayLength(e,leds)!=41)return 0;
 jint* p=(*e)->GetIntArrayElements(e,pixels,NULL);jfloat* l=(*e)->GetFloatArrayElements(e,leds,NULL);int result=instrument_snapshot(H,(uint32_t*)p,l);
 for(int i=0;i<57600;i++){uint32_t v=p[i];p[i]=(v&0xff00ff00)|((v&255)<<16)|((v>>16)&255);}
 (*e)->ReleaseIntArrayElements(e,pixels,p,0);(*e)->ReleaseFloatArrayElements(e,leds,l,0);return result;
}
JNIEXPORT jbyteArray JNICALL JNI(flash)(JNIEnv*e,jclass c,jlong handle){size_t n=instrument_flash(H,NULL,0);if(n>1048576||!n)return NULL;jbyteArray result=(*e)->NewByteArray(e,n);jbyte* p=(*e)->GetByteArrayElements(e,result,NULL);instrument_flash(H,(uint8_t*)p,n);(*e)->ReleaseByteArrayElements(e,result,p,0);return result;}
JNIEXPORT jboolean JNICALL JNI(restore)(JNIEnv*e,jclass c,jlong handle,jbyteArray data){jsize n=(*e)->GetArrayLength(e,data);jbyte* p=(*e)->GetByteArrayElements(e,data,NULL);bool result=instrument_restore(H,(uint8_t*)p,n);(*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);return result;}
JNIEXPORT jbyteArray JNICALL JNI(readPart)(JNIEnv*e,jclass c,jlong handle,jint type,jint id){uint8_t* buffer=malloc(81920);int n=type==0?instrument_sloop_object_read(H,id,buffer,65536):type==1?instrument_sloop_arrangement_read(H,buffer,43):instrument_sloop_sample_read(H,id,buffer,81920);if(n<0||n>81920){free(buffer);fail(e,"Could not capture SLOOP component.");return NULL;}jbyteArray result=(*e)->NewByteArray(e,n);(*e)->SetByteArrayRegion(e,result,0,n,(jbyte*)buffer);free(buffer);return result;}
JNIEXPORT jint JNICALL JNI(writePart)(JNIEnv*e,jclass c,jlong handle,jint type,jint id,jbyteArray data){int n=(*e)->GetArrayLength(e,data);jbyte* p=(*e)->GetByteArrayElements(e,data,NULL);int result=type==0?instrument_sloop_object_write(H,id,(uint8_t*)p,n):type==1?instrument_sloop_arrangement_write(H,(uint8_t*)p,n):instrument_sloop_sample_write(H,id,(uint8_t*)p,n);(*e)->ReleaseByteArrayElements(e,data,p,JNI_ABORT);return result;}
JNIEXPORT jboolean JNICALL JNI(transport)(JNIEnv*e,jclass c,jlong handle,jboolean playing){return instrument_sloop_transport(H,playing);}
JNIEXPORT jint JNICALL JNI(commit)(JNIEnv*e,jclass c,jlong handle){return instrument_sloop_commit(H);}
