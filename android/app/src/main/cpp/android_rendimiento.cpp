// Keeps the heavy threads of the game on the phone's performance cores.
//
// On the Switch the game gets three whole cores at a fixed clock. A phone mixes big and little cores, and the
// scheduler judges a thread by its recent load: the ring thread and the game thread wait for each other every
// frame, look half idle, and end up on a little core at a low clock. That is a lost frame each time.
//
// Which cores are the performance ones is not "the fastest clock" on every SoC. The Exynos 7884B (Galaxy A20)
// has 2x Cortex-A73 at 2.0 GHz and 6x Cortex-A53 at 1.6 GHz: 1.6/2.0 = 80 %, so a "at least 70 % of the
// fastest" test lets all eight through and the affinity is never applied ("8 CPU cores without slow
// cluster"). Two sources are tried in order; if neither works, the mask is left alone:
//
//   1. /sys/devices/system/cpu/cpuN/cpu_capacity, the scheduler's own view of each core (A73 ~1024, A53 ~512
//      or 768 on the same SoC). Present since Android 8 on essentially every big.LITTLE kernel.
//   2. cpuinfo_max_freq, keeping the cores within 90 % of the fastest. 90 % separates every big.LITTLE
//      combination in use (the closest common one is 1.8/2.0 = 90 %, which stays together) and rejects 80 %.
//
// The mask is applied to every thread that already exists (the SDL thread, the UI thread, the logger), and the
// threads created later inherit it. That is the "everything on the big cluster" mode. On a SoC whose top
// cluster has a single core that would be reckless, so it is only applied when there are at least
// nfsmw_android_nucleos_grandes_min cores in it; the heavy renderer threads can still ask for it one by one
// with nfsmw_android_hilo_nucleos_grandes().
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include <rex/cvar.h>
#include <rex/logging.h>

REXCVAR_DEFINE_BOOL(nfsmw_android_nucleos_grandes, true, "NFSMW",
                    "Android: restrict the game threads to the performance cores (cpu_capacity, or max clock "
                    "within 90% of the fastest); false = system default")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_INT32(nfsmw_android_nucleos_grandes_min, 2, "NFSMW",
                     "Android: only move every thread to the performance cluster if it has at least this many "
                     "cores; below it, only the renderer threads ask for it one by one")
    .range(1, 8)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace {

// Mask of the performance cores, computed once at startup. A zero count means "do not pin anything".
cpu_set_t g_big_mask;
int g_big_count = 0;

long ReadNumber(const std::string& path) {
  std::ifstream f(path);
  long value = 0;
  return (f >> value) ? value : 0;
}

long CoreCapacity(int cpu) {
  // cpu_capacity is 0-1024 (the scheduler's capacity). Any positive number is usable for comparing the cores
  // of one SoC.
  return ReadNumber("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpu_capacity");
}

long MaxClockKhz(int cpu) {
  return ReadNumber("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
}

}  // namespace

extern "C" void nfsmw_android_nucleos_grandes_aplicar() {
  if (!REXCVAR_GET(nfsmw_android_nucleos_grandes)) {
    return;
  }
  const int total = int(sysconf(_SC_NPROCESSORS_CONF));
  if (total <= 1 || total > CPU_SETSIZE) {
    return;
  }
  std::vector<long> values(size_t(total), 0);
  const char* source = "cpu_capacity";
  long maximum = 0;
  for (int cpu = 0; cpu < total; ++cpu) {
    values[size_t(cpu)] = CoreCapacity(cpu);
    maximum = std::max(maximum, values[size_t(cpu)]);
  }
  if (maximum <= 0) {
    source = "cpuinfo_max_freq";
    maximum = 0;
    for (int cpu = 0; cpu < total; ++cpu) {
      values[size_t(cpu)] = MaxClockKhz(cpu);
      maximum = std::max(maximum, values[size_t(cpu)]);
    }
  }
  if (maximum <= 0) {
    REXLOG_INFO("[android] cannot read cpu_capacity or CPU core frequencies: affinity unchanged");
    return;
  }
  CPU_ZERO(&g_big_mask);
  int chosen = 0;
  std::string list;
  for (int cpu = 0; cpu < total; ++cpu) {
    // Within 90 % of the best core. With cpu_capacity that is the big cluster and nothing else; with clocks it
    // is the same unless the SoC has a cluster within 10 % of the fastest, in which case keeping it together is
    // exactly what we want (both are performance cores, e.g. 1.8/2.0).
    if (values[size_t(cpu)] * 10 >= maximum * 9) {
      CPU_SET(cpu, &g_big_mask);
      ++chosen;
      list += (list.empty() ? "" : ",") + std::to_string(cpu);
    }
  }
  if (chosen == total) {
    REXLOG_INFO("[android] all {} CPU cores are equal by {} ({}): affinity unchanged", total, source, maximum);
    return;
  }
  g_big_count = chosen;
  const int minimum = REXCVAR_GET(nfsmw_android_nucleos_grandes_min);
  if (chosen < minimum) {
    REXLOG_INFO("[android] performance cores {} of {} by {} (best {}); only {} of them, so the process mask is "
                "left alone and the renderer threads pin themselves",
                list, total, source, maximum, chosen);
    return;
  }
  // Every thread that already exists (the SDL thread, the UI thread, the logger); the ones created later
  // inherit the mask of their creator.
  int threads = 0;
  std::error_code ec;
  for (const auto& task : std::filesystem::directory_iterator("/proc/self/task", ec)) {
    const pid_t tid = pid_t(std::strtol(task.path().filename().c_str(), nullptr, 10));
    if (tid > 0 && sched_setaffinity(tid, sizeof(g_big_mask), &g_big_mask) == 0) {
      ++threads;
    }
  }
  REXLOG_INFO("[android] game running on cores {} of {} by {} (best {}); {} threads migrated", list, total,
              source, maximum, threads);
}

// Called from the renderer threads that do the heavy work (ring, vertex copies, cache writer, texture hash).
// It only touches the calling thread, so it is safe even when the process-wide mask was skipped.
extern "C" void nfsmw_android_hilo_nucleos_grandes() {
  if (g_big_count <= 0) {
    return;
  }
  sched_setaffinity(0, sizeof(g_big_mask), &g_big_mask);
}
