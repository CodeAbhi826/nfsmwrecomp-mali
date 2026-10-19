// nfsmw - journal of the last GPU operations (native renderer).
//
// WHY
//   When a job faults, the Mali driver declares the device lost and the runtime aborts in the presenter
//   before anything can be written. The operations themselves, however, are known the moment they are
//   recorded: this keeps the last ones in RAM (a handful of stores each) and only writes them to disk when
//   a D3D wait shows the GPU has gone quiet - the stall that always precedes the abort. The file then shows
//   what was in flight when the fault happened: draws (with their vertex ranges), texture uploads (staging
//   offset and size), resolves and copies.
//
// Files: logs/diario_N.txt next to the other logs. At most 8 per run, at least 45 s apart (see the caller
// in nfsmw_espera_anillo.cpp, only when a wait expires over and over or lasts more than 400 ms).
#pragma once

#include <rex/filesystem.h>
#include <rex/logging.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <string>
#include <type_traits>

namespace nfsmw::nativo::diario {

// A Vulkan handle as a number, for the journal. Non-dispatchable handles are pointers with the 64-bit
// pointer defines (Android, PC, NVK) and plain integers with others; this accepts both, unlike a bare
// static_cast, which the Android build rejects. The value is only an identity for the log.
template <typename T>
inline uint64_t NumeroDeMango(T mango) {
  if constexpr (std::is_pointer_v<T>) {
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(mango));
  } else {
    return static_cast<uint64_t>(mango);
  }
}

enum : uint32_t {
  kFotograma = 1,  // frame boundary: a = frame number
  kDibujo = 2,     // a = indices/vertices, b = vmin, c = first (index or vertex), d = guest address of the
                   // first vertex stream, e = its bytes
  kSubida = 3,     // vkCmdCopyBufferToImage: a = guest texture address, b = staging buffer offset,
                   // c = width << 32 | height (level 0), d = levels, e = image
  kResuelta = 4,   // vkCmdCopyImageToBuffer: a = source image, b = readback buffer, c = width << 32 | height,
                   // d = dx << 32 | dy
  kCopia = 5,      // vkCmdCopyImage / vkCmdBlitImage: a = source image, b = destination image,
                   // c = source width << 32 | height, d = destination width << 32 | height
  kEspera = 7,     // the GPU went quiet: a = how far the stall has got (iterations or ms)
};

struct Registro {
  std::atomic<uint64_t> numero{0};
  uint32_t tipo = 0;
  uint32_t relleno = 0;
  uint64_t a = 0;
  uint64_t b = 0;
  uint64_t c = 0;
  uint64_t d = 0;
  uint64_t e = 0;
};

constexpr size_t kCapacidad = 16384;

inline std::array<Registro, kCapacidad> g_registros{};
inline std::atomic<uint64_t> g_siguiente{0};
inline std::atomic<int> g_volcados{0};

// A few plain stores on the hot paths (ring thread, upload paths). Several threads write it: the counter
// hands out the slot, and the record publishes itself with the sequence number.
inline void Anotar(uint32_t tipo, uint64_t a, uint64_t b = 0, uint64_t c = 0, uint64_t d = 0,
                   uint64_t e = 0) {
  const uint64_t n = g_siguiente.fetch_add(1, std::memory_order_relaxed);
  Registro& r = g_registros[size_t(n & (kCapacidad - 1))];
  r.numero.store(0, std::memory_order_relaxed);
  r.tipo = tipo;
  r.a = a;
  r.b = b;
  r.c = c;
  r.d = d;
  r.e = e;
  r.numero.store(n + 1, std::memory_order_release);
}

// Writes the last records to logs/diario_N.txt. Called only from the stall detectors, on the thread that
// is already blocked waiting for the GPU.
inline void Volcar(const char* motivo, uint64_t detalle) {
  const int n = g_volcados.fetch_add(1, std::memory_order_relaxed) + 1;
  if (n > 8) {
    return;
  }
  const std::filesystem::path ruta =
      rex::filesystem::GetExecutableFolder() / "logs" / ("diario_" + std::to_string(n) + ".txt");
  std::error_code ec;
  std::filesystem::create_directories(ruta.parent_path(), ec);
  std::FILE* f = std::fopen(ruta.string().c_str(), "wb");
  if (f == nullptr) {
    REXLOG_WARN("[nativo] diario: no se pudo escribir {} ({})", ruta.string(), ec.message());
    return;
  }
  const uint64_t fin = g_siguiente.load(std::memory_order_acquire);
  const uint64_t desde = fin > kCapacidad ? fin - kCapacidad : 0;
  std::fprintf(f, "#diario;%s;%llu;%llu;%llu\n", motivo, static_cast<unsigned long long>(detalle),
               static_cast<unsigned long long>(fin), static_cast<unsigned long long>(fin - desde));
  size_t escritos = 0;
  for (uint64_t i = desde; i < fin; ++i) {
    const Registro& r = g_registros[size_t(i & (kCapacidad - 1))];
    if (r.numero.load(std::memory_order_acquire) != i + 1) {
      continue;  // being written right now: not that record, but the rest is what matters
    }
    std::fprintf(f, "%llu;%u;%llX;%llX;%llX;%llX;%llX\n", static_cast<unsigned long long>(i), r.tipo,
                 static_cast<unsigned long long>(r.a), static_cast<unsigned long long>(r.b),
                 static_cast<unsigned long long>(r.c), static_cast<unsigned long long>(r.d),
                 static_cast<unsigned long long>(r.e));
    ++escritos;
  }
  std::fclose(f);
  REXLOG_INFO("[nativo] diario {} volcado ({}): {} registros en {}", n, motivo, escritos, ruta.string());
}

}  // namespace nfsmw::nativo::diario
