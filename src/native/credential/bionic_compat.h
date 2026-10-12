// Bionic declarations the credential daemons' stock C++ reaches transitively
// on Android: <sys/endian.h> (via <endian.h> and <netinet/in.h>) provides the
// byte-order helpers, including the 64-bit htonq/ntohq and BSD betoh*, that
// glibc spells differently.
#pragma once
#include <arpa/inet.h>
#include <endian.h>
#define htonq(x) htobe64(x)
#define ntohq(x) be64toh(x)
#define betoh16(x) be16toh(x)
#define betoh32(x) be32toh(x)
#define betoh64(x) be64toh(x)
