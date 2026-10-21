// Keeps the heavy threads of the game on the phone's performance cores.
//
// On the Switch the game gets three whole cores at a fixed clock. A phone mixes big and little cores, and the
// scheduler judges a thread by its recent load: the ring thread and the game thread wait for each other every
// frame, look half idle, and end up on a little core at a low clock. That is a lost frame each time.
//
// Which cores are the performance ones is not "the fastest clock" on every SoC. The Exynos 7884B (Galaxy A20)
// has 2x Cortex-A73 at 2.1 GHz and 6x Cortex-A53 at 1.7 GHz: 1.7/2.1 = 81 %, so a "at least 70 % of the
// fastest" test lets all eight through and the affinity is never applied. Two sources are tried in order; if
// neither works, the mask is left alone:
//
//   1. /sys/devices/system/cpu/cpuN/cpu_capacity, the scheduler's own view of each core (A73 ~1024, A53 ~512
//      or 768 on the same SoC). Present since Android 8 on essentially every big.LITTLE kernel.
//   2. cpuinfo_max_freq, keeping the cores within 90 % of the fastest. 90 % separates every big.LITTLE
//      combination in use (the closest common one is 1.8/2.0 = 90 %, which stays together) and rejects 81 %.
//
// nfsmw_android_afinidad selects what is pinned (default "auto"):
//
//   "auto"  A watchdog re-reads /proc/self/task every 2 s and applies name-based rules. The preparer
//           (SDLThread), the ring (GPU anillo nativo), the Xenos command processor (GPU Commands) and the
//           main guest thread (Main XThread) stay on the performance cluster; everything else goes to the
//           other cores so it cannot steal a performance core from the frame. If the other cluster is less
//           than 60 % of the fastest (a 2+6 SoC such as the Snapdragon 695), only those critical threads
//           are pinned and the rest is left free: forcing audio or shader compilation onto cores that slow
//           is worse than the contention it avoids.
//   "all"   The old one-shot mode: every existing thread moves to the performance cluster and the threads
//           created later inherit that mask. Kept as the A/B control.
//   ""      No affinity changes (same as nfsmw_android_nucleos_grandes=false).
//   rules   "SDLThread=6-7;Main XThread=6+7;*=0-5". Prefix match on the kernel's 15-character thread name,
//           first rule wins, "*" is the rest.
//
// The rules are re-applied because Android changes the cpuset when the app goes to the background and the
// kernel re-balances; a one-shot mask would silently stop being true.
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <rex/cvar.h>
#include <rex/logging.h>

REXCVAR_DEFINE_BOOL(nfsmw_android_nucleos_grandes, true, "NFSMW",
                    "Android: master switch for the thread affinity rules; false = leave the scheduler alone")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_INT32(nfsmw_android_nucleos_grandes_min, 2, "NFSMW",
                     "Android ('all' mode only): only move every thread to the performance cluster if it has "
                     "at least this many cores; below it, only the renderer threads ask for it one by one")
    .range(1, 8)
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

REXCVAR_DEFINE_STRING(nfsmw_android_afinidad, "auto", "NFSMW",
                      "Android thread affinity: 'auto' (name-based split re-applied every 2 s), 'all' (every "
                      "thread on the performance cores), '' (do nothing), or rules such as "
                      "'SDLThread=6-7;Main XThread=6+7;*=0-5'")
    .lifecycle(rex::cvar::Lifecycle::kInitOnly);

namespace {

constexpr int kMaxCores = 64;
constexpr auto kReapply = std::chrono::seconds(2);

struct CoreInfo {
  int count = 0;
  long value[kMaxCores] = {};
  const char* source = nullptr;  // null when no usable source
};

struct Rule {
  std::string prefix;  // empty = wildcard
  cpu_set_t cores;
  std::string text;  // for the log
};

std::string Trim(std::string s) {
  const auto first = s.find_first_not_of(" \t");
  const auto last = s.find_last_not_of(" \t\r\n");
  return first == std::string::npos ? std::string() : s.substr(first, last - first + 1);
}

// One small /proc or /sys file, without ifstream: the watchdog reads ~45 of them every 2 s.
bool ReadSmallFile(const char* path, char* buffer, size_t size) {
  const int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    return false;
  }
  const ssize_t read_bytes = read(fd, buffer, size - 1);
  close(fd);
  if (read_bytes <= 0) {
    return false;
  }
  buffer[read_bytes] = '\0';
  for (ssize_t i = read_bytes - 1; i >= 0 && (buffer[i] == '\n' || buffer[i] == '\r'); --i) {
    buffer[i] = '\0';
  }
  return true;
}

