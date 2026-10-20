// ETC2 (Mali) transcode of the BC textures that have to be decoded on the CPU, plus the append-only
// disk cache that lets a later run skip both the BC decode and the ETC2 encode.
//
// Why ETC2. On a Vulkan driver without BC support (Mali, Xclipse...) every BC1-3 texture is decoded to
// RGBA8: 32 bits per texel against the 4 the source format carries, so 8x the VRAM and 8x the sampling
// bandwidth. That is where the frame time and the heat of a race go. ETC2_R8G8B8A8_UNORM_BLOCK is 16
// bytes per 4x4 block, exactly the size of a BC2/BC3 block, is decoded in hardware and keeps the 8-bit
// alpha that BC1's punchthrough mode needs. EAC R11/R11G11 do the same for BC4/BC5.
//
// The block layout matches BC's, so the upload path (GrabarCopiaTextura and its per level offsets) does
// not change: only the format, the byte count and who produces the bytes do.
//
// Nothing here touches Vulkan, so the layout and the file format can be exercised on PC.
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <system_error>
#include <unordered_map>
#include <span>
#include <string>
#include <vector>

#include "thirdparty/etcpak/ProcessRGB.hpp"

namespace nfsmw::etc2 {

// --- Layout ---------------------------------------------------------------------------------------------
// One block is 4x4 texels and 16 bytes for ETC2 RGBA8 and EAC R11G11; 8 bytes for EAC R11.
inline uint32_t Bloques(uint32_t texeles) { return (texeles + 3) / 4; }

inline uint32_t BytesBloque(bool un_canal) { return un_canal ? 8 : 16; }

// Offsets of every mip level inside the upload buffer for an ETC2 image: level after level, each one with
// all its layers back to back, tightly packed (every block is a whole number of bytes, so there is nothing
// to pad and every offset is a multiple of the 16-byte block Vulkan asks bufferOffset to be a multiple of).
inline bool Disposicion(uint32_t ancho, uint32_t alto, uint32_t capas, uint32_t niveles, bool un_canal,
                        std::array<uint32_t, 16>& desplazamientos, uint32_t& bytes) {
  if (!ancho || !alto || !capas || !niveles || niveles > desplazamientos.size()) return false;
  const uint32_t bytes_bloque = BytesBloque(un_canal);
  uint64_t cursor = 0;
  for (uint32_t n = 0; n < niveles; ++n) {
    const uint64_t bx = Bloques(std::max(ancho >> n, 1u));
    const uint64_t by = Bloques(std::max(alto >> n, 1u));
    desplazamientos[n] = uint32_t(cursor);
    cursor += bx * by * bytes_bloque * capas;
    if (cursor > 0xFFFFFFFFull) return false;
  }
  bytes = uint32_t(cursor);
  return true;
}

// --- Encoder --------------------------------------------------------------------------------------------
// One mip level of one image, all layers: `entrada` holds the level's texels as RGBA8 (tight, row stride
// `ancho`) and `desplazamiento` is where the level starts. The encoder needs the source to be a whole
// number of 4x4 blocks wide and four rows tall, so the tiny levels of a mip chain are padded by clamping
// (level 2 of a 4x4 texture is 1x1 and still costs a full block). `rasguño` is scratch kept between calls
// so a padded level does not allocate.
inline void CodificarNivelRgba(const std::vector<uint8_t>& entrada, uint32_t desplazamiento, uint32_t ancho,
                              uint32_t alto, uint32_t capas, bool un_canal, std::vector<uint32_t>& rasguño,
                              uint8_t* destino) {
  const uint32_t bx = Bloques(ancho), by = Bloques(alto);
  const bool exacto = (ancho % 4) == 0 && (alto % 4) == 0;
  uint8_t* salida = destino;
  for (uint32_t capa = 0; capa < capas; ++capa) {
    const uint8_t* plano = entrada.data() + desplazamiento + size_t(ancho) * alto * 4 * capa;
    const uint32_t* fuente = nullptr;
    uint32_t paso = 0;
    if (exacto) {
      fuente = reinterpret_cast<const uint32_t*>(plano);
      paso = ancho;
    } else {
      rasguño.resize(size_t(bx) * 4 * by * 4);
      for (uint32_t y = 0; y < by * 4; ++y) {
        const uint32_t fy = std::min(y, alto - 1);
        uint32_t* fila = rasguño.data() + size_t(y) * bx * 4;
        const uint8_t* origen = plano + size_t(fy) * ancho * 4;
        for (uint32_t x = 0; x < bx * 4; ++x) {
          std::memcpy(&fila[x], origen + size_t(std::min(x, ancho - 1)) * 4, 4);
        }
      }
      fuente = rasguño.data();
      paso = bx * 4;
    }
    // Heuristics off: the frame budget is what this is for, and the source is already lossy BC.
    if (un_canal) {
      CompressEacR(fuente, reinterpret_cast<uint64_t*>(salida), bx * by, paso);
    } else {
      CompressEtc2Rgba(fuente, reinterpret_cast<uint64_t*>(salida), bx * by, paso, false);
    }
    salida += size_t(bx) * by * BytesBloque(un_canal);
  }
}

// --- Disk cache -----------------------------------------------------------------------------------------
// Append-only log of the encoded textures: a small header and then, for every texture, a 16-byte record
// header (key, byte count, format) followed by the payload. Nothing from the game is stored, only derived
// blocks, and a run only appends the textures it was actually missing, so the file grows while playing.
//
// The record header is 16 bytes so a payload always starts on a 16-byte boundary: the reader can then
// serve a hit straight from the file without a realignment copy.
//
// A run that is killed mid-append leaves a half written last record; the loader stops at the first record
// that runs past the end of the file and truncates the tail, so the file heals itself.
constexpr uint32_t kMagiaFichero = 0x5445464Eu;  // "NFET" in little-endian
constexpr uint32_t kVersionFichero = 1;
constexpr size_t kCabeceraFichero = 8;
constexpr size_t kCabeceraRegistro = 16;
// Whole-game content is around 100 MB; the cap only exists so a long-lived install cannot grow without end.
constexpr uint64_t kTamanoMaximo = uint64_t(512) << 20;

class CacheTexturas {
 public:
  struct Entrada {
    uint64_t desplazamiento = 0;
    uint32_t bytes = 0;
    uint8_t formato = 0;
  };

