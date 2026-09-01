/* artzig: stand-in for the aconfig-generated hwui flags header. Production-mode
 * defaults (declaration state = disabled), matching the all-off Java side.
 * Flag list = libs/hwui/aconfig/hwui_flags.aconfig at the pin. */
#pragma once
namespace com::android::graphics::hwui::flags {
inline bool animated_image_drawable_filter_bitmap() { return false; }
inline bool animate_hdr_transitions() { return false; }
inline bool bitmap_ashmem_long_name() { return false; }
inline bool bitmap_parcel_ashmem_as_immutable() { return false; }
inline bool calc_workload_orig_deadline() { return false; }
inline bool clip_shader() { return false; }
inline bool clip_surfaceviews() { return false; }
inline bool draw_region() { return false; }
inline bool early_preinit_buffer_allocator() { return false; }
inline bool early_preload_gl_context() { return false; }
inline bool gainmap_animations() { return false; }
inline bool gainmap_constructor_with_metadata() { return false; }
inline bool high_contrast_text_inner_text_color() { return false; }
inline bool high_contrast_text_small_text_rect() { return false; }
inline bool initialize_gl_always() { return false; }
inline bool iso_gainmap_apis() { return false; }
inline bool limited_hdr() { return false; }
inline bool query_global_priority() { return false; }
inline bool remove_vri_sketchy_destroy() { return false; }
inline bool requested_formats_v() { return false; }
inline bool resample_gainmap_regions() { return false; }
inline bool runtime_color_filters_blenders() { return false; }
inline bool shader_color_space() { return false; }
inline bool skip_eglmanager_telemetry() { return false; }
}
