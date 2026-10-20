// On-device conversion benchmark: how long the BC -> host-format transcode takes on this phone.
//
// It exists because the numbers that matter cannot be guessed. The renderer today decodes BC on the CPU
// and uploads RGBA8; ETC2 would add an encode on top and cut VRAM and bus by 4x; ASTC would ask for a
// real encoder (astcenc) and a block size choice. This measures, with the same code the renderer uses and
// the same compiler flags, the milliseconds each of those costs per megabyte of source RGBA.
//
// Nothing here touches Vulkan or the log, so it can be run on PC (see the host harness).
#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <span>
#include <string>
#include <utility>
#include <vector>

#include "nfsmw_texturas_astc.h"
#include "nfsmw_texturas_bc.h"
#include "nfsmw_texturas_etc2.h"

namespace nfsmw::medir {

struct Resultado {
  std::string nombre;
  double ms = 0;     // per image, averaged over the repetitions
  double mb_s = 0;   // MB of source RGBA per second
  std::string nota;
};

inline double Milisegundos(std::chrono::steady_clock::time_point inicio) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - inicio).count();
}

// A deterministic image with the mix a game texture has: soft gradients, fine alternating detail that
// forces the encoders to actually look at modes, hard-edged UI-like rectangles, a little noise, and an
// alpha channel that is mostly opaque with both a smooth ramp and a hard cutout (BC1 punchthrough).
inline void ImagenDePrueba(std::vector<uint8_t>& rgba, uint32_t ancho, uint32_t alto) {
  rgba.resize(size_t(ancho) * alto * 4);
  uint32_t estado = 0x9E3779B9u;
  const auto siguiente = [&estado]() {
    estado ^= estado << 13;
    estado ^= estado >> 17;
    estado ^= estado << 5;
    return estado;
  };
  for (uint32_t y = 0; y < alto; ++y) {
    for (uint32_t x = 0; x < ancho; ++x) {
      uint8_t* p = &rgba[(size_t(y) * ancho + x) * 4];
      // Soft base: two ramps plus a low-frequency ripple built from large blocks.
      int r = 36 + int((uint64_t(x) * 190) / ancho);
      int g = 28 + int((uint64_t(y) * 120) / alto);
      int b = 64 + int((uint64_t(x) * y * 150) / (uint64_t(ancho) * alto));
      const int onda = int(((x >> 5) + (y >> 4)) & 7) - 3;
      r += onda * 4;
      g += onda * 3;
      b -= onda * 5;
      // Fine alternating detail at the texel scale: what makes the mode search do work.
      if (((x >> 1) ^ (y >> 1)) & 1) {
        r += 26;
        g -= 20;
        b += 16;
      }
      // Hard edges with flat interiors, like text and HUD panels.
      const uint32_t cx = x % 97, cy = y % 61;
      if (cx < 9 && cy < 13) {
        r = 240; g = 238; b = 230;
      } else if (cx < 9 && cy >= 20 && cy < 26) {
        r = 16; g = 14; b = 12;
      }
      // A little deterministic noise; photos are not pure noise and neither are these.
      const int n = int((siguiente() >> 24) & 0x1F) - 16;
      r += n;
      g += n / 2;
      b += n;
      p[0] = uint8_t(std::clamp(r, 0, 255));
      p[1] = uint8_t(std::clamp(g, 0, 255));
      p[2] = uint8_t(std::clamp(b, 0, 255));
      p[3] = (cx >= 80 && cy < 30) ? 0 : uint8_t(std::clamp(191 + int((64 * y) / alto), 0, 255));
    }
  }
}

