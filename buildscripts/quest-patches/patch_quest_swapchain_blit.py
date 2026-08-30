#!/usr/bin/env python3
"""Quest/gl4es interop fix: present XR swapchain frames with raw GLES3 calls.

Problem: the OpenXR runtime gives us REAL GLES texture names, but the engine renders
through gl4es, which virtualizes texture names (name -> glname). Attaching the runtime
texture via gl4es remaps it onto an unrelated internal texture (name collision), and
gl4es "implements" glBlitFramebuffer by drawing a quad from ITS OWN idea of the read
attachment. Net effect: the runtime texture is never written -> black view in the HMD.

Fix: for swapchain images only, create the destination FBO, attach the runtime texture
and do the final blit with the real driver entry points (dlsym'd from libGLESv2.so).
FBO names pass through gl4es unchanged (shared namespace), so the engine's read FBO id
can be used directly. Real bindings are saved/restored so gl4es's cached state stays
coherent.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh and by hand.
"""
import sys

CPP = "../openmw-vr/apps/openmw/mwvr/openxrswapchainimage.cpp"
HPP = "../openmw-vr/apps/openmw/mwvr/vrframebuffer.hpp"

NEW_TEMPLATE = """#ifdef XR_USE_GRAPHICS_API_OPENGL_ES
    // Quest/gl4es interop: see patch_quest_swapchain_blit.py for the full story.
    // Everything below talks to the REAL driver (dlsym'd), bypassing gl4es.
    namespace
    {
        struct RealGles
        {
            static constexpr unsigned kReadFramebuffer = 0x8CA8;
            static constexpr unsigned kDrawFramebuffer = 0x8CA9;
            static constexpr unsigned kReadFramebufferBinding = 0x8CAA;
            static constexpr unsigned kDrawFramebufferBinding = 0x8CA6;
            static constexpr unsigned kColorAttachment0 = 0x8CE0;
            static constexpr unsigned kDepthAttachment = 0x8D00;
            static constexpr unsigned kTexture2D = 0x0DE1;
            static constexpr unsigned kFramebufferComplete = 0x8CD5;
            static constexpr unsigned kColorBufferBit = 0x00004000;
            static constexpr unsigned kDepthBufferBit = 0x00000100;
            static constexpr unsigned kNearest = 0x2600;

            void (*GenFramebuffers)(int, unsigned*) = nullptr;
            void (*DeleteFramebuffers)(int, const unsigned*) = nullptr;
            void (*BindFramebuffer)(unsigned, unsigned) = nullptr;
            void (*FramebufferTexture2D)(unsigned, unsigned, unsigned, unsigned, int) = nullptr;
            void (*BlitFramebuffer)(int, int, int, int, int, int, int, int, unsigned, unsigned) = nullptr;
            unsigned (*CheckFramebufferStatus)(unsigned) = nullptr;
            void (*GetIntegerv)(unsigned, int*) = nullptr;
            unsigned (*GetError)() = nullptr;
            bool ok = false;

            static RealGles& get()
            {
                static RealGles instance;
                return instance;
            }

            RealGles()
            {
                void* handle = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
                if (!handle)
                {
                    Log(Debug::Error) << "RealGles: dlopen(libGLESv2.so) failed: " << dlerror();
                    return;
                }
                GenFramebuffers = reinterpret_cast<decltype(GenFramebuffers)>(dlsym(handle, "glGenFramebuffers"));
                DeleteFramebuffers = reinterpret_cast<decltype(DeleteFramebuffers)>(dlsym(handle, "glDeleteFramebuffers"));
                BindFramebuffer = reinterpret_cast<decltype(BindFramebuffer)>(dlsym(handle, "glBindFramebuffer"));
                FramebufferTexture2D = reinterpret_cast<decltype(FramebufferTexture2D)>(dlsym(handle, "glFramebufferTexture2D"));
                BlitFramebuffer = reinterpret_cast<decltype(BlitFramebuffer)>(dlsym(handle, "glBlitFramebuffer"));
                CheckFramebufferStatus = reinterpret_cast<decltype(CheckFramebufferStatus)>(dlsym(handle, "glCheckFramebufferStatus"));
                GetIntegerv = reinterpret_cast<decltype(GetIntegerv)>(dlsym(handle, "glGetIntegerv"));
                GetError = reinterpret_cast<decltype(GetError)>(dlsym(handle, "glGetError"));
                ok = GenFramebuffers && DeleteFramebuffers && BindFramebuffer && FramebufferTexture2D
                    && BlitFramebuffer && CheckFramebufferStatus && GetIntegerv && GetError;
                if (!ok)
                    Log(Debug::Error) << "RealGles: missing GLES3 entry points (glBlitFramebuffer requires ES3)";
            }
        };
    }

    template<>
    class OpenXRSwapchainImageTemplate< XrSwapchainImageOpenGLESKHR > : public OpenXRSwapchainImage
    {
    public:
        static constexpr XrStructureType XrType = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;

    public:
        OpenXRSwapchainImageTemplate(osg::GraphicsContext* gc, XrSwapchainCreateInfo swapchainCreateInfo, const XrSwapchainImageOpenGLESKHR& xrImage)
            : OpenXRSwapchainImage()
            , mXrImage(xrImage)
            , mWidth(static_cast<int>(swapchainCreateInfo.width))
            , mHeight(static_cast<int>(swapchainCreateInfo.height))
            , mIsDepth((swapchainCreateInfo.usageFlags & XR_SWAPCHAIN_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0)
        {
            auto& gles = RealGles::get();
            if (!gles.ok)
                return;

            int prevDraw = 0;
            gles.GetIntegerv(RealGles::kDrawFramebufferBinding, &prevDraw);
            gles.GenFramebuffers(1, &mRawFbo);
            gles.BindFramebuffer(RealGles::kDrawFramebuffer, mRawFbo);
            gles.FramebufferTexture2D(RealGles::kDrawFramebuffer,
                mIsDepth ? RealGles::kDepthAttachment : RealGles::kColorAttachment0,
                RealGles::kTexture2D, mXrImage.image, 0);
            const unsigned status = gles.CheckFramebufferStatus(RealGles::kDrawFramebuffer);
            const unsigned err = gles.GetError();
            gles.BindFramebuffer(RealGles::kDrawFramebuffer, static_cast<unsigned>(prevDraw));
            Log(Debug::Verbose) << "RealGles swapchain image: tex=" << mXrImage.image
                                << (mIsDepth ? " depth" : " color")
                                << " rawFbo=" << mRawFbo
                                << " status=0x" << std::hex << status
                                << " err=0x" << err << std::dec;
            if (status != RealGles::kFramebufferComplete)
                Log(Debug::Error) << "RealGles swapchain image: framebuffer incomplete, status=0x"
                                  << std::hex << status << std::dec;
        }

        ~OpenXRSwapchainImageTemplate() override
        {
            auto& gles = RealGles::get();
            if (gles.ok && mRawFbo)
                gles.DeleteFramebuffers(1, &mRawFbo);
        }

        void blit(osg::GraphicsContext* gc, VRFramebuffer& readBuffer, int offset_x, int offset_y) override
        {
            auto& gles = RealGles::get();
            if (!gles.ok || !mRawFbo)
                return;

            int prevRead = 0, prevDraw = 0;
            gles.GetIntegerv(RealGles::kReadFramebufferBinding, &prevRead);
            gles.GetIntegerv(RealGles::kDrawFramebufferBinding, &prevDraw);

            // gl4es passes framebuffer names straight through to the driver, so the
            // engine-side FBO id is valid for raw GLES too.
            gles.BindFramebuffer(RealGles::kReadFramebuffer, readBuffer.fbo());
            gles.BindFramebuffer(RealGles::kDrawFramebuffer, mRawFbo);
            gles.BlitFramebuffer(offset_x, offset_y, offset_x + mWidth, offset_y + mHeight,
                0, 0, mWidth, mHeight,
                mIsDepth ? RealGles::kDepthBufferBit : RealGles::kColorBufferBit,
                RealGles::kNearest);

            static int diagCount = 0;
            if (diagCount < 8)
            {
                ++diagCount;
                const unsigned err = gles.GetError();
                Log(Debug::Error) << "RealGles blit#" << diagCount
                                  << (mIsDepth ? " depth" : " color")
                                  << " srcFbo=" << readBuffer.fbo() << " dstFbo=" << mRawFbo
                                  << " " << mWidth << "x" << mHeight
                                  << " off=" << offset_x << "," << offset_y
                                  << " err=0x" << std::hex << err << std::dec;
            }

            gles.BindFramebuffer(RealGles::kReadFramebuffer, static_cast<unsigned>(prevRead));
            gles.BindFramebuffer(RealGles::kDrawFramebuffer, static_cast<unsigned>(prevDraw));
        }

        XrSwapchainImageOpenGLESKHR mXrImage;
        int mWidth;
        int mHeight;
        bool mIsDepth;
        unsigned mRawFbo = 0;
    };
#endif // XR_USE_GRAPHICS_API_OPENGL_ES"""


