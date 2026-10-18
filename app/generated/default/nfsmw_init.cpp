// Generated for ReXGlue Android runtime - NFSMW
#include "nfsmw_init.h"

static const rex::PPCFuncMapping s_func_mappings[] = {
    { 0, nullptr }
};

const rex::PPCImageInfo PPCImageConfig = {
    .code_base = REX_CODE_BASE,
    .code_size = REX_CODE_SIZE,
    .image_base = REX_IMAGE_BASE,
    .image_size = REX_IMAGE_SIZE,
    .func_mappings = s_func_mappings,
    .rexcrt_heap = REXCRT_HEAP,
    .codegen_flags = {
        .skip_lr = false,
        .ctr_as_local = false,
        .xer_as_local = false,
        .reserved_as_local = false,
        .skip_msr = false,
        .cr_as_local = false,
        .non_argument_as_local = false,
        .non_volatile_as_local = false,
    },
};
