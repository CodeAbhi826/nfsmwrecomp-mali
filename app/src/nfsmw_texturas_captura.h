// Ahead-of-time capture of the BC textures, so the ETC2 encode happens before the game boots and never
// inside the texture path.
//
// Why. Encoding a texture to ETC2 costs a few milliseconds, and a zone load brings dozens of textures at
// once: done lazily, the first visit to each zone pays a burst of encoding exactly when the frame budget is
// tightest. This file splits that work in two:
//
//   1. Capture (a session with nfsmw_nativo_texturas_etc2 off). The renderer already has to untile and
//      byte-swap each BC texture to decode it to RGBA8, and that is the expensive part. The result is copied
//      to an append-only file as it is produced. No encoding, no extra CPU work worth mentioning.
//   2. Convert (the next start, before the game is running). Every captured texture is decoded and encoded
//      once, from the captured bytes, and stored in the ETC2 block cache. Nothing else is running.
//
// After that the renderer only looks the texture up: a hit reads the blocks from the cache and skips the
// untiling, the BC decode and the encode. Textures seen for the first time after the last conversion still
// take the old path (one encode); the next start moves them over.
//
// The capture holds bytes from the game, so it stays in the app's private directory, is never distributed,
// and is truncated as soon as everything in it is in the block cache.
//
// The record format is deliberately the same information nfsmw::bc::Convertir needs, so the converter is
// just: read record, remove the tiling, decode, encode, append. It is GPU-independent and testable on PC.
#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <span>
#include <string>
#include <unordered_set>
#include <vector>

#include "nfsmw_texturas_bc.h"
#include "nfsmw_texturas_etc2.h"
#include "nfsmw_texturas_astc.h"

namespace nfsmw::captura_texturas {

// One texture as it came out of the guest: [clave u64][bytes u32][ancho u32][alto u32][capas u32]
// [niveles u8][indice_bc u8][reservado 6], then `bytes` of untiled, byte-swapped BC blocks.
//
// `clave` is the ETC2 cache key the renderer will compute for this texture (content hash + shape + format),
// so the converter never has to reconstruct it and a capture can never disagree with a lookup.
constexpr uint32_t kMagiaFichero = 0x5442464Eu;  // "NFBT" in little-endian
constexpr uint32_t kVersionFichero = 1;
constexpr size_t kCabeceraFichero = 8;
// 32 bytes: the first field is a u64 and the payload starts on a 16-byte boundary, so the reader never has
// to realign.
constexpr size_t kCabeceraRegistro = 32;
// The capture is deleted as soon as it is converted; the cap only protects against a machine that never
// runs the ETC2 path again. BC data for the whole game is well under 512 MB.
constexpr uint64_t kTamanoMaximo = uint64_t(1) << 30;

class CapturaTexturas {
 public:
  CapturaTexturas() = default;
  ~CapturaTexturas() { Cerrar(); }
  CapturaTexturas(const CapturaTexturas&) = delete;
  CapturaTexturas& operator=(const CapturaTexturas&) = delete;

