/*
 * Stub for AOSP's per-binary "generated_rust_staticlib" (librustlibs.a), whose only
 * C-ABI export used by libunwindstack/dex2oat is rustc_demangle() (demangling Rust
 * symbols in native backtraces). Building the real thing needs the AOSP Rust
 * toolchain; without it, Rust frames simply stay mangled.
 *
 * Contract (rustc-demangle-capi): returns nonzero on success, 0 on failure.
 */
int rustc_demangle(const char* mangled, char* out, unsigned long out_size) {
  (void)mangled;
  (void)out;
  (void)out_size;
  return 0;
}