// `lado` is the side of the square test image; a power of two keeps every path aligned.
inline std::vector<Resultado> Conversiones(uint32_t lado, uint32_t repeticiones) {
  std::vector<Resultado> resultados;
  if (!lado || !repeticiones) return resultados;
  const double mb = double(lado) * double(lado) * 4.0 / (1024.0 * 1024.0);
  const auto velocidad = [mb, repeticiones](double ms) {
    const double total_ms = ms * repeticiones;
    return total_ms > 0 ? (mb * repeticiones) / (total_ms / 1000.0) : 0.0;
  };

  std::vector<uint8_t> imagen;
  ImagenDePrueba(imagen, lado, lado);

  // --- BC decode: what the renderer does today ------------------------------------------------
  // Any bit pattern is a valid block and the decoder cost is per block, not per content, so the input is
  // a deterministic pattern instead of a real texture (we have no BC encoder to make one).
  const uint32_t bloques_x = (lado + 3) / 4;
  for (const auto& [formato, nombre] : {std::pair{nfsmw::bc::Formato::BC1, "BC1 decode -> RGBA8"},
                                       std::pair{nfsmw::bc::Formato::BC3, "BC3 decode -> RGBA8"}}) {
    const size_t bytes = size_t(bloques_x) * bloques_x * nfsmw::bc::BytesBloque(formato);
    std::vector<uint8_t> comprimido(bytes);
    uint32_t s = 0x1234567u;
    for (size_t i = 0; i < bytes; ++i) {
      s ^= s << 13;
      s ^= s >> 17;
      s ^= s << 5;
      comprimido[i] = uint8_t(s >> 24);
    }
    std::vector<uint8_t> salida;
    std::array<uint32_t, 16> desplazamientos{};
    const auto inicio = std::chrono::steady_clock::now();
    for (uint32_t r = 0; r < repeticiones; ++r) {
      if (!nfsmw::bc::Convertir(formato, comprimido, lado, lado, 1, 1, salida, desplazamientos)) {
        resultados.push_back({nombre, 0, 0, "fallo"});
        break;
      }
    }
    const double ms = Milisegundos(inicio) / repeticiones;
    resultados.push_back({nombre, ms, velocidad(ms), {}});
  }

  // --- ETC2 RGBA8 (etcpak) --------------------------------------------------------------------
  {
    std::vector<uint32_t> scratch;
    std::vector<uint8_t> bloques(size_t(bloques_x) * bloques_x * 16);
    const auto inicio = std::chrono::steady_clock::now();
    for (uint32_t r = 0; r < repeticiones; ++r) {
      nfsmw::etc2::CodificarNivelRgba(imagen, 0, lado, lado, 1, false, scratch, bloques.data());
    }
    const double ms = Milisegundos(inicio) / repeticiones;
    resultados.push_back({"ETC2 RGBA8 4x4 (etcpak, 4 bpp)", ms, velocidad(ms), "16 B/bloque"});
  }

  // --- ASTC (astcenc, fastest preset) ---------------------------------------------------------
  for (const uint32_t bloque : {4u, 6u, 8u}) {
    const auto inicio_creacion = std::chrono::steady_clock::now();
    nfsmw::astc::Codificador codificador(bloque, bloque);
    const double ms_creacion = Milisegundos(inicio_creacion);
    const std::string nota = "contexto " + std::to_string(int(ms_creacion + 0.5)) + " ms";
    if (!codificador.Valido()) {
      resultados.push_back({"ASTC " + std::to_string(bloque) + "x" + std::to_string(bloque), 0, 0,
                            "no disponible"});
      continue;
    }
    std::vector<uint8_t> bloques(
        size_t(nfsmw::astc::BytesPorNivel(lado, lado, 1, bloque, bloque)));
    const auto inicio = std::chrono::steady_clock::now();
    bool bien = true;
    for (uint32_t r = 0; r < repeticiones && bien; ++r) {
      bien = codificador.Codificar(imagen.data(), lado, lado, 1, bloques.data());
    }
    const double ms = Milisegundos(inicio) / repeticiones;
    resultados.push_back({"ASTC " + std::to_string(bloque) + "x" + std::to_string(bloque) +
                              " (astcenc fastest, " +
                              std::to_string(bloque == 4 ? 8 : bloque == 6 ? 3 : 2) + " bpp)",
                          bien ? ms : 0, bien ? velocidad(ms) : 0, nota});
  }
  return resultados;
}

// Repetitions that keep the whole run around a couple of seconds whatever the side: 8 MB of source RGBA
// per stage, at least one pass.
inline uint32_t RepeticionesPara(uint32_t lado) {
  const double mb = double(lado) * double(lado) * 4.0 / (1024.0 * 1024.0);
  return uint32_t(std::clamp(int(8.0 / (mb > 0 ? mb : 1)), 1, 8));
}

}  // namespace nfsmw::medir
