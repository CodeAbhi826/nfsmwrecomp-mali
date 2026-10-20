// ASTC transcode of BC textures, with the ARM astcenc encoder.
//
// Why it is here. ETC2_R8G8B8A8 is the cheap option: 8 bpp, the same 16-byte block as BC2/BC3, a fast encoder
// (etcpak) and a block layout identical to BC, so nothing in the upload path changes. ASTC is the expensive
// one: the encoder is a real search (astcenc), but the block size is a free parameter, so the same texture can
// be 8 bpp (4x4), 3.56 bpp (6x6) or 2 bpp (8x8) instead of the fixed 8 bpp of ETC2. ASTC 6x6 is half the VRAM
// of ETC2 and ASTC 8x8 a quarter, and both cost far more CPU to produce.
//
// Mixed sizes. One block size for the whole game wastes the parameter: a HUD label or a decal shows the 6x6
// error immediately, while a road tile or a tree atlas does not. NeedsDetail() picks the textures whose block
// error lands on screen (small images, and alpha-tested ones) and those are encoded at 4x4; everything else
// uses the configured base size. At 4x4 a small texture is a few KB, so the extra quality is free.
//
// Nothing here touches Vulkan, so the encoder wrapper can be exercised on PC.
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <vector>

#include "thirdparty/astcenc/astcenc.h"

namespace nfsmw::astc {

// Every ASTC block is 128 bits; the block size only decides how many texels share them.
constexpr uint32_t kBlockBytes = 16;

inline uint32_t Blocks(uint32_t texels, uint32_t block_side) {
  return (texels + block_side - 1) / block_side;
}

inline uint64_t LevelBytes(uint32_t width, uint32_t height, uint32_t layers, uint32_t bx, uint32_t by) {
  return uint64_t(Blocks(width, bx)) * Blocks(height, by) * layers * kBlockBytes;
}

// Offsets of every mip level inside the upload buffer for an ASTC image, with the same shape as the ETC2 one
// (level after level, layers back to back): the only difference is that a block covers bx x by texels instead
// of 4x4. An image whose sides are not multiples of the block size still gets whole blocks: the last one in
// each row and column encodes the edge texels clamped, which is what astcenc does on its own.
inline bool Layout(uint32_t width, uint32_t height, uint32_t layers, uint32_t levels, uint32_t bx,
                   uint32_t by, std::array<uint32_t, 16>& offsets, uint32_t& bytes) {
  if (!width || !height || !layers || !levels || levels > offsets.size() || !bx || !by) return false;
  uint64_t cursor = 0;
  for (uint32_t n = 0; n < levels; ++n) {
    const uint64_t bxx = Blocks(std::max(width >> n, 1u), bx);
    const uint64_t byy = Blocks(std::max(height >> n, 1u), by);
    offsets[n] = uint32_t(cursor);
    cursor += bxx * byy * kBlockBytes * layers;
    if (cursor > 0xFFFFFFFFull) return false;
  }
  bytes = uint32_t(cursor);
  return true;
}

// The quality levels astcenc exposes, in the order the CVar numbers them: 0 fastest, 2 medium (the encoder's
// own recommendation for production assets), 3 thorough, 4 exhaustive. astcenc 5 takes the quality as a float
// between the ASTCENC_PRE_* constants, not as a preset enum.
constexpr int kQualityLevels = 5;
inline float QualityForLevel(int quality) {
  switch (quality) {
    case 0: return ASTCENC_PRE_FASTEST;
    case 1: return ASTCENC_PRE_FAST;
    case 3: return ASTCENC_PRE_THOROUGH;
    case 4: return ASTCENC_PRE_EXHAUSTIVE;
    default: return ASTCENC_PRE_MEDIUM;
  }
}
inline const char* QualityName(int quality) {
  switch (quality) {
    case 0: return "fastest";
    case 1: return "fast";
    case 3: return "thorough";
    case 4: return "exhaustive";
    default: return "medium";
  }
}

// True when a texture is one where a 6x6/8x8 block error is visible and the extra bytes are cheap: small
// images (HUD, text, icons, decals) and alpha-tested ones (foliage, fences, banners), whose cutout a large
// block smears. `bc_index` is 1..5 as the renderer numbers the BC formats; BC2/BC3 carry alpha.
inline bool NeedsDetail(uint32_t width, uint32_t height, uint32_t layers, uint32_t levels, uint32_t bc_index) {
  (void)levels;
  const uint64_t area = uint64_t(width) * height * layers;
  if (area <= uint64_t(128) * 128) return true;
  if (bc_index >= 2 && area <= uint64_t(512) * 512) return true;
  return false;
}

// The block side a texture gets: the configured base size, or 4x4 when mixing is on, the base is larger, and
// the texture is one where the block error would be visible.
inline uint32_t ChosenSide(uint32_t base, bool mixed, uint32_t width, uint32_t height, uint32_t layers,
                           uint32_t levels, uint32_t bc_index) {
  if (base <= 4) return 4;
  if (!mixed) return base;
  return NeedsDetail(width, height, layers, levels, bc_index) ? 4 : base;
}

// The block cache key for an ASTC payload: the capture key (content + shape + BC format) mixed with the block
// side, because the same texture at 4x4 and 6x6 are different bytes. ETC2 keeps using the capture key as is,
// so its cache and its keys are untouched by the ASTC work. A splitmix64 finalizer is enough: the capture key
// is already a strong hash.
inline uint64_t CacheKey(uint64_t capture_key, uint32_t side) {
  uint64_t h = capture_key ^ (uint64_t(side) * 0x9E3779B97F4A7C15ull);
  h ^= h >> 30;
  h *= 0xBF58476D1CE4E5B9ull;
  h ^= h >> 27;
  h *= 0x94D049BB133111EBull;
  h ^= h >> 31;
  return h;
}

// One encoder context per block size: astcenc allocates its tables and per-block scratch on creation and is
// meant to be reused.
class Encoder {
 public:
  explicit Encoder(uint32_t bx, uint32_t by, int quality = 0) : bx_(bx), by_(by) {
    astcenc_config config{};
    // LDR (not sRGB): the images are VK_FORMAT_*_UNORM, and the sampled value must stay linear, exactly like
    // the ETC2_R8G8B8A8_UNORM the renderer already uses.
    if (astcenc_config_init(ASTCENC_PRF_LDR, bx, by, 1, QualityForLevel(quality), 0, &config) !=
        ASTCENC_SUCCESS) {
      return;
    }
    if (astcenc_context_alloc(&config, 1, &context_, nullptr) != ASTCENC_SUCCESS) {
      context_ = nullptr;
    }
  }
  ~Encoder() {
    if (context_) astcenc_context_free(context_);
  }
  Encoder(const Encoder&) = delete;
  Encoder& operator=(const Encoder&) = delete;

