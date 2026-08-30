#!/usr/bin/env python3
"""Patch openxrplatform.cpp: add Android OpenXR loader init (xrInitializeLoaderKHR)
and XrInstanceCreateInfoAndroidKHR chaining, required for Meta Quest 2."""
import os
import sys

os.chdir(os.path.dirname(os.path.abspath(__file__)))
PATH = "../openmw-vr/apps/openmw/mwvr/openxrplatform.cpp"

with open(PATH, "r", encoding="utf-8") as f:
    src = f.read()

if "xrInitializeLoaderKHR" in src:
    print("Already patched, nothing to do.")
    sys.exit(0)

replacements = []

# 1. Include SDL_system.h for SDL_AndroidGetJNIEnv/SDL_AndroidGetActivity
old = """#elif defined(__ANDROID__)
// Android uses EGL/JNI; no X11/GLX headers needed
#include <jni.h>
#include <EGL/egl.h>
"""
new = """#elif defined(__ANDROID__)
// Android uses EGL/JNI; no X11/GLX headers needed
#include <jni.h>
#include <EGL/egl.h>
#include <SDL_system.h>
"""
replacements.append((old, new))

# 2. Loader init helper + call at start of OpenXRPlatform ctor
old = """    OpenXRPlatform::OpenXRPlatform(osg::GraphicsContext* gc)
        : mPrivate(new OpenXRPlatformPrivate(gc))
    {
        // Enumerate layers and their extensions.
"""
new = """#ifdef __ANDROID__
    static JavaVM* sAndroidVM = nullptr;
    static jobject sAndroidActivity = nullptr;

    // On Android the OpenXR loader must be initialized with the app's JavaVM and
    // activity before any other OpenXR call, otherwise runtime discovery fails.
    static void initXrLoaderAndroid()
    {
        static bool initialized = false;
        if (initialized)
            return;
        initialized = true;

        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        if (!env)
        {
            Log(Debug::Error) << "OpenXR loader init: SDL_AndroidGetJNIEnv() returned null";
            return;
        }
        env->GetJavaVM(&sAndroidVM);
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (activity)
        {
            sAndroidActivity = env->NewGlobalRef(activity);
            env->DeleteLocalRef(activity);
        }

        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        XrResult res = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
            reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader));
        if (XR_SUCCEEDED(res) && initializeLoader != nullptr)
        {
            XrLoaderInitInfoAndroidKHR loaderInitInfo{ XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR };
            loaderInitInfo.applicationVM = sAndroidVM;
            loaderInitInfo.applicationContext = sAndroidActivity;
            res = initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInitInfo));
            Log(Debug::Verbose) << "xrInitializeLoaderKHR result: " << XrResultString(res);
        }
        else
        {
            Log(Debug::Error) << "xrInitializeLoaderKHR unavailable: " << XrResultString(res);
        }
    }
#endif

    OpenXRPlatform::OpenXRPlatform(osg::GraphicsContext* gc)
        : mPrivate(new OpenXRPlatformPrivate(gc))
    {
#ifdef __ANDROID__
        initXrLoaderAndroid();
#endif
        // Enumerate layers and their extensions.
"""
replacements.append((old, new))

# 3. Enable XR_KHR_android_create_instance in setupExtensions
old = """        selectGraphicsAPIExtension();

        Log(Debug::Verbose) << "Using extensions:";
"""
new = """        selectGraphicsAPIExtension();

        Log(Debug::Verbose) << "Using extensions:";

#ifdef __ANDROID__
        // Android runtimes (Meta Quest) require the VM/activity at instance creation
        enableExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, true);
#endif
"""
replacements.append((old, new))

# 4. Chain XrInstanceCreateInfoAndroidKHR into xrCreateInstance
old = """        strcpy(createInfo.applicationInfo.applicationName, "openmw_vr");
        createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

        auto res = CHECK_XRCMD(xrCreateInstance(&createInfo, &instance));
"""
new = """        strcpy(createInfo.applicationInfo.applicationName, "openmw_vr");
        createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

#ifdef __ANDROID__
        XrInstanceCreateInfoAndroidKHR androidCreateInfo{ XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR };
        if (extensionEnabled(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) && sAndroidVM && sAndroidActivity)
        {
            androidCreateInfo.applicationVM = sAndroidVM;
            androidCreateInfo.applicationActivity = sAndroidActivity;
            createInfo.next = &androidCreateInfo;
        }
#endif

        auto res = CHECK_XRCMD(xrCreateInstance(&createInfo, &instance));
"""
replacements.append((old, new))

for i, (old, new) in enumerate(replacements, 1):
    count = src.count(old)
    if count != 1:
        print(f"ERROR: replacement #{i} anchor found {count} times (expected 1)")
        sys.exit(1)
    src = src.replace(old, new)
    print(f"OK: replacement #{i} applied")

with open(PATH, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)

print("Patched successfully:", PATH)
