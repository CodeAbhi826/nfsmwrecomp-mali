// Generated for ReXGlue Android runtime - NFSMW
#pragma once

#define REX_CONFIG_H_INCLUDED

#define REX_IMAGE_BASE 0x82000000ull
#define REX_IMAGE_SIZE 0x00cd0000ull
#define REX_CODE_BASE  0x820e0000ull
#define REX_CODE_SIZE  0x007e8ba0ull
#define REX_THUNK_RESERVE_SIZE 0ull
#define REXCRT_HEAP 0

#include <rex/ppc.h>
#include <rex/image_info.h>
#include <rex/logging.h>
#include <rex/chrono/clock.h>
#include <rex/perf/counter.h>
#include <rex/thread/mutex.h>
#include <rex/system/mmio_handler.h>

#include <atomic>

extern const rex::PPCImageInfo PPCImageConfig;
