// nfsmw - frame rhythm and thermal telemetry (see nfsmw_ritmo.cpp).
#pragma once

namespace nfsmw::ritmo {

// Called once per game frame from the Swap hook (nfsmw_d3d_trace.cpp), after the guest
// function returns, which is the moment the frame is handed to the compositor.
void RegistrarSwap();

}  // namespace nfsmw::ritmo

// Second line of the on-screen FPS counter, e.g. "p95 67  max 183  3v58 4v27  62C 1.7G  st12".
// The pointer is stable until the next call on the same thread; empty until the first window closes.
extern "C" const char* nfsmw_ritmo_hud_line();