def patch_cpp():
    src = open(CPP, encoding="utf-8").read()
    if "RealGles" in src:
        print("cpp: already patched")
        return

    # 1. dlfcn include
    old_inc = ("#elif defined(__ANDROID__)\n"
               "// Android uses EGL/JNI; no X11/GLX headers needed\n"
               "#include <jni.h>\n"
               "#include <EGL/egl.h>\n")
    new_inc = ("#elif defined(__ANDROID__)\n"
               "// Android uses EGL/JNI; no X11/GLX headers needed\n"
               "#include <jni.h>\n"
               "#include <EGL/egl.h>\n"
               "#include <dlfcn.h>\n")
    if src.count(old_inc) != 1:
        sys.exit("cpp: include anchor not found")
    src = src.replace(old_inc, new_inc)

    # 2. Replace the whole GLES template block (first ifdef..matching endif comment)
    start = src.find("#ifdef XR_USE_GRAPHICS_API_OPENGL_ES")
    end_marker = "#endif // XR_USE_GRAPHICS_API_OPENGL_ES"
    end = src.find(end_marker, start)
    if start == -1 or end == -1:
        sys.exit("cpp: GLES template block not found")
    end += len(end_marker)
    src = src[:start] + NEW_TEMPLATE + src[end:]

    open(CPP, "w", encoding="utf-8", newline="\n").write(src)
    print("cpp: patched")


