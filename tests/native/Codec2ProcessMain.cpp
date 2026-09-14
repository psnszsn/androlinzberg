// Codec2 bufferpool users return from main while their process-lifetime
// invalidation/eviction workers remain detached. Match AndroidRuntime::exit,
// also used by hosted app/service processes: do not destroy their static mutexes
// under live workers. Preserve the original test's result and output.
#include <cstdio>
#include <cstdlib>
#include <iostream>

int codec2ProcessTestMain(int argc, char** argv);

int main(int argc, char** argv) {
    const int result = codec2ProcessTestMain(argc, argv);
    std::cout.flush();
    std::cerr.flush();
    std::fflush(nullptr);
    std::_Exit(result);
}