  bool Abrir(const std::string& ruta) {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    CerrarSinCerrojo();
    ruta_ = ruta;
    fichero_ = std::fopen(ruta.c_str(), "r+b");
    bool nuevo = false;
    if (!fichero_) {
      fichero_ = std::fopen(ruta.c_str(), "w+b");
      nuevo = true;
    }
    if (!fichero_) return false;
    if (nuevo) {
      const uint32_t cabecera[2] = {kMagiaFichero, kVersionFichero};
      if (std::fwrite(cabecera, sizeof(cabecera), 1, fichero_) != 1) {
        CerrarSinCerrojo();
        return false;
      }
      bytes_fichero_ = kCabeceraFichero;
      std::fflush(fichero_);
      return true;
    }
    if (std::fseek(fichero_, 0, SEEK_END) != 0) {
      CerrarSinCerrojo();
      return false;
    }
    const long total = std::ftell(fichero_);
    std::fseek(fichero_, 0, SEEK_SET);
    uint32_t cabecera[2] = {};
    if (total < long(kCabeceraFichero) || std::fread(cabecera, sizeof(cabecera), 1, fichero_) != 1 ||
        cabecera[0] != kMagiaFichero || cabecera[1] != kVersionFichero) {
      CerrarSinCerrojo();
      return false;
    }
    // Index the keys and, at the same time, find where the last complete record ends.
    uint64_t util = kCabeceraFichero;
    uint8_t registro[kCabeceraRegistro];
    while (util + kCabeceraRegistro <= uint64_t(total)) {
      if (std::fread(registro, sizeof(registro), 1, fichero_) != 1) break;
      uint64_t clave = 0;
      uint32_t bytes = 0;
      std::memcpy(&clave, registro, sizeof(clave));
      std::memcpy(&bytes, registro + 8, sizeof(bytes));
      if (bytes == 0 || util + kCabeceraRegistro + bytes > uint64_t(total)) break;  // half written tail
      claves_.insert(clave);
      util += kCabeceraRegistro + bytes;
      if (std::fseek(fichero_, long(util), SEEK_SET) != 0) break;
    }
    if (util != uint64_t(total)) {
      std::error_code error;
      std::filesystem::resize_file(ruta_, util, error);
      if (error) {
        CerrarSinCerrojo();
        return false;
      }
    }
    bytes_fichero_ = util;
    std::fseek(fichero_, long(util), SEEK_SET);
    return true;
  }

  void Cerrar() {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    CerrarSinCerrojo();
  }

  bool Abierta() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return fichero_ != nullptr;
  }

  const std::string& Ruta() const { return ruta_; }

  uint64_t Entradas() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return claves_.size();
  }

  uint64_t Tamano() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return bytes_fichero_;
  }

  // Called from the texture path: one record per texture the renderer sees for the first time. A duplicate
  // key (the same content again) is not written. Returns false when it could not be stored, which is never
  // fatal: the texture is simply encoded the old way.
  bool Anadir(uint64_t clave, uint32_t ancho, uint32_t alto, uint32_t capas, uint32_t niveles,
              uint8_t indice_bc, std::span<const uint8_t> datos) {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    if (!fichero_ || datos.empty() || niveles == 0 || niveles > 16 || capas == 0) return false;
    if (claves_.count(clave)) return false;
    if (bytes_fichero_ + kCabeceraRegistro + datos.size() > kTamanoMaximo) return false;
    uint8_t registro[kCabeceraRegistro] = {};
    const uint32_t bytes = uint32_t(datos.size());
    std::memcpy(registro, &clave, sizeof(clave));
    std::memcpy(registro + 8, &bytes, sizeof(bytes));
    std::memcpy(registro + 12, &ancho, sizeof(ancho));
    std::memcpy(registro + 16, &alto, sizeof(alto));
    std::memcpy(registro + 20, &capas, sizeof(capas));
    registro[24] = uint8_t(niveles);
    registro[25] = indice_bc;
    if (std::fseek(fichero_, long(bytes_fichero_), SEEK_SET) != 0) return false;
    if (std::fwrite(registro, sizeof(registro), 1, fichero_) != 1) return false;
    if (std::fwrite(datos.data(), 1, datos.size(), fichero_) != datos.size()) return false;
    // No fsync, exactly like the block cache: the record is in the page cache and a kill only costs the last
    // one, which the loader drops and the next run captures again.
    std::fflush(fichero_);
    bytes_fichero_ += kCabeceraRegistro + datos.size();
    claves_.insert(clave);
    return true;
  }

  // Everything in the file is in the block cache: drop it so the next start does not walk it again.
  bool Reiniciar() {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    if (!fichero_) return false;
    std::error_code error;
    std::filesystem::resize_file(ruta_, kCabeceraFichero, error);
    if (error) return false;
    claves_.clear();
    bytes_fichero_ = kCabeceraFichero;
    std::fseek(fichero_, long(bytes_fichero_), SEEK_SET);
    return true;
  }

 private:
  void CerrarSinCerrojo() {
    if (fichero_) {
      std::fclose(fichero_);
      fichero_ = nullptr;
    }
    claves_.clear();
    bytes_fichero_ = 0;
  }

  mutable std::mutex cerrojo_;
  std::string ruta_;
  FILE* fichero_ = nullptr;
  uint64_t bytes_fichero_ = 0;
  std::unordered_set<uint64_t> claves_;
};