// "6-7", "0+1", "0-3+6". Returns false when no core is left.
bool ParseCores(const std::string& list, cpu_set_t& cores) {
  CPU_ZERO(&cores);
  size_t position = 0;
  while (position <= list.size()) {
    size_t end = list.find('+', position);
    if (end == std::string::npos) {
      end = list.size();
    }
    const std::string piece = Trim(list.substr(position, end - position));
    if (!piece.empty()) {
      const size_t dash = piece.find('-');
      const int first = std::atoi(piece.substr(0, dash).c_str());
      const int last = dash == std::string::npos ? first : std::atoi(piece.substr(dash + 1).c_str());
      for (int core = first; core <= last && core < kMaxCores; ++core) {
        if (core >= 0) {
          CPU_SET(core, &cores);
        }
      }
    }
    position = end + 1;
  }
  return CPU_COUNT(&cores) > 0;
}

std::string CoresText(const cpu_set_t& cores) {
  std::string text;
  for (int core = 0; core < kMaxCores; ++core) {
    if (CPU_ISSET(core, &cores)) {
      if (!text.empty()) {
        text += '+';
      }
      text += std::to_string(core);
    }
  }
  return text;
}

// One value per core from /sys/devices/system/cpu/cpuN/<file>, or -1 where it is missing (a powered-down
// core loses its cpufreq on some kernels): that core enters no mask. True when the values tell cores apart.
bool ReadPerCore(const char* file, int count, long* value) {
  long highest = -1;
  long lowest = -1;
  for (int core = 0; core < count; ++core) {
    char path[96];
    char buffer[32];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/%s", core, file);
    value[core] = ReadSmallFile(path, buffer, sizeof(buffer)) ? std::atol(buffer) : -1;
    if (value[core] <= 0) {
      value[core] = -1;
      continue;
    }
    highest = highest < 0 ? value[core] : std::max(highest, value[core]);
    lowest = lowest < 0 ? value[core] : std::min(lowest, value[core]);
  }
  return highest > lowest;
}

CoreInfo ReadCoreInfo() {
  CoreInfo info;
  const long configured = sysconf(_SC_NPROCESSORS_CONF);
  if (configured <= 0) {
    return info;
  }
  info.count = int(std::min<long>(configured, kMaxCores));
  info.source = "cpu_capacity";
  if (!ReadPerCore("cpu_capacity", info.count, info.value)) {
    info.source = "cpufreq/cpuinfo_max_freq";
    if (!ReadPerCore("cpufreq/cpuinfo_max_freq", info.count, info.value)) {
      info.source = nullptr;
    }
  }
  return info;
}

std::vector<Rule> ParseRules(const std::string& text) {
  std::vector<Rule> rules;
  size_t position = 0;
  while (position < text.size()) {
    size_t end = text.find(';', position);
    if (end == std::string::npos) {
      end = text.size();
    }
    const std::string piece = text.substr(position, end - position);
    position = end + 1;
    const size_t equals = piece.find('=');
    if (equals == std::string::npos) {
      continue;
    }
    Rule rule;
    rule.prefix = Trim(piece.substr(0, equals));
    if (rule.prefix == "*") {
      rule.prefix.clear();
    }
    if (!ParseCores(piece.substr(equals + 1), rule.cores)) {
      REXLOG_WARN("[affinity] rule without cores, ignored: '{}'", piece);
      continue;
    }
    rule.text = (rule.prefix.empty() ? "*" : rule.prefix) + "=" + CoresText(rule.cores);
    rules.push_back(std::move(rule));
  }
  return rules;
}

Rule MakeRule(const char* name, const cpu_set_t& cores) {
  Rule rule;
  rule.prefix = name;
  rule.cores = cores;
  rule.text = std::string(name) + "=" + CoresText(cores);
  return rule;
}

