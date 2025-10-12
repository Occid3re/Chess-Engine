# Linux x86_64 Syzygy Native Library

Place the `libJSyzygy.so` binary from the [syzygy-bridge](https://github.com/ljgw/syzygy-bridge) releases in this directory before building a distributable artifact.

The JNI loader extracts this file at runtime, so the engine expects it to be named exactly `libJSyzygy.so`.
