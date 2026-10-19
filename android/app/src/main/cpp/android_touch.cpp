#include <jni.h>

#include <cmath>
#include <cstdint>

#include <rex/cvar.h>
#include <rex/input/sdl/sdl_input_driver.h>

// TouchControlsView hands the pad to the game through
// TouchControllerBridge.setState(int buttons, float lx, float ly, float rx, float ry, float lt, float rt).
// The button mask is in SDL gamepad-button bits (1 << SDL_GAMEPAD_BUTTON_*), the axes are already
// normalised to -1..1 and the triggers to 0..1; rex_sdl_set_touch_gamepad_state wants XInput bits,
// raw int16 axes and uint8 triggers. The bridge does that conversion here, as the Android core the
// overlay was written against did (that is why the overlay sends SDL bits and not XInput bits:
// sending XInput made START arrive as BACK, A as D-pad down and the D-pad as A/B/X/Y).
namespace {

// 1 << SDL_GAMEPAD_BUTTON_*, index 0..14, to XInput bits (X_INPUT_GAMEPAD_*).
constexpr uint16_t kBitsSdlAXInput[15] = {
    0x1000,  // SOUTH  -> A
    0x2000,  // EAST   -> B
    0x4000,  // WEST   -> X
    0x8000,  // NORTH  -> Y
    0x0020,  // BACK
    0x0000,  // GUIDE: no XInput bit
    0x0010,  // START
    0x0040,  // LEFT_STICK  -> LEFT_THUMB
    0x0080,  // RIGHT_STICK -> RIGHT_THUMB
    0x0100,  // LEFT_SHOULDER
    0x0200,  // RIGHT_SHOULDER
    0x0001,  // DPAD_UP
    0x0002,  // DPAD_DOWN
    0x0004,  // DPAD_LEFT
    0x0008,  // DPAD_RIGHT
};

int16_t Eje(float v) {
  const float recortado = v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
  return static_cast<int16_t>(std::lrintf(recortado * 32767.0f));
}

uint8_t Gatillo(float v) {
  const float recortado = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
  return static_cast<uint8_t>(std::lrintf(recortado * 255.0f));
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_nfsmwrecomp_TouchControllerBridge_setState(JNIEnv*, jclass, jint buttons, jfloat left_x,
                                                          jfloat left_y, jfloat right_x, jfloat right_y,
                                                          jfloat left_trigger, jfloat right_trigger) {
  uint16_t xinput = 0;
  for (int i = 0; i < 15; ++i) {
    if ((buttons >> i) & 1) {
      xinput |= kBitsSdlAXInput[i];
    }
  }
  rex_sdl_set_touch_gamepad_state(xinput, Eje(left_x), Eje(left_y), Eje(right_x), Eje(right_y),
                                  Gatillo(left_trigger), Gatillo(right_trigger));
}

extern "C" JNIEXPORT void JNICALL
Java_com_nfsmw_android_TouchControlsView_nativeSetTouchState(JNIEnv*, jclass, jint buttons, jint left_x,
                                                             jint left_y, jint right_x, jint right_y,
                                                             jint left_trigger, jint right_trigger) {
  rex_sdl_set_touch_gamepad_state(static_cast<uint16_t>(buttons), static_cast<int16_t>(left_x),
                                  static_cast<int16_t>(left_y), static_cast<int16_t>(right_x),
                                  static_cast<int16_t>(right_y), static_cast<uint8_t>(left_trigger),
                                  static_cast<uint8_t>(right_trigger));
}

// Screen mode, live: the presenter reads these cvars on every frame (presenter.cpp).
//   stretch = true:  the game image fills the whole phone screen.
//   stretch = false: 16:9 with bars at the sides, as the game was made.
extern "C" JNIEXPORT void JNICALL
Java_com_nfsmw_android_GameActivity_nativeSetStretch(JNIEnv*, jclass, jboolean stretch) {
  rex::cvar::SetFlagByName("present_letterbox", stretch ? "false" : "true");
  if (stretch) {
    // No overscan crop: all of the image, stretched to the screen.
    rex::cvar::SetFlagByName("present_safe_area_x", "100");
    rex::cvar::SetFlagByName("present_safe_area_y", "100");
  }
}