// The performance cores are the fastest ones. If every core has the same value there is nothing to split.
//
// With two or fewer big cores and the next cluster below 60 % of the fastest, sending everything else there
// is worse than leaving it free: audio, shader compilation and driver threads would run on cores that are
// less than half as fast. In that case only the critical threads are pinned.
std::vector<Rule> AutoRules(const CoreInfo& info) {
  if (info.source == nullptr || info.count <= 1) {
    REXLOG_INFO("[affinity] auto: no usable cpu_capacity or CPU frequencies, nothing pinned");
    return {};
  }
  long top = -1;
  long second = -1;
  for (int core = 0; core < info.count; ++core) {
    top = std::max(top, info.value[core]);
  }
  for (int core = 0; core < info.count; ++core) {
    if (info.value[core] > 0 && info.value[core] < top) {
      second = std::max(second, info.value[core]);
    }
  }
  if (top <= 0 || second <= 0) {
    REXLOG_INFO("[affinity] auto: every core has the same {}; nothing pinned", info.source);
    return {};
  }
  cpu_set_t prime;
  cpu_set_t middle;
  cpu_set_t rest;
  CPU_ZERO(&prime);
  CPU_ZERO(&middle);
  CPU_ZERO(&rest);
  std::string values;
  for (int core = 0; core < info.count; ++core) {
    if (info.value[core] <= 0) {
      continue;
    }
    values += (values.empty() ? "" : " ") + std::to_string(core) + ':' + std::to_string(info.value[core]);
    if (info.value[core] == top) {
      CPU_SET(core, &prime);
    } else {
      CPU_SET(core, &rest);
      if (info.value[core] == second) {
        CPU_SET(core, &middle);
      }
    }
  }
  REXLOG_INFO("[affinity] auto: by {}: {}", info.source, values);
  const int num_prime = CPU_COUNT(&prime);
  const bool few_big = num_prime <= 2 && second * 10 < top * 6;

  std::vector<Rule> rules;
  if (num_prime >= 2 || few_big) {
    // SDLThread is this port's preparer: the thread that builds the draw list and runs the guest frame work,
    // ~92 % of a core at the lobby. The upstream rules do not name it because there the second cluster is
    // nearly as fast; here it is the heaviest thread, so it must stay on a performance core.
    for (const char* name : {"SDLThread", "GPU Commands", "GPU anillo", "Main XThread"}) {
      rules.push_back(MakeRule(name, prime));
    }
  } else {
    // One prime core: the main thread keeps it, and the GPU threads go to the next tier where they share
    // with the rest. The preparer waits for the main thread every frame, so it stays on the prime too.
    rules.push_back(MakeRule("GPU Commands", middle));
    rules.push_back(MakeRule("GPU anillo", middle));
    rules.push_back(MakeRule("Main XThread", prime));
    rules.push_back(MakeRule("SDLThread", prime));
  }
  if (few_big) {
    REXLOG_INFO("[affinity] auto: {} performance cores and the next cluster below 60 %; only the critical "
                "threads are pinned, the rest is left free",
                num_prime);
    return rules;
  }
  Rule wildcard;
  wildcard.cores = rest;
  wildcard.text = "*=" + CoresText(rest);
  rules.push_back(wildcard);
  return rules;
}

// Re-applies the rules every 2 s. Android changes the cpuset when the app goes to the background and the
// kernel re-balances after a while, so a mask set once would silently stop being true.
class AffinityWatchdog {
 public:
  explicit AffinityWatchdog(std::vector<Rule> rules) : rules_(std::move(rules)) {
    thread_ = std::thread([this]() { Loop(); });
  }

  ~AffinityWatchdog() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      stop_ = true;
    }
    wake_.notify_all();
    if (thread_.joinable()) {
      thread_.join();
    }
  }

 private:
  void Loop() {
    // Otherwise it inherits the creator's name ("SDLThread") and shows up as a second preparer in profiles.
    pthread_setname_np(pthread_self(), "NFS affinity");
    while (true) {
      Pass();
      std::unique_lock<std::mutex> lock(mutex_);
      if (wake_.wait_for(lock, kReapply, [this]() { return stop_; })) {
        return;
      }
    }
  }

  const Rule* RuleFor(const char* name) const {
    for (const Rule& rule : rules_) {
      if (rule.prefix.empty() || std::strncmp(name, rule.prefix.c_str(), rule.prefix.size()) == 0) {
        return &rule;
      }
    }
    return nullptr;
  }

  void Pass() {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) {
      return;
    }
    while (const dirent* entry = readdir(dir)) {
      if (entry->d_name[0] < '0' || entry->d_name[0] > '9') {
        continue;
      }
      const pid_t tid = pid_t(std::atoi(entry->d_name));
      char path[64];
      char name[32];
      std::snprintf(path, sizeof(path), "/proc/self/task/%d/comm", int(tid));
      if (!ReadSmallFile(path, name, sizeof(name))) {
        continue;  // the thread just exited
      }
      const Rule* rule = RuleFor(name);
      if (rule == nullptr) {
        continue;
      }
      // Only touch it when it does not already have the mask: this also restores it after Android changed
      // the cpuset on returning from the background.
      cpu_set_t current;
      if (sched_getaffinity(tid, sizeof(current), &current) == 0 && CPU_EQUAL(&current, &rule->cores)) {
        continue;
      }
      if (sched_setaffinity(tid, sizeof(rule->cores), &rule->cores) != 0) {
        // EINVAL: in the background the cpuset does not include those cores. Normal; it will be restored
        // on return, so do not warn every 2 s.
        if (errno != EINVAL && failed_.insert(tid).second) {
          REXLOG_WARN("[affinity] cannot pin '{}' (tid {}): {}", name, tid, std::strerror(errno));
        }
        continue;
      }
      if (announced_.insert(std::to_string(tid) + '|' + name).second) {
        REXLOG_INFO("[affinity] {} (tid {}) -> {}", name, tid, CoresText(rule->cores));
      }
    }
    closedir(dir);
  }

  const std::vector<Rule> rules_;
  std::thread thread_;
  std::mutex mutex_;
  std::condition_variable wake_;
  bool stop_ = false;
  // Only for the log, touched by the watchdog thread alone.
  std::set<std::string> announced_;
  std::set<pid_t> failed_;
};