  bool Valid() const { return context_ != nullptr; }
  uint32_t BlockX() const { return bx_; }
  uint32_t BlockY() const { return by_; }

  // `rgba` holds `layers` slices of `width`x`height` RGBA8, rows tight, slices back to back. astcenc clamps
  // reads past the edge, so dimensions that are not multiples of the block size are fine.
  bool Encode(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t layers, uint8_t* output) {
    if (!context_ || !rgba || !output || !width || !height || !layers) return false;
    // One entry per slice, each pointing at the first texel of that slice (tightly packed rows).
    slices_.resize(layers);
    for (uint32_t z = 0; z < layers; ++z) {
      slices_[z] = const_cast<uint8_t*>(rgba) + size_t(z) * width * height * 4;
    }
    astcenc_image image{};
    image.dim_x = width;
    image.dim_y = height;
    image.dim_z = layers;
    image.data_type = ASTCENC_TYPE_U8;
    image.data = slices_.data();
    const astcenc_swizzle swizzle = {ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    const size_t bytes = size_t(LevelBytes(width, height, layers, bx_, by_));
    if (astcenc_compress_image(context_, &image, &swizzle, output, bytes, 0) != ASTCENC_SUCCESS) {
      return false;
    }
    astcenc_compress_reset(context_);
    return true;
  }

 private:
  astcenc_context* context_ = nullptr;
  uint32_t bx_ = 4;
  uint32_t by_ = 4;
  // astcenc wants an array of slice pointers.
  std::vector<void*> slices_;
};

}  // namespace nfsmw::astc
