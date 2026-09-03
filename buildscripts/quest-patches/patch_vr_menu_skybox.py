#!/usr/bin/env python3
"""360-degree sky sphere behind VR menus and loading screens.

Instead of floating in a black void, the main menu and loading screens are
surrounded by an equirectangular panorama. The texture is loaded through the
VFS ("[VR] menu sky texture", default textures/vr_menu_sky.png), which the
launcher provides via an extra data directory at /sdcard/tes3mpvr/vrdata —
users can drop their own panorama there.

The sphere lives in the VRGUIManager scene root, is drawn on the inside
(front-face culling), unlit, and only becomes visible while a MainMenu or
LoadingScreen GUI layer exists.

Applies to both engine trees. Idempotent, CRLF-tolerant.
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = [os.path.join(ROOT, "openmw-vr"), os.path.join(ROOT, "openmw-vr-sp")]

MARKER = "vr_menu_skybox"

HPP_ANCHOR = """        void insertWidget(MWGui::Layout* widget);
        void removeWidget(MWGui::Layout* widget);
        void setFocusLayer(VRGUILayer* layer);"""

HPP_NEW = """        void insertWidget(MWGui::Layout* widget);
        void removeWidget(MWGui::Layout* widget);
        void updateSkySphere(); // vr_menu_skybox
        void setFocusLayer(VRGUILayer* layer);"""

def read_norm(p):
    return open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

def write_lf(p, src):
    open(p, "w", encoding="utf-8", newline="\n").write(src)

CTOR_ANCHOR = """        mGeometries->setName("VR GUI Geometry Root");"""

CTOR_NEW = """        mGeometries->setName("VR GUI Geometry Root");
        createSkySphere(); // vr_menu_skybox"""

# free function + member impl appended near insertLayer
IMPL_ANCHOR = """    void VRGUIManager::insertLayer(const std::string& name)"""

IMPL_NEW = """    void VRGUIManager::createSkySphere()
    {
        // vr_menu_skybox: equirect panorama sphere shown behind menu/loading
        std::string texPath;
        try { texPath = Settings::Manager::getString("menu sky texture", "VR"); }
        catch (...) { /* key absent from defaults - use the shipped panorama */ }
        if (texPath.empty())
            texPath = "textures/vr_menu_sky.png";
        osg::ref_ptr<osg::Image> image;
        try
        {
            if (mResourceSystem->getVFS()->exists(texPath))
                image = mResourceSystem->getImageManager()->getImage(texPath);
        }
        catch (const std::exception& e)
        {
            Log(Debug::Warning) << "VR menu skybox: " << e.what();
        }
        if (!image)
        {
            Log(Debug::Verbose) << "VR menu skybox: no texture at " << texPath << ", skipping";
            return;
        }

        osg::ref_ptr<osg::Sphere> shape = new osg::Sphere(osg::Vec3(0.f, 0.f, 0.f), 5000.f);
        osg::ref_ptr<osg::ShapeDrawable> drawable = new osg::ShapeDrawable(shape);
        osg::ref_ptr<osg::Texture2D> tex = new osg::Texture2D(image);
        tex->setWrap(osg::Texture::WRAP_S, osg::Texture::REPEAT);
        tex->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);
        tex->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
        tex->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);

        osg::StateSet* state = drawable->getOrCreateStateSet();
        state->setTextureAttributeAndModes(0, tex, osg::StateAttribute::ON);
        state->setMode(GL_LIGHTING, osg::StateAttribute::OFF | osg::StateAttribute::PROTECTED);
        state->setMode(GL_DEPTH_TEST, osg::StateAttribute::OFF);
        state->setAttributeAndModes(new osg::CullFace(osg::CullFace::FRONT), osg::StateAttribute::ON);
        state->setRenderBinDetails(-100, "RenderBin"); // draw first, behind everything
        state->setAttributeAndModes(new osg::Depth(osg::Depth::LEQUAL, 0.0, 1.0, false));

        mSkySphere = new osg::PositionAttitudeTransform();
        // rotate so the panorama seam sits behind the player and the horizon is level
        mSkySphere->setAttitude(osg::Quat(osg::PI_2, osg::Vec3(1, 0, 0)));
        osg::ref_ptr<osg::Geode> geode = new osg::Geode();
        geode->addDrawable(drawable);
        mSkySphere->addChild(geode);
        mSkySphere->setNodeMask(0);
        mSkySphere->setName("VR Menu Sky");
        mGeometriesRootNode->addChild(mSkySphere);
    }

    void VRGUIManager::updateSkySphere()
    {
        if (!mSkySphere)
            return;
        bool show = mLayers.count("MainMenu") > 0 || mLayers.count("LoadingScreen") > 0;
        mSkySphere->setNodeMask(show ? MWRender::VisMask::Mask_3DGUI : 0);
    }

    void VRGUIManager::insertLayer(const std::string& name)"""

