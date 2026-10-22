// nfsmw - frame rhythm and thermal telemetry, the "[ritmo]" line and the FPS HUD's second line.
//
// The existing 10 s reports answer "how fast": swaps per second, the worst frame and where its time
// went. They do not answer "how evenly", which is what the eye judges. A PC at 20 fps on a 60 Hz
// output presents every third vblank: 50.0, 50.0, 50.0 ms. On a phone the same average can arrive as
// 33.3, 66.7, 33.3, 66.7: the counter says 20 fps and the image still stutters. This line buckets
// every interval by its nearest vblank multiple and reports the two most common, so a 3v/4v
// alternation is visible at a glance and does not need a profiler.
//
// It also samples the thermal zones and the CPU frequencies once per window. The A20 reaches 76-79 C
// in about two minutes of attract screen; from there the governor moves the big cores and the rhythm
// changes mid-session, which is the other reason a session starts smooth and ends choppy.
//
// Cost: one clock read and one mutex per frame (uncontended), sixteen sysfs reads per window.

#include "nfsmw_ritmo.h"

#include <rex/cvar.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

#include <fmt/format.h>

#include "nfsmw_informe_diferido.h"

namespace nfsmw::ritmo {
namespace {

using Reloj = std::chrono::steady_clock;

// Ten seconds at 60 fps is 600 samples; 2048 covers a fast menu for a longer window.
constexpr size_t kMaxMuestras = 2048;
constexpr size_t kMinimoMuestras = 10;

REXCVAR_DEFINE_INT32(nfsmw_ritmo_cada_s, 10, "NFSMW",
                     "Cada cuantos segundos se escribe la linea [ritmo]: reparto de intervalos entre "
                     "fotogramas, cadencia, temperaturas y frecuencias (1-60; 1 para una prueba fina)")
    .range(1, 60);
REXCVAR_DEFINE_INT32(nfsmw_pantalla_hz, 60, "NFSMW",
                     "Frecuencia del modo de pantalla en uso. El launcher pasa la real; [ritmo] agrupa "
                     "cada intervalo por su multiplo de vblank mas cercano")
    .range(30, 240);
REXCVAR_DEFINE_BOOL(nfsmw_ritmo_informe, true, "NFSMW",
                    "Escribir la linea [ritmo] (el HUD sigue funcionando con esto apagado)");

struct Estado {
  std::mutex cerrojo;
  std::array<uint64_t, kMaxMuestras> intervalo_ns{};
  size_t usadas = 0;
  size_t siguiente = 0;
  Reloj::time_point ultimo_swap{};
  bool hay_swap = false;
  Reloj::time_point ultimo_informe{};
  bool hay_informe = false;

