template <class Llamadas>
[[gnu::noinline]] void CopiaOriginal(PPCContext& __restrict ctx, uint8_t* base) {
  Nativa<Llamadas>(ctx, base);
}
