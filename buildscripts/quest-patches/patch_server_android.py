#!/usr/bin/env python3
"""Make the TES3MP dedicated server compile for Android arm64 (embedded SP server).

aarch64/clang portability fixes in the scripting FFI:
1. On aarch64, va_list is a 32-byte struct (std::__va_list), not a pointer, so the
   TypeChar<> mapping hits its static_assert. Desktop x86_64 silently decays
   va_list parameters to a pointer ('p'); mirror that explicitly.
2. The Lua dispatcher templates need ScriptFunctions::functions[] to stay
   constexpr (argument types unpack at compile time), but clang refuses any
   function-pointer -> void* conversion in constant expressions (gcc allows the
   C-style cast as an extension). Split the data: the constexpr table keeps
   signatures with addr=nullptr, and a parallel runtime-initialized table
   (functionAddrs) carries the real addresses for the actual calls.

Idempotent; applied to buildscripts/openmw-vr by setup-source.sh.
"""
import os
import re
import sys

os.chdir(os.path.dirname(os.path.abspath(__file__)))

TYPES = "../openmw-vr/apps/openmw-mp/Script/Types.hpp"
FUNCS_HPP = "../openmw-vr/apps/openmw-mp/Script/ScriptFunctions.hpp"
FUNCS_CPP = "../openmw-vr/apps/openmw-mp/Script/ScriptFunctions.cpp"
LANGLUA = "../openmw-vr/apps/openmw-mp/Script/LangLua/LangLua.cpp"
LANGNATIVE = "../openmw-vr/apps/openmw-mp/Script/LangNative/LangNative.cpp"

CLANG_CTOR_NULLPTR = """#if (!defined(__clang__) && defined(__GNUC__))
    template<typename R, typename... Types>
    constexpr ScriptFunctionPointer(Function<R, Types...> addr) : ScriptIdentity(addr), addr((void*)(addr)) {}
#else
    // clang: no function-pointer -> void* conversion is constexpr-legal, and this
    // table must stay constexpr for the Lua dispatcher templates. Store nullptr
    // here; the real addresses live in ScriptFunctions::functionAddrs (runtime).
    template<typename R, typename... Types>
    constexpr ScriptFunctionPointer(Function<R, Types...> addr) : ScriptIdentity(addr), addr(nullptr) {}
#endif"""


def apply_edit(path, old, new, label):
    src = open(path, encoding="utf-8").read()
    if new in src:
        print(f"{label}: already applied")
        return
    if src.count(old) != 1:
        sys.exit(f"{label}: anchor found {src.count(old)} times (expected 1)")
    open(path, "w", encoding="utf-8", newline="\n").write(src.replace(old, new))
    print(f"{label}: applied")


# 1. va_list mapping
apply_edit(TYPES,
    """template<> struct TypeChar<void, sizeof_void<void>::value> { enum { value = 'v' }; };""",
    """template<> struct TypeChar<void, sizeof_void<void>::value> { enum { value = 'v' }; };
#ifdef __ANDROID__
// aarch64 va_list is a 32-byte struct; x86_64 decays it to a pointer ('p'). Keep parity.
template<> struct TypeChar<va_list, sizeof(va_list)> { enum { value = 'p' }; };
#endif""",
    "types: va_list")

# 2. clang ctor -> nullptr (replacing whichever earlier variant is present)
src = open(TYPES, encoding="utf-8").read()
if CLANG_CTOR_NULLPTR not in src:
    pattern = re.compile(
        r"#if \(!defined\(__clang__\) && defined\(__GNUC__\)\).*?#endif",
        re.S)
    m = pattern.search(src)
    if not m or "ScriptFunctionPointer" not in m.group(0):
        sys.exit("types: ScriptFunctionPointer ctor block not found")
    src = src[:m.start()] + CLANG_CTOR_NULLPTR + src[m.end():]
    open(TYPES, "w", encoding="utf-8", newline="\n").write(src)
    print("types: ctor nullptr variant applied")
else:
    print("types: ctor nullptr variant already applied")

