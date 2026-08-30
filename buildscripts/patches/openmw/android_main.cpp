int stderr = 0; // Hack: fix linker error

#include "SDL_main.h"
#include "engine.hpp"
#include "mwbase/windowmanager.hpp"
#include <SDL_gamecontroller.h>
#include <SDL_mouse.h>
#include <SDL_events.h>
#include <SDL_hints.h>

#include <osg/GraphicsContext>
#include <osg/OperationThread>

/*******************************************************************************
 Functions called by JNI
 *******************************************************************************/
#include <jni.h>

/* Called before  to initialize JNI bindings  */

extern void SDL_Android_Init(JNIEnv* env, jclass cls);
extern int argcData;
extern const char **argvData;
void releaseArgv();


extern "C" int Java_org_libsdl_app_SDLActivity_getMouseX(JNIEnv *env, jclass cls, jobject obj) {
    int ret = 0;
    SDL_GetMouseState(&ret, nullptr);
    return ret;
}


extern "C" int Java_org_libsdl_app_SDLActivity_getMouseY(JNIEnv *env, jclass cls, jobject obj) {
    int ret = 0;
    SDL_GetMouseState(nullptr, &ret);
    return ret;
}

extern "C" int Java_org_libsdl_app_SDLActivity_isMouseShown(JNIEnv *env, jclass cls, jobject obj) {
    return SDL_ShowCursor(SDL_QUERY);
}

extern SDL_Window *Android_Window;
extern "C" int SDL_SendMouseMotion(SDL_Window * window, int mouseID, int relative, int x, int y);
extern "C" void Java_org_libsdl_app_SDLActivity_sendRelativeMouseMotion(JNIEnv *env, jclass cls, int x, int y) {
    SDL_SendMouseMotion(Android_Window, 0, 1, x, y);
}

extern "C" int SDL_SendMouseButton(SDL_Window * window, int mouseID, Uint8 state, Uint8 button);
extern "C" void Java_org_libsdl_app_SDLActivity_sendMouseButton(JNIEnv *env, jclass cls, int state, int button) {
    SDL_SendMouseButton(Android_Window, 0, state, button);
}

extern "C" int Java_org_libsdl_app_SDLActivity_nativeInit(JNIEnv* env, jclass cls, jobject obj) {
    setenv("OPENMW_DECOMPRESS_TEXTURES", "1", 1);

    // On Android, we use a virtual controller with guid="Virtual"
    SDL_GameControllerAddMapping("5669727475616c000000000000000000,Virtual,a:b0,b:b1,back:b15,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1,guide:b16,leftshoulder:b6,leftstick:b13,lefttrigger:a5,leftx:a0,lefty:a1,rightshoulder:b7,rightstick:b14,righttrigger:a4,rightx:a2,righty:a3,start:b11,x:b3,y:b4");

    // Meta Quest Touch controllers (vendor 0x2833, product 0x0160).
    // Quest merges both controllers into a single Android InputDevice.
    // After axis deduplication the layout is:
    //   a0=leftx  a1=lefty  a2=rightx  a3=righty
    //   a4=lefttrigger  a5=righttrigger  a6=brake(=lt dup)  a7=gas(=rt dup)
    // GUID: bus=0x05(BT) vendor=0x2833 product=0x0160 btnmask=0x7fff axismask=0x003f
    SDL_GameControllerAddMapping("050000003328000060010000ff7f3f00,Meta Quest Touch Controller,a:b0,b:b1,x:b2,y:b3,back:b4,guide:b5,start:b6,leftstick:b7,rightstick:b8,leftshoulder:b9,rightshoulder:b10,dpup:b11,dpdown:b12,dpleft:b13,dpright:b14,leftx:a0,lefty:a1,rightx:a2,righty:a3,lefttrigger:a4,righttrigger:a5");

    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");

    return 0;
}

extern osg::ref_ptr<osgViewer::Viewer> g_viewer;
static osg::GraphicsContext *ctx;

class CtxReleaseOperation : public osg::Operation {
public:
    virtual void operator () (osg::Object* caller) {
        ctx->releaseContext();
    }
};

class CtxAcquireOperation : public osg::Operation {
public:
    virtual void operator () (osg::Object* caller) {
        ctx->makeCurrent();
    }
};

extern "C" void Java_org_libsdl_app_SDLActivity_omwSurfaceDestroyed(JNIEnv *env, jclass cls, jobject obj) {
    if (!g_viewer)
        return;

    // The Android 2D surface can be destroyed while the engine is still
    // starting up (e.g. during the Quest immersive transition), before the
    // viewer camera has a graphics context. Nothing to release in that case.
    osg::GraphicsContext *gc = g_viewer->getCamera() ? g_viewer->getCamera()->getGraphicsContext() : nullptr;
    if (gc)
    {
        osg::ref_ptr<CtxReleaseOperation> op = new CtxReleaseOperation();
        ctx = gc;
        gc->add(op);
    }

    auto win = (MWBase::WindowManager *)MWBase::Environment::get().getWindowManager();
    if (win)
        win->windowVisibilityChange(false);
}

extern "C" void Java_org_libsdl_app_SDLActivity_omwSurfaceRecreated(JNIEnv *env, jclass cls, jobject obj) {
    if (!g_viewer)
        return;

    osg::GraphicsContext *gc = g_viewer->getCamera() ? g_viewer->getCamera()->getGraphicsContext() : nullptr;
    if (gc)
    {
        osg::ref_ptr<CtxAcquireOperation> op = new CtxAcquireOperation();
        ctx = gc;
        gc->add(op);
    }

    auto win = (MWBase::WindowManager *)MWBase::Environment::get().getWindowManager();
    if (win)
        win->windowVisibilityChange(true);
}
