// Generated for ReXGlue Android runtime - NFSMW
#include "nfsmw_init.h"

PPCFuncMapping PPCFuncMappings[] = {
    { 0, nullptr }
};

const rex::PPCImageInfo PPCImageConfig = {
    .code_base = static_cast<rex::u32>(REX_CODE_BASE),
    .code_size = static_cast<rex::u32>(REX_CODE_SIZE),
    .image_base = static_cast<rex::u32>(REX_IMAGE_BASE),
    .image_size = static_cast<rex::u32>(REX_IMAGE_SIZE),
    .func_mappings = PPCFuncMappings,
    .rexcrt_heap = false,
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
