# Links the native test programs statically. On Windows a dynamically linked test loads the first
# libstdc++-6.dll on PATH, which may belong to another program and fail with 0xC0000139.
Import("env")

env.Append(LINKFLAGS=["-static"])
