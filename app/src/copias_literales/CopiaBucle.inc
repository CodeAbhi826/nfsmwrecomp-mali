template <class Llamadas>
[[gnu::noinline]] void CopiaBucle(PPCContext& __restrict ctx, uint8_t* base) {
  NativaBucle<Llamadas>(ctx, base);
}
