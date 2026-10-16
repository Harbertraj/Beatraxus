"""
Headless OpenGL ES 3.x runner for the FPS Booster shader harness.

Creates a surfaceless EGL context (Mesa llvmpipe in CI / sandbox, any ES 3.0+ driver elsewhere) and
exposes just enough of GLES to run full-screen fragment-shader passes exactly as the Android renderer
does: attribute-less triangle strip driven by gl_VertexID, textures as inputs, FBO textures as outputs.

This runs the *real* GLSL used by the app, so it validates shader compilation and numerical behaviour.
It says nothing about speed on a phone GPU (llvmpipe is a CPU rasteriser).
"""
from __future__ import annotations

import ctypes
from ctypes import POINTER, byref, c_char_p, c_float, c_int, c_uint, c_void_p

import numpy as np

# ---- GL constants -----------------------------------------------------------------------------
GL_TEXTURE_2D = 0x0DE1
GL_RGBA8 = 0x8058
GL_R8 = 0x8229
GL_RG8 = 0x822B
GL_R16F = 0x822D
GL_RGBA16F = 0x881A
GL_RGBA32F = 0x8814
GL_RGBA = 0x1908
GL_RED = 0x1903
GL_RG = 0x8227
GL_UNSIGNED_BYTE = 0x1401
GL_FLOAT = 0x1406
GL_FRAMEBUFFER = 0x8D40
GL_COLOR_ATTACHMENT0 = 0x8CE0
GL_FRAMEBUFFER_COMPLETE = 0x8CD5
GL_TEXTURE_MIN_FILTER = 0x2801
GL_TEXTURE_MAG_FILTER = 0x2800
GL_TEXTURE_WRAP_S = 0x2802
GL_TEXTURE_WRAP_T = 0x2803
GL_LINEAR = 0x2601
GL_NEAREST = 0x2600
GL_CLAMP_TO_EDGE = 0x812F
GL_VERTEX_SHADER = 0x8B31
GL_FRAGMENT_SHADER = 0x8B30
GL_COMPILE_STATUS = 0x8B81
GL_LINK_STATUS = 0x8B82
GL_INFO_LOG_LENGTH = 0x8B84
GL_TRIANGLE_STRIP = 0x0005
GL_TEXTURE0 = 0x84C0
GL_VERSION = 0x1F02
GL_RENDERER = 0x1F01
GL_EXTENSIONS = 0x1F03
GL_NUM_EXTENSIONS = 0x821D

