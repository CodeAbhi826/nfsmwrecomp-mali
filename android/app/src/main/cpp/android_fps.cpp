#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>

// The FPS HUD on the phone is a Java TextView (com.nfsmw.android.GameActivity.ponerContadorFps) that
// polls io.github.nfsmwrecomp.GameActivity every 500 ms. The Android core the overlay was written
// against exported those two natives; the engine built from this tree did not, so the HUD stayed at
// "-- fps". They are implemented here, over the same counter the F3 overlay uses.
//
// The counter is bumped once per Swap in the game (the hook in nfsmw_d3d_trace.cpp), so it measures
// the game's real frames and not how often the UI thread repaints: with a menu open the overlay
// repaints nonstop and an overlay-driven counter would read ~60 FPS while the game sits at 4.
extern std::atomic<uint64_t> g_nfsmw_fotogramas_juego;

namespace {

std::mutex g_cerrojo;
std::chrono::steady_clock::time_point g_ultimo;
uint64_t g_fotogramas = 0;
bool g_iniciado = false;
float g_fps = 0.0f;
float g_ms_por_fotograma = 0.0f;

// Averages over the window since the previous call. The Java side polls every 500 ms; the 250 ms guard
// keeps a caller that polls faster from measuring a window too short to mean anything.
void Muestrear() {
  using Reloj = std::chrono::steady_clock;
  const auto ahora = Reloj::now();
  const uint64_t total = g_nfsmw_fotogramas_juego.load(std::memory_order_relaxed);
  if (!g_iniciado) {
    g_iniciado = true;
    g_ultimo = ahora;
    g_fotogramas = total;
    return;
  }
  const double dt = std::chrono::duration<double>(ahora - g_ultimo).count();
  if (dt < 0.25) {
    return;
  }
  const uint64_t hechos = total - g_fotogramas;
  g_ultimo = ahora;
  g_fotogramas = total;
  g_fps = static_cast<float>(static_cast<double>(hechos) / dt);
  g_ms_por_fotograma = hechos > 0 ? static_cast<float>(dt * 1000.0 / static_cast<double>(hechos)) : 0.0f;
}

}  // namespace

extern "C" JNIEXPORT jfloat JNICALL
Java_io_github_nfsmwrecomp_GameActivity_nativeFps(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> cerrojo(g_cerrojo);
  Muestrear();
  return g_fps;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_github_nfsmwrecomp_GameActivity_nativeMsPorFotograma(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> cerrojo(g_cerrojo);
  // nativeFps() (called just before, from the same poll) updates the window and this value with it.
  return g_ms_por_fotograma;
}