  ~CacheTexturas() { Cerrar(); }

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
    if (total < long(kCabeceraFichero)) {
      std::fclose(fichero_);
      fichero_ = std::fopen(ruta.c_str(), "w+b");
      if (!fichero_) return false;
      const uint32_t cabecera[2] = {kMagiaFichero, kVersionFichero};
      std::fwrite(cabecera, sizeof(cabecera), 1, fichero_);
      bytes_fichero_ = kCabeceraFichero;
      std::fflush(fichero_);
      return true;
    }
    std::fseek(fichero_, 0, SEEK_SET);
    uint32_t cabecera[2] = {};
    if (std::fread(cabecera, sizeof(cabecera), 1, fichero_) != 1 || cabecera[0] != kMagiaFichero ||
        cabecera[1] != kVersionFichero) {
      CerrarSinCerrojo();
      return false;
    }
    uint64_t util = kCabeceraFichero;
    uint8_t registro[kCabeceraRegistro];
    while (util + kCabeceraRegistro <= uint64_t(total)) {
      if (std::fread(registro, sizeof(registro), 1, fichero_) != 1) break;
      uint64_t clave = 0;
      uint32_t bytes = 0;
      std::memcpy(&clave, registro, sizeof(clave));
      std::memcpy(&bytes, registro + 8, sizeof(bytes));
      if (bytes == 0 || util + kCabeceraRegistro + bytes > uint64_t(total)) break;  // half written tail
      indice_.emplace(clave, Entrada{util + kCabeceraRegistro, bytes, registro[12]});
      util += kCabeceraRegistro + bytes;
      if (std::fseek(fichero_, long(util), SEEK_SET) != 0) break;
    }
    if (util != uint64_t(total)) {
      // Drop whatever the last run left half written. resize_file is the portable way to do it (the
      // POSIX truncate and the Windows _chsize are not).
      std::error_code error;
      std::filesystem::resize_file(ruta_, util, error);
    }
    bytes_fichero_ = util;
    std::fseek(fichero_, long(util), SEEK_SET);
    return true;
  }

  void Cerrar() {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    CerrarSinCerrojo();
  }

  const Entrada* Buscar(uint64_t clave) const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    const auto it = indice_.find(clave);
    return it == indice_.end() ? nullptr : &it->second;
  }

  bool Leer(const Entrada& entrada, std::vector<uint8_t>& salida) const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    if (!fichero_) return false;
    salida.resize(entrada.bytes);
    if (std::fseek(fichero_, long(entrada.desplazamiento), SEEK_SET) != 0) return false;
    return std::fread(salida.data(), 1, entrada.bytes, fichero_) == entrada.bytes;
  }

  // Appends. A duplicate key (the same content encoded twice) is not written again.
  bool Guardar(uint64_t clave, uint8_t formato, std::span<const uint8_t> datos) {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    if (!fichero_ || datos.empty()) return false;
    if (indice_.count(clave)) return false;
    if (bytes_fichero_ + kCabeceraRegistro + datos.size() > kTamanoMaximo) {
      lleno_ = true;
      return false;
    }
    uint8_t registro[kCabeceraRegistro] = {};
    const uint32_t bytes = uint32_t(datos.size());
    std::memcpy(registro, &clave, sizeof(clave));
    std::memcpy(registro + 8, &bytes, sizeof(bytes));
    registro[12] = formato;
    if (std::fseek(fichero_, long(bytes_fichero_), SEEK_SET) != 0) return false;
    if (std::fwrite(registro, sizeof(registro), 1, fichero_) != 1) return false;
    if (std::fwrite(datos.data(), 1, datos.size(), fichero_) != datos.size()) return false;
    // No fsync: the record is in the page cache, and the loader drops whatever a kill leaves half written.
    std::fflush(fichero_);
    indice_.emplace(clave, Entrada{bytes_fichero_ + kCabeceraRegistro, bytes, formato});
    bytes_fichero_ += kCabeceraRegistro + datos.size();
    return true;
  }

  size_t Entradas() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return indice_.size();
  }
  uint64_t Tamano() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return bytes_fichero_;
  }
  bool Lleno() const {
    std::lock_guard<std::mutex> cerrojo(cerrojo_);
    return lleno_;
  }

 private:
  void CerrarSinCerrojo() {
    if (fichero_) {
      std::fflush(fichero_);
      std::fclose(fichero_);
      fichero_ = nullptr;
    }
    indice_.clear();
    bytes_fichero_ = 0;
  }

  mutable std::mutex cerrojo_;
  std::FILE* fichero_ = nullptr;
  std::string ruta_;
  std::unordered_map<uint64_t, Entrada> indice_;
  uint64_t bytes_fichero_ = 0;
  bool lleno_ = false;
};

}  // namespace nfsmw::etc2