AffinityWatchdog* g_watchdog = nullptr;

// Performance mask and count for the "all" mode and for nfsmw_android_hilo_nucleos_grandes().
cpu_set_t g_big_mask;
int g_big_count = 0;

void StartWatchdog(const std::string& mode, std::vector<Rule> rules) {
  if (g_watchdog != nullptr) {
    return;
  }
  std::string summary;
  for (const Rule& rule : rules) {
    if (!summary.empty()) {
      summary += "; ";
    }
    summary += rule.text;
  }
  REXLOG_INFO("[affinity] '{}' -> {}", mode, summary);
  // Never deleted on purpose: the process is the lifetime. A static destructor here could join after the
  // logging system is gone; Android kills the process anyway.
  g_watchdog = new AffinityWatchdog(std::move(rules));
}

}  // namespace

extern "C" void nfsmw_android_nucleos_grandes_aplicar() {
  if (!REXCVAR_GET(nfsmw_android_nucleos_grandes)) {
    return;
  }
  const CoreInfo info = ReadCoreInfo();
  if (info.source == nullptr) {
    REXLOG_INFO("[affinity] cannot read cpu_capacity or CPU core frequencies: affinity unchanged");
    return;
  }
  const std::string mode = Trim(REXCVAR_GET(nfsmw_android_afinidad));
  if (mode.empty()) {
    REXLOG_INFO("[affinity] disabled by nfsmw_android_afinidad");
    return;
  }
  if (mode == "auto") {
    std::vector<Rule> rules = AutoRules(info);
    if (!rules.empty()) {
      StartWatchdog(mode, std::move(rules));
    }
    return;
  }
  if (mode == "all") {
    long best = 0;
    for (int core = 0; core < info.count; ++core) {
      best = std::max(best, info.value[core]);
    }
    if (best <= 0) {
      REXLOG_INFO("[affinity] cannot read cpu_capacity or CPU core frequencies: affinity unchanged");
      return;
    }
    cpu_set_t mask;
    CPU_ZERO(&mask);
    int chosen = 0;
    std::string list;
    for (int core = 0; core < info.count; ++core) {
      // Within 90 % of the best core. With cpu_capacity that is the big cluster and nothing else; with clocks
      // it is the same unless the SoC has a cluster within 10 % of the fastest, in which case keeping it
      // together is exactly what we want (both are performance cores, e.g. 1.8/2.0).
      if (info.value[core] * 10 >= best * 9) {
        CPU_SET(core, &mask);
        ++chosen;
        list += (list.empty() ? "" : ",") + std::to_string(core);
      }
    }
    if (chosen == info.count) {
      REXLOG_INFO("[affinity] all {} CPU cores are equal by {} ({}): affinity unchanged", info.count,
                  info.source, best);
      return;
    }
    g_big_mask = mask;
    g_big_count = chosen;
    const int minimum = REXCVAR_GET(nfsmw_android_nucleos_grandes_min);
    if (chosen < minimum) {
      REXLOG_INFO("[affinity] performance cores {} of {} by {} (best {}); only {} of them, so the process mask "
                  "is left alone and the renderer threads pin themselves",
                  list, info.count, info.source, best, chosen);
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
    REXLOG_INFO("[affinity] 'all': game running on cores {} of {} by {} (best {}); {} threads migrated", list,
                info.count, info.source, best, threads);
    return;
  }
  std::vector<Rule> rules = ParseRules(mode);
  if (rules.empty()) {
    REXLOG_WARN("[affinity] no valid rules in '{}': nothing pinned", mode);
    return;
  }
  StartWatchdog(mode, std::move(rules));
}

// Called from the renderer threads that do the heavy work (ring, vertex copies, cache writer, texture hash).
// In auto/rules mode the watchdog owns the affinity: a one-shot pin here would fight the 2 s re-apply and
// pull light renderer threads back onto the performance cores.
extern "C" void nfsmw_android_hilo_nucleos_grandes() {
  if (g_watchdog != nullptr) {
    return;
  }
  if (g_big_count <= 0) {
    return;
  }
  sched_setaffinity(0, sizeof(g_big_mask), &g_big_mask);
}