struct ConversionResult {
  uint64_t converted = 0;  // encoded and stored in the block cache now
  uint64_t skipped = 0;    // already in the block cache
  uint64_t failed = 0;     // could not be decoded/encoded/stored
  uint64_t bytes = 0;      // source BC bytes read from the capture
  double ms = 0;
};

// Walks the capture once. The policy decides everything that changes between targets:
//
//   uint64_t CacheKey(capture_key, width, height, layers, levels, bc_index)
//   uint32_t Side(width, height, layers, levels, bc_index)          // 0 for ETC2
//   bool Convert(side, rgba, src_offsets, width, height, layers, levels, blocks, dst_offsets)
//
// Keyed by the capture key so that the texture path and this walker always agree: ETC2 uses it as is, ASTC
// mixes in the block side (4x4 and 6x6 payloads of the same texture are different bytes). `progress(read,
// result)` is called every 128 records; pass a no-op lambda when the caller does not care. It does not touch
// the capture file (the caller decides whether to truncate it) and needs no GPU.
template <typename Policy, typename Progress>
ConversionResult ConvertCaptureWith(const std::string& path, nfsmw::etc2::CacheTexturas& cache, Policy&& policy,
                                    Progress&& progress) {
  ConversionResult result;
  const auto start = std::chrono::steady_clock::now();
  FILE* file = std::fopen(path.c_str(), "rb");
  if (!file) return result;
  uint32_t header[2] = {};
  if (std::fread(header, sizeof(header), 1, file) != 1 || header[0] != kMagiaFichero ||
      header[1] != kVersionFichero) {
    std::fclose(file);
    return result;
  }
  std::vector<uint8_t> data, rgba, blocks;
  std::array<uint32_t, 16> src_offsets{};
  std::array<uint32_t, 16> dst_offsets{};
  uint64_t read = 0;
  while (true) {
    uint8_t record[kCabeceraRegistro];
    if (std::fread(record, sizeof(record), 1, file) != 1) break;
    uint64_t capture_key = 0;
    uint32_t bytes = 0, width = 0, height = 0, layers = 0;
    std::memcpy(&capture_key, record, sizeof(capture_key));
    std::memcpy(&bytes, record + 8, sizeof(bytes));
    std::memcpy(&width, record + 12, sizeof(width));
    std::memcpy(&height, record + 16, sizeof(height));
    std::memcpy(&layers, record + 20, sizeof(layers));
    const uint32_t levels = record[24];
    const uint8_t bc_index = record[25];
    if (bytes == 0 || bytes > (uint64_t(1) << 30) || width == 0 || height == 0 || layers == 0 || levels == 0 ||
        levels > 16 || bc_index < 1 || bc_index > 3) {
      break;  // corrupt record: stop, the rest cannot be trusted either
    }
    data.resize(bytes);
    if (std::fread(data.data(), 1, bytes, file) != bytes) break;
    ++read;
    if ((read & 127) == 0) progress(read, result);
    const uint64_t cache_key = policy.CacheKey(capture_key, width, height, layers, levels, bc_index);
    if (cache.Buscar(cache_key)) {
      ++result.skipped;
      continue;
    }
    if (!nfsmw::bc::Convertir(nfsmw::bc::Formato(bc_index), data, width, height, layers, levels, rgba,
                              src_offsets)) {
      ++result.failed;
      continue;
    }
    const uint32_t side = policy.Side(width, height, layers, levels, bc_index);
    if (!policy.Convert(side, rgba, src_offsets, width, height, layers, levels, blocks, dst_offsets)) {
      ++result.failed;
      continue;
    }
    if (cache.Guardar(cache_key, bc_index, blocks) || cache.Buscar(cache_key)) {
      ++result.converted;
      result.bytes += bytes;
    } else {
      ++result.failed;
    }
  }
  std::fclose(file);
  result.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  return result;
}