VERTEX = """#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""


class ShaderError(RuntimeError):
    pass


class GL:
    """Singleton-style wrapper around a surfaceless EGL/GLES3 context."""

    def __init__(self) -> None:
        self.egl = ctypes.CDLL("libEGL.so.1")
        self.gl = ctypes.CDLL("libGLESv2.so.2")
        egl = self.egl
        egl.eglGetProcAddress.restype = c_void_p
        egl.eglGetProcAddress.argtypes = [c_char_p]
        get_plat = ctypes.CFUNCTYPE(c_void_p, c_int, c_void_p, c_void_p)(
            egl.eglGetProcAddress(b"eglGetPlatformDisplayEXT"))
        self.dpy = get_plat(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
        if not self.dpy:
            raise RuntimeError("no surfaceless EGL display")
        maj, mn = c_int(), c_int()
        egl.eglInitialize.argtypes = [c_void_p, POINTER(c_int), POINTER(c_int)]
        if not egl.eglInitialize(c_void_p(self.dpy), byref(maj), byref(mn)):
            raise RuntimeError("eglInitialize failed")
        egl.eglBindAPI(0x30A0)  # EGL_OPENGL_ES_API
        attribs = (c_int * 5)(0x3040, 0x40, 0x3033, 0x1, 0x3038)
        cfg, n = c_void_p(), c_int()
        egl.eglChooseConfig.argtypes = [c_void_p, POINTER(c_int), POINTER(c_void_p), c_int, POINTER(c_int)]
        if not egl.eglChooseConfig(c_void_p(self.dpy), attribs, byref(cfg), 1, byref(n)) or n.value < 1:
            raise RuntimeError("no ES3 config")
        egl.eglCreateContext.restype = c_void_p
        egl.eglCreateContext.argtypes = [c_void_p, c_void_p, c_void_p, POINTER(c_int)]
        ctx = egl.eglCreateContext(c_void_p(self.dpy), cfg, None, (c_int * 3)(0x3098, 3, 0x3038))
        if not ctx:
            raise RuntimeError("eglCreateContext failed")
        egl.eglMakeCurrent.argtypes = [c_void_p, c_void_p, c_void_p, c_void_p]
        if not egl.eglMakeCurrent(c_void_p(self.dpy), None, None, c_void_p(ctx)):
            raise RuntimeError("eglMakeCurrent failed")
        self.ctx = ctx
        g = self.gl
        g.glGetString.restype = c_char_p
        g.glGetStringi.restype = c_char_p
        g.glCreateShader.restype = c_uint
        g.glCreateProgram.restype = c_uint
        g.glGetUniformLocation.restype = c_int
        self.version = g.glGetString(c_uint(GL_VERSION)).decode()
        self.renderer = g.glGetString(c_uint(GL_RENDERER)).decode()
        n_ext = c_int()
        g.glGetIntegerv(c_uint(GL_NUM_EXTENSIONS), byref(n_ext))
        self.extensions = {g.glGetStringi(c_uint(GL_EXTENSIONS), c_uint(i)).decode() for i in range(n_ext.value)}
        self.fbo = self._gen(g.glGenFramebuffers)
        self._programs: dict[tuple[str, str], "Program"] = {}

    # ---- helpers -------------------------------------------------------------------------
    def _gen(self, fn) -> int:
        v = c_uint()
        fn(1, byref(v))
        return v.value

    def texture(self, w: int, h: int, fmt: int = GL_RGBA8, filt: int = GL_LINEAR) -> int:
        g = self.gl
        t = self._gen(g.glGenTextures)
        g.glBindTexture(GL_TEXTURE_2D, t)
        g.glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h)
        for p, v in ((GL_TEXTURE_MIN_FILTER, filt), (GL_TEXTURE_MAG_FILTER, filt),
                     (GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE), (GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)):
            g.glTexParameteri(GL_TEXTURE_2D, p, v)
        return t

    def set_filter(self, tex: int, filt: int) -> None:
        g = self.gl
        g.glBindTexture(GL_TEXTURE_2D, tex)
        g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filt)
        g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filt)

    def upload_rgba8(self, tex: int, img: np.ndarray) -> None:
        """img: HxWx3/4 uint8 (top row first). Stored bottom-up like a GL texture with uv.y = 0 at row 0."""
        h, w = img.shape[:2]
        if img.shape[2] == 3:
            img = np.concatenate([img, np.full((h, w, 1), 255, np.uint8)], axis=2)
        arr = np.ascontiguousarray(img, dtype=np.uint8)
        self.gl.glBindTexture(GL_TEXTURE_2D, tex)
        self.gl.glPixelStorei(0x0CF5, 1)  # UNPACK_ALIGNMENT
        self.gl.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, arr.ctypes.data_as(c_void_p))

    def delete_texture(self, tex: int) -> None:
        self.gl.glDeleteTextures(1, byref(c_uint(tex)))

    def bind_target(self, tex: int, w: int, h: int) -> None:
        g = self.gl
        g.glBindFramebuffer(GL_FRAMEBUFFER, self.fbo)
        g.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        status = g.glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if status != GL_FRAMEBUFFER_COMPLETE:
            raise RuntimeError(f"framebuffer incomplete 0x{status:x} ({w}x{h})")
        g.glViewport(0, 0, w, h)

    def draw(self) -> None:
        self.gl.glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

    def read_rgba8(self, tex: int, w: int, h: int) -> np.ndarray:
        self.bind_target(tex, w, h)
        buf = np.empty((h, w, 4), np.uint8)
        self.gl.glPixelStorei(0x0D05, 1)  # PACK_ALIGNMENT
        self.gl.glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf.ctypes.data_as(c_void_p))
        return buf

    def read_rgba32f(self, tex: int, w: int, h: int) -> np.ndarray:
        """Reads a float/half-float colour attachment as float32 RGBA."""
        self.bind_target(tex, w, h)
        buf = np.empty((h, w, 4), np.float32)
        self.gl.glPixelStorei(0x0D05, 1)
        self.gl.glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, buf.ctypes.data_as(c_void_p))
        return buf

    def finish(self) -> None:
        self.gl.glFinish()

    def program(self, fragment: str, vertex: str = VERTEX) -> "Program":
        key = (vertex, fragment)
        if key not in self._programs:
            self._programs[key] = Program(self, vertex, fragment)
        return self._programs[key]


class Program:
    def __init__(self, gl: GL, vertex: str, fragment: str) -> None:
        self.gl = gl
        g = gl.gl
        vs = self._compile(GL_VERTEX_SHADER, vertex)
        fs = self._compile(GL_FRAGMENT_SHADER, fragment)
        self.id = g.glCreateProgram()
        g.glAttachShader(self.id, vs)
        g.glAttachShader(self.id, fs)
        g.glLinkProgram(self.id)
        ok = c_int()
        g.glGetProgramiv(self.id, GL_LINK_STATUS, byref(ok))
        if not ok.value:
            raise ShaderError("link: " + self._log(g.glGetProgramiv, g.glGetProgramInfoLog, self.id))
        g.glDeleteShader(vs)
        g.glDeleteShader(fs)
        self._loc: dict[str, int] = {}

    def _log(self, getiv, getlog, obj) -> str:
        n = c_int()
        getiv(obj, GL_INFO_LOG_LENGTH, byref(n))
        buf = ctypes.create_string_buffer(max(n.value, 1))
        getlog(obj, n.value, None, buf)
        return buf.value.decode(errors="replace")

    def _compile(self, kind: int, src: str) -> int:
        g = self.gl.gl
        s = g.glCreateShader(kind)
        b = src.encode()
        arr = (c_char_p * 1)(b)
        g.glShaderSource(s, 1, arr, None)
        g.glCompileShader(s)
        ok = c_int()
        g.glGetShaderiv(s, GL_COMPILE_STATUS, byref(ok))
        if not ok.value:
            raise ShaderError("compile: " + self._log(g.glGetShaderiv, g.glGetShaderInfoLog, s))
        return s

    def use(self) -> "Program":
        self.gl.gl.glUseProgram(self.id)
        return self

    def loc(self, name: str) -> int:
        if name not in self._loc:
            self._loc[name] = self.gl.gl.glGetUniformLocation(self.id, name.encode())
        return self._loc[name]

    def tex(self, unit: int, name: str, tex: int) -> "Program":
        g = self.gl.gl
        g.glActiveTexture(GL_TEXTURE0 + unit)
        g.glBindTexture(GL_TEXTURE_2D, tex)
        g.glUniform1i(self.loc(name), unit)
        return self

    def i1(self, name, v): self.gl.gl.glUniform1i(self.loc(name), int(v)); return self
    def i2(self, name, a, b): self.gl.gl.glUniform2i(self.loc(name), int(a), int(b)); return self
    def f1(self, name, v): self.gl.gl.glUniform1f(self.loc(name), c_float(v)); return self
    def f2(self, name, a, b): self.gl.gl.glUniform2f(self.loc(name), c_float(a), c_float(b)); return self
    def f3(self, name, a, b, c): self.gl.gl.glUniform3f(self.loc(name), c_float(a), c_float(b), c_float(c)); return self
    def f4(self, name, a, b, c, d): self.gl.gl.glUniform4f(self.loc(name), c_float(a), c_float(b), c_float(c), c_float(d)); return self


_instance: GL | None = None


def get_gl() -> GL:
    global _instance
    if _instance is None:
        _instance = GL()
    return _instance