# 3. runtime mirror types after ScriptFunctionData
apply_edit(TYPES,
    """struct ScriptCallbackData
{""",
    """#ifdef __clang__
// Runtime mirror of ScriptFunctionData: same initializer lists, but actually
// stores the function addresses (see ScriptFunctions::functionAddrs).
struct ScriptFunctionPointerRT
{
    void* addr;
    template<typename R, typename... Types>
    ScriptFunctionPointerRT(Function<R, Types...> a) : addr(reinterpret_cast<void*>(a)) {}
};

struct ScriptFunctionDataRT
{
    const char* name;
    const ScriptFunctionPointerRT func;

    ScriptFunctionDataRT(const char* name, ScriptFunctionPointerRT func) : name(name), func(func) {}
};
#endif

struct ScriptCallbackData
{""",
    "types: RT mirror structs")

# 4. declaration of the runtime table
apply_edit(FUNCS_HPP,
    """            OBJECTAPI,
            WORLDSTATEAPI
    };
""",
    """            OBJECTAPI,
            WORLDSTATEAPI
    };

#ifdef __clang__
    // Same entries as functions[], but with real addresses (see Types.hpp).
    static const ScriptFunctionDataRT functionAddrs[];
#endif
""",
    "funcs.hpp: functionAddrs decl")

# 5. definition of the runtime table in the .cpp (duplicate the initializer block)
src_hpp = open(FUNCS_HPP, encoding="utf-8").read()
m = re.search(r"static constexpr ScriptFunctionData functions\[\]\{(.*?)\n    \};", src_hpp, re.S)
if not m:
    sys.exit("funcs.hpp: functions[] initializer block not found")
initializer = m.group(1)

src_cpp = open(FUNCS_CPP, encoding="utf-8").read()
if "functionAddrs[]" in src_cpp:
    print("funcs.cpp: functionAddrs def already applied")
else:
    anchor = "constexpr ScriptFunctionData ScriptFunctions::functions[];"
    if anchor not in src_cpp:
        sys.exit("funcs.cpp: functions[] out-of-class definition not found")
    addition = (anchor + "\n\n#ifdef __clang__\nconst ScriptFunctionDataRT ScriptFunctions::functionAddrs[]{"
                + initializer + "\n};\n#endif")
    src_cpp = src_cpp.replace(anchor, addition)
    open(FUNCS_CPP, "w", encoding="utf-8", newline="\n").write(src_cpp)
    print("funcs.cpp: functionAddrs def applied")

# 6. LangLua runtime call site
apply_edit(LANGLUA,
    """        return reinterpret_cast<FunctionEllipsis<R>>(F_.func.addr)(std::forward<Args>(args)...);""",
    """#ifdef __clang__
        return reinterpret_cast<FunctionEllipsis<R>>(ScriptFunctions::functionAddrs[F].func.addr)(std::forward<Args>(args)...);
#else
        return reinterpret_cast<FunctionEllipsis<R>>(F_.func.addr)(std::forward<Args>(args)...);
#endif""",
    "langlua: addr lookup")

# 7. link android/log for the server executable (components drags SDL/GL along)
apply_edit("../openmw-vr/apps/openmw-mp/CMakeLists.txt",
    """    target_link_libraries(tes3mp-server dl)""",
    """    target_link_libraries(tes3mp-server dl)
    if (ANDROID)
        # the monolithic components lib drags SDL/GL4ES along; satisfy their imports
        target_link_libraries(tes3mp-server android log OpenSLES EGL GLESv1_CM GLESv2 hidapi)
    endif()""",
    "cmake: android log libs")

# 8. LangNative runtime loop
apply_edit(LANGNATIVE,
    """        for (const auto &function : ScriptFunctions::functions)
            if (!SetScript(lib, std::string(pf + function.name).c_str(), function.func.addr))""",
    """        for (const auto &function : ScriptFunctions::functions)
#ifdef __clang__
            if (!SetScript(lib, std::string(pf + function.name).c_str(),
                    ScriptFunctions::functionAddrs[&function - ScriptFunctions::functions].func.addr))
#else
            if (!SetScript(lib, std::string(pf + function.name).c_str(), function.func.addr))
#endif""",
    "langnative: addr lookup")

print("done")
