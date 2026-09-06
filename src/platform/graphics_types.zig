//! C layout shared by the host gralloc and presentation seams. Described FDs
//! are borrowed for the lifetime of the corresponding native buffer lease.
pub const Info = extern struct {
    width: u32,
    height: u32,
    format: u32,
    layers: u32,
    usage: u64,
    drm_format: u32,
    stride: u32,
    offset: u32,
    reserved: u32 = 0,
    modifier: u64,
    id: u64,
    fd: i32,
    dataspace: i32 = 0,
};