def patch_hpp():
    src = open(HPP, encoding="utf-8").read()
    if "uint32_t fbo() const" in src:
        print("hpp: already patched")
        return
    anchor = "        uint32_t depthBuffer() const { return mDepthBuffer.mImage; };"
    if src.count(anchor) != 1:
        sys.exit("hpp: getter anchor not found")
    src = src.replace(anchor, anchor + "\n        uint32_t fbo() const { return mFBO; };")
    open(HPP, "w", encoding="utf-8", newline="\n").write(src)
    print("hpp: patched")


VIEWER = "../openmw-vr/apps/openmw/mwvr/vrviewer.cpp"

OLD_VSOURCE = ('        static const char* vSource = "#version 120\\n varying vec2 uv; '
               'void main(){ gl_Position = vec4(gl_Vertex.xy*2.0 - 1, 0, 1); uv = gl_Vertex.xy;}";')
NEW_VSOURCE = ('        // ESSL (gl4es) compatibility: no implicit int->float, no f-suffix\n'
               '        static const char* vSource = "#version 120\\n varying vec2 uv; '
               'void main(){ gl_Position = vec4(gl_Vertex.xy*2.0 - vec2(1.0), 0.0, 1.0); uv = gl_Vertex.xy;}";')

OLD_FLINE = '"rgb = (rgb - 0.5f) * contrast + 0.5f;"'
NEW_FLINE = '"rgb = (rgb - vec3(0.5)) * contrast + vec3(0.5);"'


def patch_gamma_shader():
    src = open(VIEWER, encoding="utf-8").read()
    if "vec2(1.0), 0.0, 1.0" in src:
        print("vrviewer: already patched")
        return
    if src.count(OLD_VSOURCE) != 1 or src.count(OLD_FLINE) != 1:
        sys.exit("vrviewer: gamma shader anchors not found")
    src = src.replace(OLD_VSOURCE, NEW_VSOURCE).replace(OLD_FLINE, NEW_FLINE)
    open(VIEWER, "w", encoding="utf-8", newline="\n").write(src)
    print("vrviewer: gamma shader patched for ESSL")


if __name__ == "__main__":
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    patch_cpp()
    patch_hpp()
    patch_gamma_shader()
    print("done")