  // Last complete window, read by the HUD through JNI every 500 ms.
  int fps = 0;
  int p95 = 0;
  int maximo = 0;
  int tirones = 0;
  int v1 = 0, p1 = 0;
  int v2 = 0, p2 = 0;
  int soc = 0;
  int bateria = 0;
  int grande_mhz = 0;
  int grande_max_mhz = 0;
  int pequena_mhz = 0;
};

Estado g;

bool LeerNumero(const char* ruta, long long* valor) {
  std::ifstream fichero(ruta);
  if (!fichero) {
    return false;
  }
  long long v = 0;
  if (!(fichero >> v)) {
    return false;
  }
  *valor = v;
  return true;
}

// thermal_zone*/temp is millidegrees on most kernels, tenths on some and plain degrees on a few.
long long Miligrados(long long v) {
  if (v > 2000) {
    return v;  // 61000 = 61.0 C
  }
  if (v > 200) {
    return v * 100;  // 610 = 61.0 C in tenths
  }
  return v * 1000;  // 61 = 61 C
}

void LeerTermico(int& soc, int& bateria) {
  long long peor = -273000;
  for (int z = 0; z < 16; ++z) {
    char ruta[80];
    std::snprintf(ruta, sizeof(ruta), "/sys/class/thermal/thermal_zone%d/temp", z);
    long long v = 0;
    if (!LeerNumero(ruta, &v)) {
      continue;
    }
    const long long mc = Miligrados(v);
    // Some kernels leave a zone at 0 or at a sentinel; ignore anything outside a plausible die range.
    if (mc < -40000 || mc > 150000) {
      continue;
    }
    peor = std::max(peor, mc);
  }
  soc = peor > -273000 ? int((peor + 500) / 1000) : 0;

  long long t = 0;
  bateria = LeerNumero("/sys/class/power_supply/battery/temp", &t) ? int((Miligrados(t) + 500) / 1000) : 0;
}

void LeerFrecuencias(int& grande, int& grande_max, int& pequena) {
  long long khz = 0;
  grande = LeerNumero("/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq", &khz) ? int((khz + 500) / 1000) : 0;
  grande_max = LeerNumero("/sys/devices/system/cpu/cpu6/cpufreq/cpuinfo_max_freq", &khz) ? int((khz + 500) / 1000) : 0;
  pequena = LeerNumero("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", &khz) ? int((khz + 500) / 1000) : 0;
}

struct Ventana {
  double media_ms = 0.0;
  double p50_ms = 0.0;
  double p95_ms = 0.0;
  double max_ms = 0.0;
  int v1 = 0, p1 = 0;
  int v2 = 0, p2 = 0;
  std::string cadencia;
  int otros_pct = 0;
  int cambios_pct = 0;
  int tirones = 0;
};

std::string Etiqueta(int k) {
  return k >= 8 ? std::string("8v+") : fmt::format("{}v", k);
}

Ventana Analizar(const std::vector<uint64_t>& intervalos) {
  Ventana w;
  const size_t n = intervalos.size();
  std::vector<uint64_t> ordenados = intervalos;
  std::sort(ordenados.begin(), ordenados.end());

  const uint64_t p50_ns = ordenados[n / 2];
  const uint64_t p95_ns = ordenados[size_t(double(n) * 0.95)];
  const uint64_t max_ns = ordenados.back();
  uint64_t total_ns = 0;
  for (uint64_t v : intervalos) {
    total_ns += v;
  }

  const int hz = std::max(30, std::min(240, int(REXCVAR_GET(nfsmw_pantalla_hz))));
  const double vbl_ns = 1e9 / double(hz);

  std::array<int, 9> cubos{};  // 1..8 vblanks, 8 means "8 or more"
  for (uint64_t v : intervalos) {
    int k = int(std::llround(double(v) / vbl_ns));
    k = std::clamp(k, 1, 8);
    ++cubos[k];
  }
  std::array<int, 8> orden_cubos{1, 2, 3, 4, 5, 6, 7, 8};
  std::sort(orden_cubos.begin(), orden_cubos.end(), [&](int a, int b) { return cubos[a] > cubos[b]; });
  const auto pct = [&](int k) { return int(std::lround(100.0 * double(cubos[k]) / double(n))); };
  w.v1 = orden_cubos[0];
  w.p1 = pct(w.v1);
  w.v2 = orden_cubos[1];
  w.p2 = pct(w.v2);

  // Only the three most common buckets, and only those that actually happened.
  int suma = 0;
  int impresos = 0;
  for (int k : orden_cubos) {
    if (impresos >= 3) {
      break;
    }
    const int p = pct(k);
    if (p == 0) {
      continue;
    }
    w.cadencia += (w.cadencia.empty() ? "" : " ") + fmt::format("{} {}%", Etiqueta(k), p);
    suma += p;
    ++impresos;
  }
  if (w.cadencia.empty()) {
    w.cadencia = "sin datos";
  }
  w.otros_pct = std::max(0, 100 - suma);

  // A rhythm change is a jump of at least half a vblank between consecutive frames: 3v/4v
  // alternation scores 100 %, a steady 50.0 ms scores 0 %.
  int cambios = 0;
  for (size_t i = 1; i < n; ++i) {
    const uint64_t a = intervalos[i - 1];
    const uint64_t b = intervalos[i];
    const uint64_t d = a > b ? a - b : b - a;
    if (double(d) > vbl_ns * 0.5) {
      ++cambios;
    }
  }
  w.cambios_pct = n > 1 ? int(std::lround(100.0 * double(cambios) / double(n - 1))) : 0;

  // A stutter is a frame that lost at least three quarters of a vblank against the median: at
  // 20 fps that catches the 4v frames (66.7 ms) and leaves the 3v ones (50.0 ms) alone.
  for (uint64_t v : intervalos) {
    if (double(v) > double(p50_ns) + vbl_ns * 0.75) {
      ++w.tirones;
    }
  }

  w.media_ms = double(total_ns) / double(n) / 1e6;
  w.p50_ms = double(p50_ns) / 1e6;
  w.p95_ms = double(p95_ns) / 1e6;
  w.max_ms = double(max_ns) / 1e6;
  return w;
}

// Called with g.cerrojo held.
void Informar(const std::vector<uint64_t>& intervalos, double segundos) {
  const Ventana w = Analizar(intervalos);
  LeerTermico(g.soc, g.bateria);
  LeerFrecuencias(g.grande_mhz, g.grande_max_mhz, g.pequena_mhz);

  g.fps = int(std::lround(double(intervalos.size()) / segundos));
  g.p95 = int(std::lround(w.p95_ms));
  g.maximo = int(std::lround(w.max_ms));
  g.tirones = w.tirones;
  g.v1 = w.v1;
  g.p1 = w.p1;
  g.v2 = w.v2;
  g.p2 = w.p2;

  if (!REXCVAR_GET(nfsmw_ritmo_informe)) {
    return;
  }
  const int hz = std::max(30, std::min(240, int(REXCVAR_GET(nfsmw_pantalla_hz))));
  NFSMW_INFORME_DIFERIDO(
      "[ritmo] {:.1f} s: {} fps, {:.1f} ms (p50 {:.1f}, p95 {:.1f}, max {:.1f}); vblank {:.2f} ms ({} Hz): "
      "{} (otros {}%); cambios de ritmo {}%; tirones {}; soc {} C, bateria {} C; "
      "A73 {}/{} MHz, A53 {} MHz",
      segundos, g.fps, w.media_ms, w.p50_ms, w.p95_ms, w.max_ms, 1000.0 / double(hz), hz,
      w.cadencia, w.otros_pct, w.cambios_pct, w.tirones, g.soc, g.bateria,
      g.grande_mhz, g.grande_max_mhz, g.pequena_mhz);
}

}  // namespace

void RegistrarSwap() {
  const auto ahora = Reloj::now();
  std::lock_guard<std::mutex> cerrojo(g.cerrojo);

  if (g.hay_swap) {
    const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(ahora - g.ultimo_swap).count();
    g.intervalo_ns[g.siguiente] = uint64_t(ns);
    g.siguiente = (g.siguiente + 1) % kMaxMuestras;
    if (g.usadas < kMaxMuestras) {
      ++g.usadas;
    }
  }
  g.ultimo_swap = ahora;
  g.hay_swap = true;

  if (!g.hay_informe) {
    g.hay_informe = true;
    g.ultimo_informe = ahora;
    return;
  }

  const double segundos = std::chrono::duration<double>(ahora - g.ultimo_informe).count();
  const double cada_s = double(std::max(1, std::min(60, int(REXCVAR_GET(nfsmw_ritmo_cada_s)))));
  if (segundos < cada_s) {
    return;
  }

  std::vector<uint64_t> intervalos;
  intervalos.reserve(g.usadas);
  const size_t inicio = (g.siguiente + kMaxMuestras - g.usadas) % kMaxMuestras;
  for (size_t i = 0; i < g.usadas; ++i) {
    intervalos.push_back(g.intervalo_ns[(inicio + i) % kMaxMuestras]);
  }
  g.usadas = 0;
  g.siguiente = 0;
  g.ultimo_informe = ahora;

  if (intervalos.size() >= kMinimoMuestras) {
    Informar(intervalos, segundos);
  }
}

}  // namespace nfsmw::ritmo