HPP_MEMBER_ANCHOR = """        osg::ref_ptr<osg::Group> mGeometriesRootNode{ nullptr };
        osg::ref_ptr<osg::Group> mGeometries{ new osg::Group };"""
HPP_MEMBER_NEW = """        osg::ref_ptr<osg::Group> mGeometriesRootNode{ nullptr };
        osg::ref_ptr<osg::Group> mGeometries{ new osg::Group };
        osg::ref_ptr<osg::PositionAttitudeTransform> mSkySphere; // vr_menu_skybox
        void createSkySphere();"""

def patch_tree(tree):
    cpp = os.path.join(tree, "apps", "openmw", "mwvr", "vrgui.cpp")
    hpp = os.path.join(tree, "apps", "openmw", "mwvr", "vrgui.hpp")

    src = read_norm(hpp)
    if MARKER not in src:
        ok = True
        for a, n in ((HPP_ANCHOR, HPP_NEW), (HPP_MEMBER_ANCHOR, HPP_MEMBER_NEW)):
            if src.count(a) != 1:
                print(f"{hpp}: anchor not found:\n{a}")
                ok = False
        if not ok:
            sys.exit(1)
        src = src.replace(HPP_ANCHOR, HPP_NEW, 1).replace(HPP_MEMBER_ANCHOR, HPP_MEMBER_NEW, 1)
        write_lf(hpp, src)
        print(f"{hpp}: patched")
    else:
        print(f"{hpp}: already patched")

    src = read_norm(cpp)
    if MARKER in src:
        print(f"{cpp}: already patched")
        return
    for a in (CTOR_ANCHOR, IMPL_ANCHOR):
        if src.count(a) != 1:
            sys.exit(f"{cpp}: anchor missing or ambiguous: {a[:60]}")
    src = src.replace(CTOR_ANCHOR, CTOR_NEW, 1).replace(IMPL_ANCHOR, IMPL_NEW, 1)

    # hook updateSkySphere into layer bookkeeping
    src = src.replace("        mLayers[name] = layer;",
                      "        mLayers[name] = layer;\n        updateSkySphere();", 1)
    anchor_rm = "    void VRGUIManager::removeLayer(const std::string& name)"
    idx = src.find(anchor_rm)
    if idx == -1:
        sys.exit(f"{cpp}: removeLayer not found")
    end = src.find("\n    }", idx)
    src = src[:end] + "\n        updateSkySphere();" + src[end:]

    # includes
    inc_anchor = "#include <osg/Depth>"
    if inc_anchor not in src:
        sys.exit(f"{cpp}: include anchor missing")
    src = src.replace(inc_anchor,
        "#include <osg/Depth>\n#include <osg/CullFace>\n#include <osg/ShapeDrawable>\n#include <osg/PositionAttitudeTransform>\n#include <components/vfs/manager.hpp>\n#include <components/resource/imagemanager.hpp>\n#include <components/debug/debuglog.hpp>", 1)
    write_lf(cpp, src)
    print(f"{cpp}: patched")

for t in TREES:
    if os.path.isdir(t):
        patch_tree(t)
print("done")
