# Embedded instrumentation runtime

Frida Gadget 17.26.2: https://github.com/frida/frida/releases/tag/17.26.2

Frida Java bridge 7.0.13: https://github.com/frida/frida-java-bridge

These components are distributed under LGPL-2.0 WITH WxWindows-exception-3.1. Their
upstream source, license and exception are available from the linked projects.
The separately embedded shared library can be replaced by rebuilding with a new
payload lock. The Java bridge is bundled from its npm source with npm ci.

esbuild is a build-time dependency under the MIT license.

The bundled Java bridge uses buffer 6.0.3, base64-js and ieee754, distributed
under the MIT license. License copies accompany the runtime payload.