// ETC2: the cache key is the capture key, and the blocks are produced exactly as the texture path does.
struct Etc2Policy {
  mutable std::vector<uint32_t> scratch;

  uint64_t CacheKey(uint64_t capture_key, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) const {
    return capture_key;
  }
  uint32_t Side(uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) const { return 0; }
  bool Convert(uint32_t, const std::vector<uint8_t>& rgba, const std::array<uint32_t, 16>& src, uint32_t width,
               uint32_t height, uint32_t layers, uint32_t levels, std::vector<uint8_t>& output,
               std::array<uint32_t, 16>& dst) const {
    uint32_t bytes = 0;
    if (!nfsmw::etc2::Disposicion(width, height, layers, levels, false, dst, bytes)) return false;
    output.assign(bytes, 0);
    for (uint32_t n = 0; n < levels; ++n) {
      nfsmw::etc2::CodificarNivelRgba(rgba, src[n], std::max(width >> n, 1u), std::max(height >> n, 1u), layers,
                                      false, scratch, output.data() + dst[n]);
    }
    return true;
  }
};

// ASTC: two encoders at most (4x4 for the detail textures, `base` for the rest). `encoder_4` and
// `encoder_base` may be the same object when base == 4.
struct AstcPolicy {
  uint32_t base = 6;
  bool mixed = true;
  nfsmw::astc::Encoder* encoder_4 = nullptr;
  nfsmw::astc::Encoder* encoder_base = nullptr;

  uint32_t Side(uint32_t width, uint32_t height, uint32_t layers, uint32_t levels, uint32_t bc_index) const {
    return nfsmw::astc::ChosenSide(base, mixed, width, height, layers, levels, bc_index);
  }
  uint64_t CacheKey(uint64_t capture_key, uint32_t width, uint32_t height, uint32_t layers, uint32_t levels,
                    uint32_t bc_index) const {
    return nfsmw::astc::CacheKey(capture_key, Side(width, height, layers, levels, bc_index));
  }
  bool Convert(uint32_t side, const std::vector<uint8_t>& rgba, const std::array<uint32_t, 16>& src,
               uint32_t width, uint32_t height, uint32_t layers, uint32_t levels, std::vector<uint8_t>& output,
               std::array<uint32_t, 16>& dst) const {
    nfsmw::astc::Encoder* encoder = side == 4 ? encoder_4 : encoder_base;
    if (!encoder || !encoder->Valid()) return false;
    uint32_t bytes = 0;
    if (!nfsmw::astc::Layout(width, height, layers, levels, encoder->BlockX(), encoder->BlockY(), dst, bytes)) {
      return false;
    }
    output.assign(bytes, 0);
    for (uint32_t n = 0; n < levels; ++n) {
      if (!encoder->Encode(rgba.data() + src[n], std::max(width >> n, 1u), std::max(height >> n, 1u), layers,
                           output.data() + dst[n])) {
        return false;
      }
    }
    return true;
  }
};

template <typename Progress>
ConversionResult ConvertCapture(const std::string& path, nfsmw::etc2::CacheTexturas& cache,
                                Progress&& progress) {
  return ConvertCaptureWith(path, cache, Etc2Policy{}, std::forward<Progress>(progress));
}

template <typename Progress>
ConversionResult ConvertCaptureAstc(const std::string& path, nfsmw::etc2::CacheTexturas& cache,
                                    const AstcPolicy& policy, Progress&& progress) {
  return ConvertCaptureWith(path, cache, policy, std::forward<Progress>(progress));
}


}  // namespace nfsmw::captura_texturas
