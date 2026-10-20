// ASTC transcode of BC textures, with the ARM astcenc encoder.
//
// Why it is here. ETC2_R8G8B8A8 is the cheap option: 4 bpp, the same 16-byte block as BC2/BC3, a fast
// encoder (etcpak) and a block layout identical to BC, so nothing in the upload path changes. ASTC is the
// expensive one: the encoder is a real search (astcenc), but the block size is a free parameter, so the
// same texture can be 8 bpp (4x4), 2.67 bpp (6x6) or 2 bpp (8x8) instead of the fixed 4 bpp of ETC2.
// That is the trade this header exists to measure: ASTC 6x6 is 1.5x less VRAM than ETC2, ASTC 4x4 is 2x
// more, and both cost far more CPU to produce.
//
// Nothing here touches Vulkan, so the encoder wrapper can be exercised on PC.
#pragma once

#include <cstdint>
#include <vector>

#include "thirdparty/astcenc/astcenc.h"

namespace nfsmw::astc {

// Every ASTC block is 128 bits; the block size only decides how many texels share them.
constexpr uint32_t kBytesBloque = 16;

inline uint32_t Bloques(uint32_t texeles, uint32_t tam_bloque) {
  return (texeles + tam_bloque - 1) / tam_bloque;
}

inline uint64_t BytesPorNivel(uint32_t ancho, uint32_t alto, uint32_t capas, uint32_t bx, uint32_t by) {
  return uint64_t(Bloques(ancho, bx)) * Bloques(alto, by) * capas * kBytesBloque;
}

// One encoder context per block size: astcenc allocates its tables and per-block scratch on creation and
// is meant to be reused.
class Codificador {
 public:
  Codificador(uint32_t bx, uint32_t by) : bx_(bx), by_(by) {
    astcenc_config config{};
    // LDR (not sRGB): the images are VK_FORMAT_*_UNORM, and the sampled value must stay linear, exactly
    // like the ETC2_R8G8B8A8_UNORM the renderer already uses.
    if (astcenc_config_init(ASTCENC_PRF_LDR, bx, by, 1, ASTCENC_PRE_FASTEST, 0, &config) != ASTCENC_SUCCESS) {
      return;
    }
    if (astcenc_context_alloc(&config, 1, &contexto_, nullptr) != ASTCENC_SUCCESS) {
      contexto_ = nullptr;
    }
  }
  ~Codificador() {
    if (contexto_) astcenc_context_free(contexto_);
  }
  Codificador(const Codificador&) = delete;
  Codificador& operator=(const Codificador&) = delete;

  bool Valido() const { return contexto_ != nullptr; }
  uint32_t BloquesX() const { return bx_; }
  uint32_t BloquesY() const { return by_; }

  // `rgba` holds `capas` slices of `ancho`x`alto` RGBA8, rows tight, slices back to back. astcenc clamps
  // reads past the edge, so dimensions that are not multiples of the block size are fine.
  bool Codificar(const uint8_t* rgba, uint32_t ancho, uint32_t alto, uint32_t capas, uint8_t* salida) {
    if (!contexto_ || !rgba || !salida || !ancho || !alto || !capas) return false;
    filas_.resize(size_t(capas) * alto);
    rebanadas_.resize(capas);
    for (uint32_t z = 0; z < capas; ++z) {
      rebanadas_[z] = filas_.data() + size_t(z) * alto;
      for (uint32_t y = 0; y < alto; ++y) {
        filas_[size_t(z) * alto + y] =
            const_cast<uint8_t*>(rgba) + (size_t(z) * alto + y) * ancho * 4;
      }
    }
    astcenc_image imagen{};
    imagen.dim_x = ancho;
    imagen.dim_y = alto;
    imagen.dim_z = capas;
    imagen.data_type = ASTCENC_TYPE_U8;
    imagen.data = rebanadas_.data();
    const astcenc_swizzle swizzle = {ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    const size_t bytes = size_t(BytesPorNivel(ancho, alto, capas, bx_, by_));
    if (astcenc_compress_image(contexto_, &imagen, &swizzle, salida, bytes, 0) != ASTCENC_SUCCESS) {
      return false;
    }
    astcenc_compress_reset(contexto_);
    return true;
  }

 private:
  astcenc_context* contexto_ = nullptr;
  uint32_t bx_ = 4;
  uint32_t by_ = 4;
  // astcenc wants an array of slices and, per slice, an array of row pointers.
  std::vector<void*> filas_;
  std::vector<void*> rebanadas_;
};

}  // namespace nfsmw::astc
