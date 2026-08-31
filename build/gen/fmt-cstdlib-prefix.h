// Force <cstdlib> (malloc/free) ahead of fmt/format.h, which uses them
// unqualified without including it. Passed via -include to the fmt sources.
#include <cstdlib>