extern "C" const char* nfsmw_ritmo_hud_line() {
  using namespace nfsmw::ritmo;
  static thread_local char linea[160];
  std::lock_guard<std::mutex> cerrojo(g.cerrojo);
  if (g.fps <= 0) {
    linea[0] = '\0';
    return linea;
  }
  char e1[8], e2[8], temp[16], freq[16];
  if (g.v1 >= 8) {
    std::snprintf(e1, sizeof(e1), "8v+");
  } else {
    std::snprintf(e1, sizeof(e1), "%dv", g.v1);
  }
  if (g.v2 >= 8) {
    std::snprintf(e2, sizeof(e2), "8v+");
  } else {
    std::snprintf(e2, sizeof(e2), "%dv", g.v2);
  }
  if (g.soc > 0) {
    std::snprintf(temp, sizeof(temp), "%dC", g.soc);
  } else {
    std::snprintf(temp, sizeof(temp), "--C");
  }
  if (g.grande_mhz > 0) {
    std::snprintf(freq, sizeof(freq), "%.1fG", double(g.grande_mhz) / 1000.0);
  } else {
    std::snprintf(freq, sizeof(freq), "--G");
  }
  std::snprintf(linea, sizeof(linea), "p95 %d  max %d  %s%d %s%d  %s %s  st%d",
                g.p95, g.maximo, e1, g.p1, e2, g.p2, temp, freq, g.tirones);
  return linea;
}
